# loom.budget — Resource-Aware Optimization

**Status: DRAFT**

Track per-tool-call duration and cost. Enforce per-agent budgets. Feed evaluation
and monitoring via a queryable `usage.parquet`.

---

## 1. Storage — `usage.parquet`

New global table at `<loom-dir>/usage.parquet`. Append-only; no retire flag.

```sql
CREATE TABLE IF NOT EXISTS usage (
  id          VARCHAR PRIMARY KEY,
  ts          TIMESTAMP DEFAULT now(),
  session_id  VARCHAR,
  agent_id    VARCHAR,         -- nilable; from *agent-id* binding
  op          VARCHAR,         -- e.g. "loom.db/search-tools"
  version     INTEGER,
  duration_ms BIGINT,
  ok          BOOLEAN,
  usd_cost    DOUBLE,          -- 0.0 if no price-table entry
  tokens_in   INTEGER,         -- nilable; v1 = NULL
  tokens_out  INTEGER          -- nilable; v1 = NULL
)
```

Rationale: dedicated table — never merge into `events.parquet` (would poison
`db/search-events` cosine ranking; same lesson as guard plan defect 5).

Cost lookup: `<loom-dir>/budget.edn`

```clojure
{:default       {:usd-per-call 0.0}
 :ops           {"loom.embedder/embed"      {:usd-per-call 0.0}
                 "loom.seed.eval/eval-expr" {:usd-per-call 0.0}}
 :budgets       {:default  {:usd 1.00 :duration-ms 60000 :calls 1000}
                 "analyzer" {:usd 5.00 :duration-ms 300000 :calls 5000}
                 "finder"   {:usd 1.00 :duration-ms 60000 :calls 2000}}}
```

---

## 2. Hook point

**`envelope.clj` `with-provenance` (defmacro at line 7, body lines 25–45)** is
the only place that already sees `:op`, `:duration-ms`, `:ok?` for *every* tool.
But mutating it would couple envelope to budget.

**Decision:** keep `with-provenance` pure. Add a thin `loom.budget/call`
entrypoint (mirrors `loom.guard/call` from the guard plan). Signature is
**explicit-seq** (not varargs) — args is a single seq the caller assembles:

```
agent → (loom.budget/call ctx op-fn [arg1 arg2 ...])
        ├─ enforce! ctx *agent-id*           ; throws if over
        └─ (let [env (apply op-fn ctx args)] ; envelope from with-provenance
             (record! ctx env *agent-id*)
             env)
```

`*agent-id*` is a dynamic var bound by the agent harness once per turn:
```clojure
(binding [loom.budget/*agent-id* "analyzer"] ...)
```

For tools invoked outside `call` (legacy paths, internal db ops), no usage row
is recorded — acceptable, those are infrastructure not agent actions.

---

## 3. Public API — `src/loom/budget.clj`

```clojure
(ns loom.budget
  (:require [loom.db :as db]
            [loom.envelope :refer [with-provenance]]
            [clojure.core.async :as async]))

(def ^:dynamic *agent-id* nil)

;; --- config -----------------------------------------------------------------
(defn load-config
  "Read <loom-dir>/budget.edn (memoised w/ TTL). Returns config map."
  [ctx]
  (with-provenance "loom.budget/load-config" 1
    ...))

;; --- recording --------------------------------------------------------------
(defn record!
  "Append a usage row derived from an envelope. Returns envelope unchanged.
   Batched via an in-memory ring; flushed every N rows or every T ms."
  [ctx envelope agent-id]
  (with-provenance "loom.budget/record!" 1
    ...))

;; --- query ------------------------------------------------------------------
(defn budget-for
  "Resolve effective budget map for agent-id from budget.edn, merging :default.
   => {:usd 5.00 :duration-ms 300000 :calls 5000}"
  [ctx agent-id]
  (with-provenance "loom.budget/budget-for" 1
    ...))

(defn current-usage
  "Sum {:usd :duration-ms :calls} for agent-id within the current session.
   SQL: SELECT SUM(usd_cost), SUM(duration_ms), COUNT(*) FROM usage WHERE ..."
  [ctx agent-id]
  (with-provenance "loom.budget/current-usage" 1
    ...))

(defn enforce!
  "Throw ex-info :budget-exceeded if current-usage >= budget-for.
   No-op if agent-id is nil. Returns nil on success."
  [ctx agent-id]
  (with-provenance "loom.budget/enforce!" 1
    ...))

;; --- entrypoint -------------------------------------------------------------
(defn call
  "Canonical agent entrypoint: enforce, run, record.
   args is an explicit seq (not varargs) — caller controls the shape."
  [ctx op-fn args]
  (with-provenance "loom.budget/call" 1
    (enforce! ctx *agent-id*)
    (let [env (apply op-fn ctx args)]
      (record! ctx env *agent-id*)
      env)))

;; --- reporting --------------------------------------------------------------
(defn report
  "Aggregated usage report. opts: {:session-id :agent-id :since-ms :group-by}.
   Returns vec of {:agent-id :op :calls :usd :duration-ms}."
  [ctx opts]
  (with-provenance "loom.budget/report" 1
    ...))
```

### Batching detail
- In-process ring buffer (atom holding vec of pending rows).
- Flush triggers: ≥ 64 rows OR ≥ 5s since first pending row.
- Flush goes through existing `db/start-writer!` queue (single-writer guarantee).
- Periodic flush via one go-loop started by `init!`.

---

## 4. Integration diff skeleton

### NEW: `src/loom/budget.clj`
~150 lines, structure above.

### NEW: `.loom/budget.edn`
Default config (committed example).

### EDIT: `src/loom/db.clj`

Add usage DDL + read/write helpers, mirroring the events block (~30 lines).
Insertion point: after the events section, before chunks (~line 575).

```diff
+ ;; ---------------------------------------------------------------------------
+ ;; Usage — global (loom.budget)
+ ;; ---------------------------------------------------------------------------
+
+ (def ^:private usage-ddl
+   "CREATE TABLE IF NOT EXISTS usage ( ... )")   ; schema from §1
+
+ (defn- load-usage-table! [conn path] ...)
+ (defn- flush-usage!     [conn path] ...)
+
+ (defn save-usage-batch!
+   "Append a batch of usage rows. Routed through write! queue."
+   [ctx rows]
+   (with-provenance "loom.db/save-usage-batch!" 1
+     (write! (fn [] ...))))
+
+ (defn query-usage
+   "Run a parameterised SELECT against usage.parquet. SQL string + params."
+   [ctx sql & params] ...)
```

### EDIT: `src/loom/core.clj`

Two lines in `start!`:

```diff
   (let [ctx (make-ctx opts)]
     (bootstrap! ctx)
+    (require 'loom.budget)
+    ((resolve 'loom.budget/init!) ctx)   ; loads cfg, starts flush loop
     (scratch/load-all! ctx)
```

### EDIT: agent harness (wherever it lives, e.g. opencode subagent definition)

```diff
- (some-tool ctx arg1 arg2)
+ (binding [loom.budget/*agent-id* agent-id]
+   (loom.budget/call ctx some-tool [arg1 arg2]))   ; args is an explicit seq
```

(One-line change per agent entrypoint. Out-of-band tool calls keep working —
they just don't get metered.)

### NO CHANGE: `envelope.clj`, `embedder.clj`
Stay pure.

---

## 5. Known gaps

1. **Token counts unavailable.** `with-provenance` doesn't see request/response
   payloads. v1 prices per-call only. v2 needs LLM clients to attach
   `:tokens-in` / `:tokens-out` to the envelope's `:provenance` map; budget
   reads them when present.
2. **Cost for embedder = 0** because it's local Ollama. If a hosted embedder
   is added, update `budget.edn` only.
3. **Bypass paths.** Any tool invoked directly (not through `loom.budget/call`)
   is not recorded. Acceptable for internal db helpers; risk if an agent calls
   tools via raw `state/get-tools` lookup. Same chokepoint problem as guard —
   solved the same way: agents are *required* to use the `call` entrypoint;
   audit via `report` showing zero usage from a known-active agent.
4. **No hard kill.** `enforce!` throws *before* the call. A long-running call
   already in flight won't be interrupted — budget is exceeded then enforced
   on the *next* call. Cooperative, not preemptive.
5. **Concurrency on the ring.** Atom + `swap!` is fine for ≤ a few hundred
   ops/sec. Above that, switch to a `j.u.c.ConcurrentLinkedQueue`.
6. **Cross-session budgets.** v1 keys `current-usage` to the current session
   only. Per-day or per-agent-lifetime budgets need a `WHERE ts > ?` variant —
   trivial extension, deferred.
7. **Price-table drift.** `budget.edn` is loaded once and TTL-cached. A
   long-running process won't see edits without a SIGHUP-equivalent. Add
   `loom.budget/reload-config!` for now.

---

## Out of scope (v1)

- Auto-throttling / backoff
- Cost prediction before call
- Per-tool quotas (only per-agent)
- Streaming/partial-result accounting
