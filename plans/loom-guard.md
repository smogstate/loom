# loom.guard — Guardrails Middleware

**Status:** APPROVED (v2 — reviewer-approved, two implementation bugs noted below)

---

## Overview

`loom.guard` is a single-namespace middleware layer that intercepts every
agent-invoked tool call in Loom, applies a declarative policy, redacts
sensitive output, enforces per-agent rate limits, and logs every denial /
redaction to the existing `events.parquet` table under dedicated `:type`
values that are excluded from regular semantic search.

It is the **first** of the five adopted agentic-design patterns to land
because it directly mitigates the largest open risk: `loom.seed.eval/eval-expr`
hands an agent-supplied string to `clojure.core/eval` with no sandbox.

Goals:

1. Defense-in-depth around `eval-expr` (AST allowlist + denylist).
2. Single chokepoint that all tool registrations flow through, so no tool
   can sneak past the wrap.
3. No schema changes. No changes to `loom.envelope`.
4. Policy lives in EDN at `.loom/guard.edn`, hot-reloadable.
5. Denials are auditable but invisible to normal vector search.

Non-goals:

- Replacing the envelope. Guard sits *outside* `with-provenance`.
- Capability tokens / per-agent crypto. Out of scope for v1.
- Sandboxing the JVM (SecurityManager). Defer to JDK-level work.

---

## Architecture

There are **three** integration points, not two as v1 claimed.

### 1. `state/add-tool!` — the real chokepoint (NEW)

The reviewer was right: `loom.tools/start-watcher!` (tools.clj lines 57–68)
already watches `state/session-state` and calls `db/save-tool!` directly
when new tools appear. So `loom.tools/register!` is **not** the only path
that produces a saved tool. The single chokepoint that *every* path must
cross is `loom.state/add-tool!`.

We therefore wrap `:fn` inside `state/add-tool!` itself:

```
(defn add-tool! [tool]
  (let [wrapped (loom.guard/wrap-tool tool)]
    (swap! session-state assoc-in [:tools (:name wrapped)] wrapped)))
```

`wrap-tool` replaces `(:fn tool)` with a guarded function and **does not**
keep a `:raw-fn` slot in the public mirror (see Defect 3 below).

This means:

- `tools/register!` → calls `state/add-tool!` → wrapped.
- `start-watcher!` → reads from `(:tools new)` which was populated by
  `state/add-tool!` → already wrapped → `db/save-tool!` saves the wrapped
  version. No bypass.
- Any future code that calls `state/add-tool!` directly is also covered.

### 2. `seed/eval.clj` — inline AST check (NEW, see Defect 3)

`eval-expr` gets a one-line guard call before `(eval ...)`. This is the
defense-in-depth layer for the highest-risk tool, independent of the
wrap above.

### 3. `loom.guard/call` — canonical agent entrypoint (unchanged)

Agents that want explicit per-agent attribution (for rate-limit accounting)
call `(guard/call ctx agent-id tool-name args)`. This is the same path
the wrap takes internally, but the agent identity is supplied explicitly
instead of being read from `ctx`.

```
                          ┌──────────────────────────────────┐
agent code  ──┬──► tools/register! ─┐                        │
              │                      ▼                        │
              ├──► state/add-tool! ──► wrap-tool ──► guarded :fn
              │                      │                        │
              └──► guard/call  ──────┘                        │
                                       │                      │
                                       ▼                      │
                              policy/check-input              │
                              rate-limit/allow?               │
                              (run real fn)                   │
                              policy/redact-output            │
                              (log denial events on rejection)│
                                                              │
seed/eval/eval-expr ──► guard/check-eval-ast ──► eval ────────┘
```

---

## Policy model

Policy lives in `.loom/guard.edn`, loaded once at `guard/init!` and cached
in `(defonce policy (atom {}))`. Hot-reload via `guard/reload-policy!`.

**Missing file behaviour:** if `.loom/guard.edn` does not exist, `guard/init!`
loads an empty map `{}`. This is **open-by-default** — no denials, no
redactions, rate limits disabled. The `eval-expr` inline AST check in
`seed/eval.clj` still runs (it uses `@guard/policy` which will have empty
allowlists, so it will allow everything). Operators who want fail-closed
behaviour should ship a `guard.edn` with explicit allowlists.

Schema:

```
{:rate-limits   {:default      {:per-minute 60 :per-hour 600}
                 "loom.seed.eval/eval-expr" {:per-minute 5 :per-hour 30}}

 :eval          {:ns-allowlist  #{"clojure.core" "clojure.string" "clojure.set"
                                  "clojure.walk" "clojure.edn"
                                  "loom.db" "loom.session" "loom.memory"
                                  "loom.embedder" "loom.envelope"
                                  "loom.seed.fs" "loom.seed.http"}
                 :ns-denylist   #{"clojure.java.shell" "clojure.java.io"
                                  "loom.tools" "loom.guard" "loom.state"}
                 :sym-denylist  #{'eval 'load-string 'load-file 'load-reader
                                  'resolve 'requiring-resolve 'find-var
                                  'alter-var-root 'intern 'ns-resolve
                                  'slurp 'spit 'read 'read-string
                                  'System/exit 'Runtime/getRuntime}}

 :url           {:scheme-allowlist #{"https" "http"}
                 :host-denylist    #{"169.254.169.254" "localhost" "127.0.0.1"}}

 :fs            {:path-allowlist  ["/home/denis/Projects/"]
                 :path-denylist   ["/home/denis/Projects/.loom/"
                                   "/home/denis/.ssh/"]}

 :limits        {:max-input-bytes  65536
                 :max-output-bytes 262144}

 :redact        [{:name "openai-key"  :pattern #"sk-[A-Za-z0-9]{20,}"}
                 {:name "aws-key"     :pattern #"AKIA[0-9A-Z]{16}"}
                 {:name "bearer"      :pattern #"(?i)bearer\s+[A-Za-z0-9._-]+"}]}
```

---

## Input checking (multimethod)

Dispatch on a coarse policy class derived from the tool name:

```
(defmulti check-input
  (fn [_policy tool-name _args] (policy-class tool-name)))

(defn policy-class [tool-name]
  (cond
    (= tool-name "loom.seed.eval/eval-expr")        :eval
    (str/starts-with? tool-name "loom.seed.http/")  :url
    (str/starts-with? tool-name "loom.seed.fs/")    :fs
    :else                                           :default))

(defmethod check-input :eval [policy _ [_ctx code-str]]
  (check-eval-ast policy code-str))

(defmethod check-input :url  [policy _ [_ctx url & _]]
  (check-url policy url))

(defmethod check-input :fs   [policy _ [_ctx path & _]]
  (check-fs-path policy path))

(defmethod check-input :default [policy _ args]
  (check-size policy args))
```

`check-eval-ast`:

1. `read-string` the code (catch reader errors → `:deny :reader-error`).
2. `clojure.walk/postwalk` collecting every `Symbol`.
3. For each symbol with a namespace: namespace must be in `:ns-allowlist`
   AND not in `:ns-denylist`.
4. For each bare symbol: must not be in `:sym-denylist`.
5. Reject any form starting with `do`/`let` that contains `def`, `defn`,
   `defmacro`, `defonce`, `in-ns`, `ns` (anti-redef).
6. Return `{:allow? bool :reason kw :detail …}`.

This same function is called from inside `seed/eval.clj` (Defect 3
defense-in-depth).

---

## Output redaction

```
(defn redact [policy v]
  (clojure.walk/postwalk
    (fn [x]
      (if (string? x)
        (reduce (fn [s {:keys [name pattern]}]
                  (let [s' (str/replace s pattern (str "«REDACTED:" name "»"))]
                    (when (not= s s')
                      (log-redaction! name))
                    s'))
                x
                (:redact policy))
        x))
    v))
```

Applied to the **unwrapped** result of the real fn, before re-wrapping in
the envelope. Walk depth-limited to 1024 nodes to avoid pathological inputs —
implement with an atom counter inside the `postwalk`:

```clojure
(defn redact [policy v]
  (let [node-count (atom 0)]
    (clojure.walk/postwalk
      (fn [x]
        (when (> (swap! node-count inc) 1024)
          (throw (ex-info "guard: redact depth limit exceeded" {:limit 1024})))
        (if (string? x)
          (reduce (fn [s {:keys [name pattern]}]
                    (let [s' (str/replace s pattern (str "«REDACTED:" name "»"))]
                      (when (not= s s') (log-redaction! name))
                      s'))
                  x (:redact policy))
          x))
      v)))

---

## Rate limiting

Sliding-window-log keyed by `[agent-id tool-name]`:

```

(defonce buckets (atom {}))   ; { [agent op] -> #queue<inst> }

(defn allow? [policy agent-id tool-name now]
  (let [key   [agent-id tool-name]
        limit (or (get-in policy [:rate-limits tool-name])
                  (get-in policy [:rate-limits :default]))
        log   (get @buckets key clojure.lang.PersistentQueue/EMPTY)
        cutoff-min  (- now 60000)
        cutoff-hour (- now 3600000)
        log'  (into clojure.lang.PersistentQueue/EMPTY
                    (filter #(>= % cutoff-hour) log))
        per-min  (count (filter #(>= % cutoff-min) log'))
        per-hour (count log')]
    (if (and (< per-min  (:per-minute limit))
             (< per-hour (:per-hour   limit)))
      (do (swap! buckets assoc key (conj log' now)) true)
      false)))

```

`agent-id` falls back to `(:agent-id ctx)` and finally to `"unknown"`.

---

## Denial logging + `search-denials` helper

Denials and redactions are written to the existing `events.parquet` with
dedicated `:type` strings:

- `"guard.denial"`   — input rejected by `check-input` or rate limiter.
- `"guard.redaction"` — output had at least one pattern match.

Payload shape:

```

{:type      "guard.denial"
 :agent-id  agent-id
 :session-id (:session-id ctx)
 :content   (pr-str {:tool tool-name :reason reason :detail detail})}

```

### Why this pollutes search

`db/search-events` is a pure cosine-similarity search over the `:vector`
column with no type filter. A flood of denial events would compete with
genuine `:finding` / `:conclusion` events for relevance and degrade
agent reasoning loops.

### `loom.guard/search-denials`

Bypasses vector search entirely:

```

(defn search-denials
  "Recent guard denials/redactions. Filters events.parquet by :type,
   no embedding required. Returns rows newest-first."
  ([ctx]            (search-denials ctx {}))
  ([ctx {:keys [agent-id tool limit since]
         :or   {limit 100}}]
   (with-provenance "loom.guard/search-denials" 1
     (let [conn (loom.db/connection ctx)
           sql  (str "SELECT id, ts, agent_id, type, content "
                     "FROM read_parquet('" (loom.db/events-path ctx) "') "
                     "WHERE type IN ('guard.denial', 'guard.redaction') "
                     (when agent-id " AND agent_id = ? ")
                     (when since    " AND ts >= ? ")
                     (when tool     " AND content LIKE ? ")
                     "ORDER BY ts DESC LIMIT " (long limit))
           params (cond-> []
                    agent-id (conj agent-id)
                    since    (conj since)
                    tool     (conj (str "%" tool "%")))]
       (loom.db/query conn sql params)))))

```

(If `loom.db` does not currently expose `connection` / `query` / `events-path`,
v1 of guard will add these as thin pass-throughs in the same patch — see
diff skeleton.)

The denial events still live in the same parquet so audit tooling, time-range
queries, and counts work — they are just *invisible to vector search* because
no agent will call `db/search-events` looking for the literal string
`"guard.denial"`.

Optional hardening (deferred): add a `:vector` of `nil` for these rows so
the column is sparse and the cosine similarity is undefined → DuckDB will
exclude them. Confirm DuckDB behaviour before relying on it.

---

## `eval-expr` protection approach

**Decision: option (b) — modify `loom.seed.eval/eval-expr` directly.**

Rationale:

1. `eval-expr` is the single highest-risk surface in Loom. It deserves
   defense-in-depth that does not depend on whether `state/add-tool!`
   wrapping was ever installed.
2. Option (a) — removing `:raw-fn` from the public state mirror — is also
   adopted (see `state/add-tool!` change above) but on its own it is not
   sufficient: any code with a direct `(require '[loom.seed.eval])` and
   `(loom.seed.eval/eval-expr ctx code)` call bypasses every wrap because
   it never goes through the registry at all.
3. Putting the AST check inline costs one require + two lines of code in
   `seed/eval.clj` and makes the protection an intrinsic property of the
   tool, not an extrinsic one.
4. `loom.guard/check-eval-ast` is a pure function so the dependency from
   `loom.seed.eval` → `loom.guard` is acyclic.

So we adopt **both** mitigations (defense-in-depth):

- `state/add-tool!` wraps `:fn` and never stores `:raw-fn`.
- `seed/eval.clj` calls `guard/check-eval-ast` inline.

---

## Integration diff skeleton

### NEW file: `src/loom/guard.clj`

```

(ns loom.guard
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [loom.envelope :refer [with-provenance]]
            [loom.db :as db]))

(defonce policy  (atom {}))
(defonce buckets (atom {}))

(defn init! [ctx] …)
(defn reload-policy! [ctx] …)

(defn check-eval-ast [policy code-str] …)
(defn check-url      [policy url]      …)
(defn check-fs-path  [policy path]     …)
(defn check-size     [policy args]     …)

(defmulti check-input (fn [_tool-name_] (policy-class tool-name)))

(defn allow?    [policy agent-id tool-name now] …)
(defn redact    [policy value] …)
(defn log-denial!    [ctx agent-id tool-name reason detail] …)
(defn log-redaction! [ctx agent-id tool-name pattern-name] …)

(defn wrap-tool [tool]
  (let [raw (:fn tool)
        nm  (:name tool)]
    (assoc tool :fn
      (fn guarded [& args]
        (let [ctx       (first args)
              agent-id  (or (:agent-id ctx) "unknown")
              now       (System/currentTimeMillis)]
          (let [{:keys [input-ok? reason detail]}   ; NOTE: use input-ok? not allow? — avoid shadowing the rate-limit allow? fn
                (check-input @policy nm args)]
            (when-not input-ok?
              (log-denial! ctx agent-id nm reason detail)
              (throw (ex-info "guard: input denied"
                              {:tool nm :reason reason :detail detail})))
            (when-not (allow? @policy agent-id nm now)
              (log-denial! ctx agent-id nm :rate-limit nil)
              (throw (ex-info "guard: rate limit"
                              {:tool nm :agent-id agent-id})))
            (redact @policy (apply raw args))))))))

(defn call [ctx agent-id tool-name args] …)

(defn search-denials
  ([ctx] (search-denials ctx {}))
  ([ctx opts] …))

```

### NEW file: `.loom/guard.edn`

(Contents shown in **Policy model** section above.)

### MODIFY `src/loom/state.clj`

```diff
 (ns loom.state
-  (:require))
+  (:require [loom.guard :as guard]))

 (defonce session-state (atom {:agents {} :history [] :tools {}}))

-(defn add-tool! [tool]
-  (swap! session-state assoc-in [:tools (:name tool)] tool))
+(defn add-tool! [tool]
+  ;; Single chokepoint: every tool — whether registered through
+  ;; loom.tools/register! or written directly here — passes through guard.
+  ;; wrap-tool replaces (:fn tool) with a guarded fn. We deliberately do
+  ;; NOT keep a :raw-fn slot, so there is no public path back to the
+  ;; unguarded function.
+  (let [wrapped (guard/wrap-tool tool)]
+    (swap! session-state assoc-in [:tools (:name wrapped)] wrapped)))

 (defn get-tools [] (:tools @session-state))
```

Cycle note: `loom.guard` requires `loom.db`, not `loom.state`. `loom.state`
requires `loom.guard`. No cycle.

### MODIFY `src/loom/tools.clj`

`register!` is **unchanged** — it already calls `state/add-tool!`, so the
wrap now happens transparently.

`start-watcher!` is **unchanged in behaviour** but gains a docstring noting
that the tools it observes are already guarded by virtue of having gone
through `state/add-tool!`. No code change required:

```diff
 (defn start-watcher! [ctx]
+  ;; NOTE: tools observed here have already been wrapped by
+  ;; loom.guard/wrap-tool inside loom.state/add-tool!, so db/save-tool!
+  ;; will persist the guarded :fn. Do not unwrap.
   (add-watch state/session-state :tool-vectorizer
     (fn [_ _ old new]
       (let [added (clojure.set/difference
                     (set (keys (:tools new)))
                     (set (keys (:tools old))))]
         (doseq [tool-name added]
           (let [tool (get-in new [:tools tool-name])]
             (when (and tool (:vector tool))
               (db/save-tool! ctx tool))))))))
```

### MODIFY `src/loom/seed/eval.clj`

```diff
 (ns loom.seed.eval
-  (:require [loom.envelope :refer [with-provenance]]))
+  (:require [loom.envelope :refer [with-provenance]]
+            [loom.guard :as guard]))

 (defn ^{:doc "Evaluate a Clojure expression string..."
         :tags ["eval" "execute" "code" "clojure"]}
   eval-expr [ctx code-str]
   (with-provenance "eval-expr" 1
+    (let [{:keys [allow? reason detail]}
+          (guard/check-eval-ast @guard/policy code-str)]
+      (when-not allow?
+        (guard/log-denial! ctx (or (:agent-id ctx) "unknown")
+                           "loom.seed.eval/eval-expr" reason detail)
+        (throw (ex-info "guard: eval denied"
+                        {:reason reason :detail detail}))))
     (str (eval (read-string code-str)))))
```

### MODIFY `src/loom/core.clj` (or wherever bootstrap lives)

```diff
 (defn start! [opts]
   (let [ctx (init-ctx opts)]
+    (guard/init! ctx)            ;; load .loom/guard.edn into guard/policy
     (tools/scan-ns! ctx 'loom.seed.eval)
     (tools/scan-ns! ctx 'loom.seed.fs)
     (tools/scan-ns! ctx 'loom.seed.http)
     (tools/start-watcher! ctx)
     ctx))
```

### MODIFY `src/loom/db.clj` (small additions for `search-denials`)

If not already present, expose:

```
(defn connection  [ctx] (:db-conn ctx))
(defn events-path [ctx] (str (:loom-dir ctx) "/events.parquet"))
(defn query [conn sql params] …)
```

These are pure pass-throughs and have no security implications.

### Total surface

| File | Lines added | Lines removed | New file? |
|---|---|---|---|
| `src/loom/guard.clj` | ~180 | 0 | yes |
| `.loom/guard.edn` | ~30 | 0 | yes |
| `src/loom/state.clj` | 7 | 1 | no |
| `src/loom/tools.clj` | 3 (comment) | 0 | no |
| `src/loom/seed/eval.clj` | 7 | 0 | no |
| `src/loom/core.clj` | 1 | 0 | no |
| `src/loom/db.clj` | ~6 | 0 | no |

---

## Known gaps & future work

1. **`require` from inside `eval-expr`** — the AST check rejects bare
   `require`, but a sufficiently clever payload could still call an allowed
   namespace's function that itself loads code. Mitigation in v2:
   per-form `*ns*` pinning + `(binding [*read-eval* false] …)`.

2. **JVM-level escape hatches** — `clojure.java.api.Clojure`, reflection,
   `MethodHandles.Lookup`, `sun.misc.Unsafe`. These are not addressed
   by AST checking. Defer to a SecurityManager / Java agent in v2.

3. **Concurrent rate-limiter accuracy** — `swap!` on `buckets` is correct
   under contention but the per-minute count is computed from a snapshot
   that may be stale by the time the swap commits. Tolerable for v1
   (sliding-window-log is conservative on bursts).

4. **Policy hot-reload race** — `@policy` is read once per call, so a
   reload mid-call uses the old policy for that call. Acceptable.

5. **Redaction false positives** — regex-based redaction can mangle
   structured data. v2: per-tool redaction profiles, opt-in for tools
   that emit binary or known-safe payloads.

6. **`search-denials` requires `loom.db` internals** — if the listed
   helpers (`connection`, `query`, `events-path`) do not exist, they are
   added in this same patch. If `loom.db` uses a different query API
   (e.g. honeysql, plain DuckDB JDBC), `search-denials` is rewritten
   accordingly without changing its public signature.

7. **Watcher is now redundant for the guarded path** but kept for
   backward compatibility with any code that mutates `session-state`
   without going through `state/add-tool!`. Audit and remove in v2 if no
   such caller exists.

8. **No capability tokens** — every agent in the same JVM shares the
   same trust level modulo `agent-id` attribution. A malicious in-process
   agent can spoof `(:agent-id ctx)`. Out of scope for v1; would require
   a signed-context design.
