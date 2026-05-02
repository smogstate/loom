# loom.hitl — Async Human-in-the-Loop Approval Queue

**Status:** DRAFT (v3)

> Changes from v2: clarified that `with-file-lock!` locks a **sidecar**
> file (`approvals.parquet.lock`), not the parquet data file itself —
> locking the parquet would race DuckDB's `COPY TO` (truncate+rewrite).
>
> Changes from v1: renamed `await` → `await!` (clojure.core clash);
> documented breaking return-type change in `suggest-promotion!`;
> resolved cross-process race via CLI-as-sole-writer constraint + FileLock;
> added nullable `blob_ref` to schema; clarified `db.clj` exposure risk.

## Goal

Replace the blocking `(read-line)` in `loom.memory/suggest-promotion!`
(`src/loom/memory.clj:38`) with a persistent, async approval queue. The
nREPL-hosted agent enqueues requests (append-only) and either
fire-and-forgets or `await!`s a decision; humans clear the queue
out-of-band via a CLI. Every decision is an auditable event.

---

## 1. Storage — `approvals.parquet`

Lives at `<loom-dir>/approvals.parquet`. Follows the existing `db.clj`
load-table → mutate → flush-table pattern (mirrors `events`/`facts`).

```sql
CREATE TABLE IF NOT EXISTS approvals (
  id          VARCHAR PRIMARY KEY,
  created_at  TIMESTAMP DEFAULT now(),
  kind        VARCHAR,            -- e.g. "promote-fact", "register-tool"
  payload     VARCHAR,            -- JSON: small inline request data
  blob_ref    VARCHAR,            -- nullable: blobs.id for large payloads
  status      VARCHAR DEFAULT 'pending',  -- pending | approved | rejected
  decided_by  VARCHAR,
  decided_at  TIMESTAMP,
  notes       VARCHAR,
  session_id  VARCHAR,            -- requesting session
  agent_id    VARCHAR             -- requesting agent
);
```

Indexed access is by `id` (PK) and by `status='pending'` filter scan — fine
at expected volumes (tens to low hundreds). Use `blob_ref` (FK-style ref to
`blobs.id`) when payload would exceed ~16 KB (e.g. a proposed tool source).

---

## 2. Concurrency model — CLI is sole reader/decider

**Constraint (v1):** the parquet file is **append-mostly from nREPL** and
**read+update-only from the CLI**.

| Process       | Allowed ops on `approvals.parquet`                |
|---------------|---------------------------------------------------|
| nREPL agent   | `request!` (INSERT new row, status=`pending`)     |
| CLI           | `pending` (SELECT), `approve!`/`reject!` (UPDATE) |
| nREPL agent   | `await!` (SELECT by id, polling — read-only)      |

This makes the dual-writer load/mutate/COPY race trivially impossible:
only one process ever **modifies existing rows**. Two simultaneous
`request!` calls from the nREPL agent are already serialized by
`db/write!`. The CLI is single-process by convention (don't run two CLIs).

**Belt-and-suspenders:** wrap every `flush-approvals!` (in both processes)
with a `java.nio.channels.FileLock` on a **sidecar** lock file at
`<loom-dir>/approvals.parquet.lock` — **never** the parquet data file
itself. The parquet file is opened, truncated, and rewritten by DuckDB's
`COPY TO`; holding a `FileLock` on it would race `COPY TO`'s own file
handle. The sidecar is touched-only and exists solely as a lock target.
A lost lock attempt retries up to 3 times with 100 ms backoff, then
throws.

```clojure
(defn- approvals-lock-path [ctx]
  ;; SIDECAR file — distinct from approvals.parquet.
  (str (get-in ctx [:config :loom-dir]) "/approvals.parquet.lock"))

(defn- with-file-lock!
  "Acquire an exclusive FileLock on the sidecar `lock-path`, run f, release.
   `lock-path` MUST be the sidecar (e.g. approvals.parquet.lock), not the
   parquet data file — DuckDB COPY TO would race a lock on the data file."
  [lock-path f]
  (with-open [ch (java.nio.channels.FileChannel/open
                   (.toPath (io/file lock-path))
                   (into-array java.nio.file.OpenOption
                     [java.nio.file.StandardOpenOption/CREATE
                      java.nio.file.StandardOpenOption/WRITE]))]
    (let [lock (.lock ch)]
      (try (f) (finally (.release lock))))))

;; usage:
;; (with-file-lock! (approvals-lock-path ctx)
;;   #(db/write! (fn [] (load-approvals! ...) ... (flush-approvals! ...))))
```

Documented limitation: running two CLIs concurrently is unsupported.
`approve!` still uses `WHERE status='pending'` so a double-decide no-ops
rather than corrupting state.

---

## 3. Public API — `loom.hitl`

All fns return envelopes via `with-provenance`. Decisions also emit an
`:approval` / `:rejection` event through `db/log-event!` so they show up in
`db/search-events`.

```clojure
(ns loom.hitl
  (:require [loom.db :as db]
            [loom.envelope :refer [with-provenance unwrap!]]
            [cheshire.core :as json]
            [clojure.core.async :as async]))

(defn request!
  "Enqueue an approval request. Returns {:id ... :status \"pending\"}.
   opts: {:kind str :payload map :blob-ref str?}"
  [ctx {:keys [kind payload blob-ref] :as opts}]
  (with-provenance "loom.hitl/request!" 1 ...))

(defn pending
  "Return all pending approval rows, oldest first. CLI-only in practice."
  [ctx]
  (with-provenance "loom.hitl/pending" 1 ...))

(defn get-approval
  "Fetch a single approval row by id (read-only; safe from any process)."
  [ctx id]
  (with-provenance "loom.hitl/get-approval" 1 ...))

(defn approve!
  "Mark an approval approved. CLI-only. Logs an :approval event.
   Uses WHERE status='pending' so double-decide no-ops."
  [ctx id {:keys [decided-by notes]}]
  (with-provenance "loom.hitl/approve!" 1 ...))

(defn reject!
  "Mark an approval rejected. CLI-only. Logs a :rejection event."
  [ctx id {:keys [decided-by notes]}]
  (with-provenance "loom.hitl/reject!" 1 ...))

(defn await!
  "Block (with timeout-ms) until the approval is decided.
   Renamed from `await` to avoid shadowing clojure.core/await.
   Polls every 500 ms via get-approval (read-only).
   Returns the decided row, or :timeout."
  [ctx id timeout-ms]
  (with-provenance "loom.hitl/await!" 1 ...))
```

**In-process notify hook (optional optimization).** Maintain
`(defonce ^:private waiters (atom {}))` mapping id → `promise-chan`. Only
useful when `request!` and the decision happen in the *same* JVM (rare —
typically the CLI is a separate JVM). For the cross-process case `await!`
just polls. Keep it as an optimization, not a correctness mechanism.

---

## 4. Replace blocking `read-line` in `suggest-promotion!`

**File:** `src/loom/memory.clj`

### ⚠ Breaking change

Return type of `suggest-promotion!` changes:

| Before                          | After                                 |
|---------------------------------|---------------------------------------|
| `<fact-id-uuid-string>` on `y`  | `{:id <approval-id> :status "pending"}` |
| `:skipped` on `n`               | (no synchronous skip — handled by CLI) |

Any caller doing `(= :skipped result)` or treating the result as a
fact-id will break. **Audit needed:** no in-tree callers found, but
external agents/scripts may depend on the old shape. To get the legacy
behavior, callers can `(hitl/await! ctx (:id result) ms)` and inspect
`(:status decided)` (`"approved"` → look up the resulting fact-id via the
linked `:approval` event; `"rejected"` → treat as `:skipped`).

### Diff

```diff
@@ -1,7 +1,7 @@ src/loom/memory.clj
 (ns loom.memory
   "Tier-3 global memory: promote session facts to permanent storage,
    suggest promotions, and retire facts that are no longer relevant."
-  (:require [clojure.string :as str]
-            [loom.db :as db]
+  (:require [loom.db :as db]
+            [loom.hitl :as hitl]
             [loom.embedder :as embedder]
             [loom.envelope :refer [with-provenance unwrap!]]))
@@ -30,12 +30,14 @@ src/loom/memory.clj
 (defn suggest-promotion!
-  "Print a suggestion to stdout and wait for user input (y/n)."
+  "Enqueue a promotion request to the HITL queue. NON-BLOCKING.
+   BREAKING: returns {:id <approval-id> :status \"pending\"} instead of
+   a fact-id or :skipped. Caller may (hitl/await! ctx id ms) to block,
+   or fire-and-forget. The CLI (clj -M:hitl) processes pending rows."
   [ctx content suggestion-text]
   (with-provenance "loom.memory/suggest-promotion!" 1
-    (println (str "\n[LOOM] Promotion suggestion: " suggestion-text))
-    (println (str "  Fact: " content))
-    (print "  Promote? (y/n): ")
-    (flush)
-    (let [answer (str/trim (read-line))]
-      (if (= "y" answer)
-        (unwrap! (promote! ctx content {}))
-        :skipped))))
+    (unwrap! (hitl/request! ctx
+               {:kind    "promote-fact"
+                :payload {:content    content
+                          :suggestion suggestion-text}}))))
```

A separate **decision applier** consumes approved rows and actually calls
`promote!`. Two viable placements:

- **(A) In CLI:** when the operator approves, the CLI immediately invokes
  `promote!` for `kind="promote-fact"`. Simple, explicit. Recommended v1.
- **(B) Background poller `loom.hitl/start-applier!`:** scans approved
  rows, applies side effects, marks `status="applied"`. Better for
  headless flows; defer to v2.

---

## 5. CLI — `clj -M:hitl`

Add to `deps.edn`:

```clojure
:hitl {:main-opts ["-m" "loom.hitl.cli"]}
```

`src/loom/hitl/cli.clj` — minimal interactive loop:

```
[loom-hitl] 3 pending
  1. promote-fact   2026-04-30 10:11   "Service X runs on :9090"
  2. promote-fact   2026-04-30 10:12   "Build uses tools.build"
  3. register-tool  2026-04-30 10:14   "fetch-rss"

> a 1                       ; approve #1
> r 2 not stable yet        ; reject #2 with note
> v 3                       ; view full payload (resolves blob_ref if set)
> q
```

Behavior:
1. Build a ctx via `loom.core/make-ctx` (no nREPL/repl/watcher).
2. Loop: print `(hitl/pending ctx)`, read commands, dispatch to
   `approve!`/`reject!`.
3. On approving a `promote-fact`, immediately call `loom.memory/promote!`
   with the payload (applier strategy A).
4. `q` exits cleanly.

Non-interactive single-shot mode:
`clj -M:hitl approve <id>` / `clj -M:hitl reject <id> <notes...>` /
`clj -M:hitl list`.

**Operational rule:** run **at most one** CLI at a time. The constraint
in §2 makes this safe-by-design.

---

## 6. Integration diff skeleton

### `src/loom/db.clj`

Two options:

**Option A (decoupled, recommended).** Expose `write!`, `exec!`, `query`
so `loom.hitl` can own its own DDL without `db.clj` knowing about it.

```diff
@@ -41,7 +41,11 @@ src/loom/db.clj
-(defn- write!
-  "Serialize a thunk through the write queue. Blocks caller until done."
+(defn write!
+  "Serialize a thunk through the single-writer queue. Blocks caller until
+   done. PUBLIC for extension namespaces (e.g. loom.hitl); do NOT call
+   from outside loom.* — bypassing this queue corrupts parquet writes."
   [f] ...)
@@ -53,12 +57,16 @@ src/loom/db.clj
-(defn- exec!
-  "Execute a SQL statement on conn. Returns update count."
+(defn exec!
+  "Execute a SQL statement on conn. Returns update count.
+   PUBLIC for extension namespaces. Must be called inside a `write!` thunk
+   for any mutation — direct calls from caller threads race the writer."
   [^java.sql.Connection conn sql & params] ...)

-(defn- query
-  "Execute a SELECT and return rows as vec of maps."
+(defn query
+  "Execute a SELECT and return rows as vec of maps.
+   PUBLIC for extension namespaces. Reads do not need the write queue."
   [^java.sql.Connection conn sql & params] ...)
```

**Option B (coupled).** Put `approvals` DDL/load/flush directly inside
`db.clj` next to `events`. Smaller diff; tighter coupling. Pick A.

### `src/loom/memory.clj`

See §4 for the full diff (with breaking-change notice).

### `src/loom/core.clj`

Nothing required for v1 (CLI builds its own ctx). For background
applier (v2):

```diff
@@ -78,6 +78,9 @@ src/loom/core.clj
      (scratch/load-all! ctx)
      (maybe-ingest-loom-md! ctx)
      (tools/start-watcher! ctx)
+     (when (get-in opts [:hitl :auto-apply?])
+       (require 'loom.hitl)
+       ((resolve 'loom.hitl/start-applier!) ctx))
      (repl/start!)
      (println "[loom] Ready.")
      ctx)))
```

### `deps.edn`

```diff
@@ -14,4 +14,5 @@ deps.edn
   :test {:extra-paths ["test"]
          :extra-deps  {io.github.cognitect-labs/test-runner
                        {:git/tag "v0.5.1" :git/sha "dfb30dd"}}}
+  :hitl {:main-opts ["-m" "loom.hitl.cli"]}
  }}
```

### New files

- `src/loom/hitl.clj` — public API (§3) + private DDL/load/flush + FileLock.
- `src/loom/hitl/cli.clj` — CLI entrypoint (§5).
- `test/loom/hitl_test.clj` — request → pending → approve → event log;
  request → reject; `await!` with timeout; double-`approve!` no-op;
  FileLock contention.

---

## 7. Known gaps / follow-ups

1. **Concurrent CLIs.** Unsupported by design (§2). FileLock + idempotent
   `WHERE status='pending'` UPDATE prevent corruption but not duplicate
   user-visible decisions if someone breaks the rule. Print a warning if
   `<lock-path>` exists at CLI startup.
2. **No notifications.** Agents poll via `await!`. Future: file-watch on
   the parquet, or a tiny localhost socket the CLI pings on decide.
3. **Applier coupling.** Strategy A means the CLI must know every `kind`.
   As kinds grow, move to a registry: `hitl/register-applier! [kind fn]`.
4. **Payload size.** Use `blob_ref` for >16 KB; CLI must resolve it on
   `v <n>`.
5. **Auth.** `decided_by` is whatever the CLI claims. Single-user dev tool
   only.
6. **No retention/GC.** Add `hitl/forget-decided! [ctx older-than]` later.
7. **Two-step write atomicity.** `approve!` writes the row then logs an
   event — two `write!` calls, not atomic. Both go through the same
   single-writer chan in order; acceptable.
8. **Breaking change blast radius.** `suggest-promotion!` return shape
   change (§4). Grep external repos before merging; consider a v2
   `suggest-promotion-async!` and deprecating the old name instead.
