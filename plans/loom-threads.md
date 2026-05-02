# loom.threads — Thread-ID Scoping for Events

**Status:** APPROVED (v2)
**Author:** analyzer (2026-05-02)
**Changes from v1:** mid-migration data-loss bug fixed via `parquet_schema()` probe; agent file before/after diffs made concrete; injection-vector gap recorded.
**Scope:** technical correlation key for event isolation across parallel agent dispatches.

---

## 0. Why threads ≠ goals

| Concern    | `goal_id`                                | `thread_id`                                  |
|------------|------------------------------------------|----------------------------------------------|
| Layer      | Business — "which user objective?"       | Technical — "which dispatch slot?"           |
| Lifetime   | Days/weeks (until `done`/`abandoned`)    | Minutes (one orchestrator turn / repair loop)|
| Source     | `loom.goals/create-goal!` → UUID         | Orchestrator-minted string, hierarchical     |
| Cardinality| One active per session (typically)       | Many concurrent per session                  |
| Examples   | `"a4f1-..."`                             | `"plan/loom.budget"`, `"repair/eval-expr/2"` |

Both columns are **independent and nullable** on `events`. A single event row may
carry either, both, or neither. The orchestrator decides what to attach.

---

## 1. Storage — extend `events.parquet`

Add **one** nullable column to `events-ddl` (db.clj L514). Assumes `goal_id`
from `loom-goals` lands first (or in same patch); the migration projects both
NULLs in one shot.

```sql
CREATE TABLE IF NOT EXISTS events (
  id         VARCHAR PRIMARY KEY,
  session_id VARCHAR,
  agent_id   VARCHAR,
  type       VARCHAR,
  vector     VARCHAR,
  content    VARCHAR,
  provenance VARCHAR,
  ts         TIMESTAMP DEFAULT now(),
  goal_id    VARCHAR,        -- from loom-goals
  thread_id  VARCHAR         -- NEW
)
```

### Migration — schema-aware projection in `load-events-table!`

DuckDB refuses `INSERT … SELECT *` on column-count mismatch, **and** a
blanket `NULL AS goal_id, NULL AS thread_id` projection would zero out a
`goal_id` column that already exists in a mid-migration parquet file. We
must probe the on-disk schema first and only inject `NULL AS <col>` for
columns that are missing.

```clojure
(defn- parquet-columns
  "Return the set of column names present in a parquet file (lower-cased)."
  [conn path]
  (->> (query conn (str "SELECT name FROM parquet_schema('" path "')"))
       (map (comp str/lower-case :name))
       set))

(def ^:private events-cols
  ;; Order matters — must match events-ddl declaration order.
  ["id" "session_id" "agent_id" "type" "vector" "content" "provenance" "ts"
   "goal_id" "thread_id"])

(defn- events-projection
  "Build the SELECT list, replacing missing columns with NULL AS <col>."
  [present]
  (->> events-cols
       (map (fn [c] (if (contains? present c) c (str "NULL AS " c))))
       (str/join ", ")))

(defn- load-events-table! [conn path]
  (exec! conn "DROP TABLE IF EXISTS events")
  (exec! conn events-ddl)
  (when (.exists (io/file path))
    (let [present (parquet-columns conn path)
          select  (events-projection present)]
      (exec! conn
        (str "INSERT INTO events SELECT " select
             " FROM read_parquet('" path "')")))))
```

Handles all three on-disk shapes idempotently:

| Existing parquet shape          | Projection injected                              |
|---------------------------------|--------------------------------------------------|
| 8-col (legacy)                  | `..., NULL AS goal_id, NULL AS thread_id`        |
| 9-col (post-`loom-goals`)       | `..., goal_id, NULL AS thread_id`                |
| 10-col (post-`loom-threads`)    | `..., goal_id, thread_id` (no-op)                |

The next `flush-events!` rewrites the parquet at the current full schema.
One extra cheap query per `load-events-table!`. No data loss across either
migration order.

---

## 2. `db/log-event!` — accept `:thread-id`

Add one destructured key, one INSERT param, one column. No callers break.

```clojure
(defn log-event!
  "Append a structural event with provenance.
   Optional :goal-id (business scope), :thread-id (technical scope)."
  [ctx event]
  (with-provenance "loom.db/log-event!" 1
    (write!
      (fn []
        (let [conn  (:conn ctx)
              path  (parquet-path (get-in ctx [:config :loom-dir]) :events)
              id    (or (:id event) (uuid))
              vec-s (float-vec->sql (:vector event))
              prov  (json/encode (or (:provenance event) {}))]
          (load-events-table! conn path)
          (exec! conn
            "INSERT INTO events
               (id, session_id, agent_id, type, vector, content, provenance, goal_id, thread_id)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            id (:session-id event) (:agent-id event) (:type event)
            vec-s (:content event) prov
            (:goal-id event) (:thread-id event))
          (flush-events! conn path)
          id)))))
```

Omitted keys → `nil` → SQL `NULL`. Fully backward compatible.

---

## 3. New query fns — deterministic, no embedding

### `search-events-in-thread`

Exact match on `thread_id`. Optional `type` filter. Ordered by `ts ASC` so
the reader sees the agent's reasoning chronologically.

```clojure
(defn search-events-in-thread
  "Fetch events tagged with exactly thread-id, optionally filtered by type.
   Pure SQL — no vector search. Pass type=nil to skip type filter."
  [ctx thread-id type limit]
  (with-provenance "loom.db/search-events-in-thread" 1
    (let [conn (:conn ctx)
          path (parquet-path (get-in ctx [:config :loom-dir]) :events)
          [where params] (if type
                           ["thread_id = ? AND type = ?" [thread-id type]]
                           ["thread_id = ?"              [thread-id]])]
      (if (.exists (io/file path))
        (->> (apply query conn
               (str "SELECT id, session_id, agent_id, type, content, provenance, ts,
                            goal_id, thread_id
                     FROM read_parquet('" path "')
                     WHERE " where "
                     ORDER BY ts ASC
                     LIMIT " (int limit))
               params)
             (mapv #(update % :provenance parse-json)))
        []))))
```

### `search-events-by-prefix`

Hierarchical thread namespaces (e.g. `plan/loom.budget` is a parent of
`plan/loom.budget/repair/2`). Uses `LIKE 'prefix/%'`.

```clojure
(defn search-events-by-prefix
  "Fetch events whose thread_id starts with prefix + '/'.
   Use for parent-thread roll-ups (e.g. all repairs under plan/X)."
  [ctx prefix type limit]
  (with-provenance "loom.db/search-events-by-prefix" 1
    (let [conn (:conn ctx)
          path (parquet-path (get-in ctx [:config :loom-dir]) :events)
          like (str prefix "/%")
          [where params] (if type
                           ["thread_id LIKE ? AND type = ?" [like type]]
                           ["thread_id LIKE ?"              [like]])]
      (if (.exists (io/file path))
        (->> (apply query conn
               (str "SELECT id, session_id, agent_id, type, content, provenance, ts,
                            goal_id, thread_id
                     FROM read_parquet('" path "')
                     WHERE " where "
                     ORDER BY ts ASC
                     LIMIT " (int limit))
               params)
             (mapv #(update % :provenance parse-json)))
        []))))
```

Existing `search-events` (vector search) is **untouched** — still useful for
cross-thread semantic queries.

---

## 4. Orchestrator — `loom.md`

Mint a thread-id once per dispatched pipeline and pass it in the task
prompt. Threads are cheap; one per `task` call is the rule.

Insert into Step 2 (after classification, before each `task` call):

```bash
# Mint a stable thread-id for this dispatch
THREAD_ID="plan/$(date +%s)-$RANDOM"
# Or, when classification yields a name:
# THREAD_ID="plan/loom.budget"
```

Then prepend to every task description passed to `task`:

```
thread-id: plan/loom.budget
<original user prompt>
```

Subagents extract the line and quote it in their `log-event!` calls. The
orchestrator's own conclusion event also carries it:

```bash
python3 ~/Projects/loom/loom_eval.py "(db/log-event! ctx {:type \"conclusion\" :content \"SUMMARY\" :thread-id \"$THREAD_ID\" :session-id (:session-id ctx) :agent-id \"loom\"})"
```

Repair loops mint child threads: `"$THREAD_ID/repair/$ATTEMPT"`. The prefix
query then reconstructs the whole tree.

---

## 5. Analyzer — `analyzer.md`

Single change: include `:thread-id` in every `log-event!` call. The thread-id
arrives in the task prompt as a `thread-id: <id>` line; analyzer parses it
into `$THREAD_ID` before logging.

**Before** (analyzer.md L32–38):

```bash
python3 ~/Projects/loom/loom_eval.py "(db/log-event! ctx {:type \"finding\" :content \"<your claim here>\" :session-id (:session-id ctx) :agent-id \"analyzer\"})"
```
```bash
python3 ~/Projects/loom/loom_eval.py "(db/log-event! ctx {:type \"conclusion\" :content \"<summary>\" :session-id (:session-id ctx) :agent-id \"analyzer\"})"
```

**After**:

```bash
# At top of agent run — extract thread-id from task prompt
THREAD_ID=$(echo "$TASK_PROMPT" | grep -m1 '^thread-id:' | cut -d' ' -f2)

python3 ~/Projects/loom/loom_eval.py "(db/log-event! ctx {:type \"finding\" :content \"<your claim here>\" :thread-id \"$THREAD_ID\" :session-id (:session-id ctx) :agent-id \"analyzer\"})"
```
```bash
python3 ~/Projects/loom/loom_eval.py "(db/log-event! ctx {:type \"conclusion\" :content \"<summary>\" :thread-id \"$THREAD_ID\" :session-id (:session-id ctx) :agent-id \"analyzer\"})"
```

Self-repair attempts mint child threads so the reviewer can walk the chain:

```bash
ATTEMPT_THREAD="$THREAD_ID/repair/$N"
python3 ~/Projects/loom/loom_eval.py "(db/log-event! ctx {:type \"repair-failed\" :content \"...\" :thread-id \"$ATTEMPT_THREAD\" :session-id (:session-id ctx) :agent-id \"analyzer\"})"
```

---

## 6. Reviewer — `reviewer.md`

Replace the broad semantic search with a thread-scoped lookup.

**Before** (reviewer.md L19):

```clojure
(unwrap! (db/search-events ctx (unwrap! (embedder/embed ctx "conclusion")) 10))
```

**After**:

```clojure
;; Thread-id arrives in the task prompt; bind it once.
(def THREAD_ID "...")  ; parsed from task prompt

;; Conclusions on this exact thread (no cross-thread contamination, no embed cost):
(unwrap! (db/search-events-in-thread ctx THREAD_ID "conclusion" 10))

;; Findings backing those conclusions:
(unwrap! (db/search-events-in-thread ctx THREAD_ID "finding" 50))

;; Child threads (repair attempts, sub-reviews):
(unwrap! (db/search-events-by-prefix ctx THREAD_ID nil 100))
```

The session-fact verdict logging at L22-23 is unchanged — session facts are
not thread-scoped (see §9.6).

---

## 7. Coexistence with `goal_id`

```clojure
(db/log-event! ctx
  {:type       "conclusion"
   :content    "shipped budget tracker"
   :goal-id    "a4f1-..."          ; business: user wants budget tracking
   :thread-id  "plan/loom.budget"  ; technical: this dispatch slot
   :session-id (:session-id ctx)
   :agent-id   "loom"})
```

Query patterns:

- "Show all events for this user goal across time"
  → `WHERE goal_id = ?` (loom.goals/list-goal-events, already planned)
- "Show this dispatch slot's reasoning chain"
  → `search-events-in-thread`
- "Show repairs under this plan"
  → `search-events-by-prefix`

The two columns never need a JOIN — they index the same row from two angles.

---

## 8. Integration diff skeleton

### `src/loom/db.clj`

| Line  | Change                                                                    |
|-------|---------------------------------------------------------------------------|
| ~514  | `events-ddl`: add `, thread_id VARCHAR` after `goal_id VARCHAR`           |
| ~526  | Replace `load-events-table!` with schema-aware version (§1); add private `parquet-columns`, `events-cols`, `events-projection` helpers above it |
| ~536  | `log-event!`: add `(:thread-id event)` param + `, thread_id` column       |
| +new  | `search-events-in-thread` (after `search-events`, ~L573)                  |
| +new  | `search-events-by-prefix` (immediately after the above)                   |

### `.opencode/agents/loom.md`

| Section | Change                                                                  |
|---------|-------------------------------------------------------------------------|
| Step 2  | Mint `THREAD_ID` per dispatch; prepend `thread-id: $THREAD_ID\n` to task|
| Step 3  | Add `:thread-id "$THREAD_ID"` to the conclusion `log-event!`            |

### `.opencode/agents/analyzer.md`

| Section            | Change                                                          |
|--------------------|-----------------------------------------------------------------|
| "Logging findings" | Parse first line for `thread-id:`; add `:thread-id` to template |
| Self-repair        | Mint child threads `$THREAD_ID/repair/$N`                       |

### `.opencode/agents/reviewer.md`

| Line | Change                                                                    |
|------|---------------------------------------------------------------------------|
| 19   | Replace `db/search-events` semantic call with `db/search-events-in-thread`|
| +new | Add `db/search-events-by-prefix` call to fetch child threads (repairs)    |

---

## 9. Known gaps

1. **`read_parquet` path interpolation is an injection vector.** The new
   `search-events-in-thread` and `search-events-by-prefix` interpolate
   `path` into the SQL string via `(str ... path ...)` — same pattern as
   every existing query fn in `db.clj` (`search-events`, `search-facts`,
   `search-chunks`, etc.). User-supplied values (`thread-id`, `prefix`,
   `type`) all flow through `?` parameters and are safe; only the parquet
   path is interpolated, and it is computed from `(get-in ctx [:config
   :loom-dir])` — trusted config, not user input. Inherited risk; out of
   scope for this plan but worth a project-wide fix later (probably a
   single `with-parquet [t path]` macro that registers a temp view).
2. **No thread-id validation.** Free-form strings; typos silently create
   orphan threads. Consider a regex `#"^[a-z][a-z0-9._/-]+$"` in
   `log-event!` if drift becomes a problem.
3. **No retention / GC.** Threads accumulate forever. Eventual `prune-thread!`
   needed once parquet sizes hurt. Defer.
4. **Subagents must parse the task prompt.** No schema for "thread-id is
   the first line." A small `loom.thread/extract` helper would be safer
   than relying on every prompt template. Out of scope here.
5. **Concurrent writes still serialize through `write!` queue** (db.clj
   L41-47). Threads enable *logical* parallelism in the agent layer, but
   parquet flushes remain single-writer. Acceptable for current load.
6. **`session.clj/log-fact!` is unchanged.** Session facts are not
   thread-scoped. If reviewer verdicts ever need thread isolation,
   add `:thread-id` to `session_facts` separately.
