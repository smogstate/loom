# loom.goals — Goal Setting & Monitoring

**Status:** DRAFT (v2)
**Author:** analyzer (2026-05-01)
**Changes from v1:** orchestrator Step 0 unwraps envelope; events migration moved into `load-events-table!` projection; `list-goals` whitelists status; `loom.goals` added to seed namespaces.
**Scope:** hierarchical goals, status tracking, event linkage, orchestrator gating.

---

## 1. Storage — `goals.parquet`

Global table at `<loom-dir>/goals.parquet`. Follows the `db.clj` table pattern
(DDL + `load-goals-table!` + `flush-goals!`, all mutations through `write!`).

```sql
CREATE TABLE IF NOT EXISTS goals (
  id               VARCHAR PRIMARY KEY,
  session_id       VARCHAR,           -- session that opened the goal
  parent_id        VARCHAR,           -- nullable, FK to goals.id (hierarchy)
  title            VARCHAR,
  description      VARCHAR,
  success_criteria VARCHAR,           -- plain text, human-checkable
  status           VARCHAR DEFAULT 'open',  -- open|active|blocked|done|abandoned
  vector           VARCHAR,           -- embedding of (title + description) for search
  created_at       TIMESTAMP DEFAULT now(),
  updated_at       TIMESTAMP DEFAULT now()
)
```

To link events → goals without altering `events` schema, add **one** column to
`events-ddl`:

```sql
goal_id VARCHAR     -- nullable; set by link-event! or log-event! when active goal exists
```

Backwards-compatible (nullable, defaults NULL). Existing parquet reads still
work because DuckDB tolerates missing columns when re-creating the in-memory
table from the DDL before INSERT.

---

## 2. Public API — `loom/goals.clj`

All fns return envelopes (`with-provenance`). Mirrors `loom.session` style:
embed in this ns, persist in `loom.db`.

```clojure
(ns loom.goals
  (:require [loom.db :as db]
            [loom.embedder :as embedder]
            [loom.envelope :refer [with-provenance unwrap!]]))

(defn create-goal!
  "Create a goal. opts: :title :description :success-criteria :parent-id :status."
  [ctx {:keys [title description success-criteria parent-id status] :as opts}]
  (with-provenance "loom.goals/create-goal!" 1
    (let [text (str title "\n" description)
          vec  (unwrap! (embedder/embed ctx text))]
      (unwrap! (db/save-goal! ctx
                 {:title            title
                  :description      description
                  :success-criteria success-criteria
                  :parent-id        parent-id
                  :status           (or status "open")
                  :session-id       (:session-id ctx)
                  :vector           vec})))))

(defn update-status!
  "Transition a goal. Valid: open|active|blocked|done|abandoned."
  [ctx goal-id status]
  (with-provenance "loom.goals/update-status!" 1
    (unwrap! (db/update-goal-status! ctx goal-id status))))

(defn link-event!
  "Attach an existing event to a goal (writes events.goal_id)."
  [ctx event-id goal-id]
  (with-provenance "loom.goals/link-event!" 1
    (unwrap! (db/link-event-to-goal! ctx event-id goal-id))))

(defn open-goals
  "List goals with status in #{open active blocked} for current session
   (or all sessions when :scope :global)."
  [ctx & {:keys [scope] :or {scope :session}}]
  (with-provenance "loom.goals/open-goals" 1
    (unwrap! (db/list-goals ctx {:scope     scope
                                 :session-id (:session-id ctx)
                                 :statuses  ["open" "active" "blocked"]}))))

(defn active
  "The single active goal for this session, or nil. Convenience for orchestrator."
  [ctx]
  (with-provenance "loom.goals/active" 1
    (->> (unwrap! (open-goals ctx))
         (filter #(= "active" (:status %)))
         first)))

(defn progress
  "Return a summary {:goal g :children [...] :events-count n :latest-event e}
   for a goal id. Walks parent_id tree one level for children."
  [ctx goal-id]
  (with-provenance "loom.goals/progress" 1
    (let [g        (unwrap! (db/get-goal ctx goal-id))
          children (unwrap! (db/list-goal-children ctx goal-id))
          events   (unwrap! (db/list-goal-events ctx goal-id))]
      {:goal          g
       :children      children
       :events-count  (count events)
       :latest-event  (last events)})))
```

---

## 3. Orchestrator integration — `.opencode/agents/loom.md`

Insert a new **Step 0** before the existing Step 1, and amend Step 3.

```markdown
## Step 0 — require active goal

```bash
python3 ~/Projects/loom/loom_eval.py '(let [g (unwrap! (loom.goals/active ctx))] (if g (:title g) "NONE"))'
```

`loom.goals/active` returns an envelope; the `let`+`unwrap!` is mandatory — a
truthy check on the raw envelope map would always pass. If output is `NONE`:

1. Restate the user request as `title` + `success-criteria`.
2. Create and activate it (same envelope unwrap discipline):
   ```bash
   python3 ~/Projects/loom/loom_eval.py '(let [g (unwrap! (loom.goals/create-goal! ctx {:title "T" :description "D" :success-criteria "SC"}))] (unwrap! (loom.goals/update-status! ctx (:id g) "active")) (:id g))'
   ```
3. Confirm one-line "Goal: <title>" to the user, then proceed.

Refuse to dispatch to subagents if Step 0 fails.
```

Amend **Step 3** so the conclusion event is linked:

```bash
python3 ~/Projects/loom/loom_eval.py '
(let [gid (:id (unwrap! (loom.goals/active ctx)))
      eid (unwrap! (db/log-event! ctx {:type "conclusion" :content "SUMMARY"
                                       :session-id (:session-id ctx) :agent-id "loom"}))]
  (unwrap! (loom.goals/link-event! ctx eid gid)))'
```

Subagents (analyzer, finder, reviewer) are **not** modified — their `:finding`
/`:conclusion` events stay free-form. The orchestrator is the gate.

---

## 4. Integration diff skeleton

### `src/loom/db.clj` — append after the events section (~line 573)

```clojure
;; ---------------------------------------------------------------------------
;; Goals — global
;; ---------------------------------------------------------------------------

(def ^:private goals-ddl
  "CREATE TABLE IF NOT EXISTS goals (
     id               VARCHAR PRIMARY KEY,
     session_id       VARCHAR,
     parent_id        VARCHAR,
     title            VARCHAR,
     description      VARCHAR,
     success_criteria VARCHAR,
     status           VARCHAR DEFAULT 'open',
     vector           VARCHAR,
     created_at       TIMESTAMP DEFAULT now(),
     updated_at       TIMESTAMP DEFAULT now()
   )")

(defn- load-goals-table! [conn path]
  (exec! conn "DROP TABLE IF EXISTS goals")
  (exec! conn goals-ddl)
  (when (.exists (io/file path))
    (exec! conn (str "INSERT INTO goals SELECT id, session_id, parent_id, title, description, success_criteria, status, vector, created_at, updated_at FROM read_parquet('" path "')"))))

(defn- flush-goals! [conn path]
  (ensure-dir! (.getParent (io/file path)))
  (exec! conn (str "COPY goals TO '" path "' (FORMAT PARQUET)")))

(defn save-goal! [ctx goal]
  (with-provenance "loom.db/save-goal!" 1
    (write!
      (fn []
        (let [conn  (:conn ctx)
              path  (parquet-path (get-in ctx [:config :loom-dir]) :goals)
              id    (or (:id goal) (uuid))
              vec-s (float-vec->sql (:vector goal))]
          (load-goals-table! conn path)
          (exec! conn
            "INSERT INTO goals (id, session_id, parent_id, title, description,
                                success_criteria, status, vector)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            id (:session-id goal) (:parent-id goal) (:title goal) (:description goal)
            (:success-criteria goal) (or (:status goal) "open") vec-s)
          (flush-goals! conn path)
          id)))))

(defn update-goal-status! [ctx goal-id status]
  (with-provenance "loom.db/update-goal-status!" 1
    (when-not (valid-statuses status)
      (throw (ex-info "Invalid goal status" {:status status :allowed valid-statuses})))
    (write!
      (fn []
        (let [conn (:conn ctx)
              path (parquet-path (get-in ctx [:config :loom-dir]) :goals)]
          (load-goals-table! conn path)
          (exec! conn "UPDATE goals SET status = ?, updated_at = now() WHERE id = ?"
                 status goal-id)
          (flush-goals! conn path)
          goal-id)))))

(defn get-goal [ctx goal-id]
  (with-provenance "loom.db/get-goal" 1
    (let [conn (:conn ctx)
          path (parquet-path (get-in ctx [:config :loom-dir]) :goals)]
      (when (.exists (io/file path))
        (first (query conn (str "SELECT * FROM read_parquet('" path "') WHERE id = ?")
                      goal-id))))))

(def ^:private valid-statuses
  #{"open" "active" "blocked" "done" "abandoned"})

(defn list-goals
  "opts: {:scope :session|:global :session-id sid :statuses [\"open\" ...]}"
  [ctx {:keys [scope session-id statuses]}]
  (with-provenance "loom.db/list-goals" 1
    (let [bad (remove valid-statuses statuses)]
      (when (seq bad)
        (throw (ex-info "Invalid goal status" {:invalid bad :allowed valid-statuses}))))
    (let [conn   (:conn ctx)
          path   (parquet-path (get-in ctx [:config :loom-dir]) :goals)
          status-list (str "(" (str/join "," (map #(str "'" % "'") statuses)) ")")
          where  (cond-> (str "status IN " status-list)
                   (= scope :session) (str " AND session_id = ?"))
          params (if (= scope :session) [session-id] [])]
      (if (.exists (io/file path))
        (apply query conn (str "SELECT * FROM read_parquet('" path "') WHERE " where
                               " ORDER BY created_at DESC") params)
        []))))

(defn list-goal-children [ctx parent-id]
  (with-provenance "loom.db/list-goal-children" 1
    (let [conn (:conn ctx)
          path (parquet-path (get-in ctx [:config :loom-dir]) :goals)]
      (if (.exists (io/file path))
        (query conn (str "SELECT * FROM read_parquet('" path "') WHERE parent_id = ?")
               parent-id)
        []))))

(defn list-goal-events [ctx goal-id]
  (with-provenance "loom.db/list-goal-events" 1
    (let [conn (:conn ctx)
          path (parquet-path (get-in ctx [:config :loom-dir]) :events)]
      (if (.exists (io/file path))
        (query conn (str "SELECT * FROM read_parquet('" path "') WHERE goal_id = ?
                          ORDER BY ts ASC") goal-id)
        []))))

(defn link-event-to-goal! [ctx event-id goal-id]
  (with-provenance "loom.db/link-event-to-goal!" 1
    (write!
      (fn []
        (let [conn (:conn ctx)
              path (parquet-path (get-in ctx [:config :loom-dir]) :events)]
          (load-events-table! conn path)
          (exec! conn "UPDATE events SET goal_id = ? WHERE id = ?" goal-id event-id)
          (flush-events! conn path)
          event-id)))))
```

Also amend `events-ddl` (line ~514) to add `goal_id VARCHAR` column, and
extend `log-event!` to accept optional `:goal-id` and pass it in the INSERT
(extra param + column in the SQL list).

**Backwards-compat migration** — old `events.parquet` files have 8 columns;
the new DDL has 9. Update `load-events-table!` to project `NULL AS goal_id`
explicitly so existing files load cleanly. The next `flush-events!` rewrites
the parquet with the new column. No standalone migration step needed.

```clojure
(defn- load-events-table! [conn path]
  (exec! conn "DROP TABLE IF EXISTS events")
  (exec! conn events-ddl)
  (when (.exists (io/file path))
    (exec! conn
      (str "INSERT INTO events
              SELECT id, session_id, agent_id, type, vector, content, provenance, ts,
                     NULL AS goal_id
              FROM read_parquet('" path "')"))))
```

### `src/loom/core.clj` — add `loom.goals` to seed namespaces

Required so the ns is loaded at boot and reachable from the orchestrator's
`loom_eval.py` calls without an explicit `require`.

```clojure
;; line ~42
(def ^:private seed-namespaces
  '[loom.seed.http loom.seed.fs loom.seed.text
    loom.seed.data loom.seed.math loom.seed.db
    loom.seed.project loom.seed.eval
    loom.goals])                          ; <-- added
```

`loom.goals` itself defines no `^{:tags …}`-annotated tool fns yet, so
`tools/scan-ns!` will be a no-op for it — the entry exists purely to force
the ns load. (Alternative: `(require 'loom.goals)` in `dev/user.clj`; either
works, but `seed-namespaces` keeps prod and dev consistent.)

`goals.parquet` is still created lazily on first `save-goal!`; no extra
bootstrap step needed.

---

## 5. Known gaps

1. **No status state-machine enforcement** — `update-status!` accepts any
   string from `valid-statuses`; no transition rules (e.g. `done` → `active`
   is allowed). Add a `valid-transitions` map if needed.
2. **Hierarchy depth is unbounded** — `progress` walks one level only; deep
   trees need a recursive CTE (DuckDB supports `WITH RECURSIVE`).
3. **No cross-session "global active goal"** — `active` is session-scoped by
   design. Multi-agent shared goals need a separate table or a `pinned` flag.
4. **Subagents don't auto-link** events to goals — only the orchestrator's
   conclusion is linked. If we want full traceability, `db/log-event!` should
   read `(:goal-id ctx)` from a session-state atom set by Step 0.
5. **Embedding goals is optional** — kept for `db/search-goals` (not yet in
   scope). Adds one Ollama call per `create-goal!`; cheap but worth noting.
6. **No deletion** — goals are mutated only via status (`abandoned`). Hard
   delete would mirror `retire-fact!` if needed.
