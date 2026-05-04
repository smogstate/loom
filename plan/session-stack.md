# Plan — Session Stack (replace `:session-id` with `:session-ids`)

**Status:** v4 — revised after reviewer rejection of v3.

**v4 changes:**
- §7a: added concrete `db-get-entity` body rewrite (delegates to
  `load-graph-scope!` + windowed SQL, mirroring `db-get-entities-by-ids`).
- §7b (new): documents the second storage pattern — single-parquet-with-
  `session_id`-column reads (`db/list-goals`). Migration rule, semantics,
  acceptance criterion, blast-radius rows for `db.clj:931` (`list-goals`)
  and `goals.clj:99` (`open-goals`).
- §7 (Other callers): corrected the inaccurate "graph reads" description
  for `goals.clj`; line 99 explicitly redirected to §7b.
- §4: thread-confinement claim substantiated (DuckDB JDBC connection-
  level serialisation; lock protects cache-atom + DDL ordering).
- §11: added migration-to-connection-pool note flagging the lock-target
  change that would be required.

**v3 changes (kept):**
- §4: `locking` guard around `load-graph-scope!` DROP+INSERT.
- §5: added `session.clj:23` to the `:scope` → `:session-ids` migration table.
- §7: expanded `graph.clj` blast-radius rows with explicit per-fn line refs.
- §7a (new in v3): positional-`session-id` migration for `db-get-entity`,
  `db-get-entities-by-ids`.
- §9: criterion 7 fixed — references real `auto-promote!`.
**Decision:** no backward compat. Global (`GLOBAL_SID`) is always the **last**
element of the read stack (lowest priority, always-present fallback).

---

## 1. Goal

Make scope explicit and composable. Every read takes an **ordered vector of
session ids**:

```
:session-ids [sid_top, sid_2, …, GLOBAL_SID]
```

- First element wins on dedup.
- Last element is always `GLOBAL_SID` unless the caller opts into strict mode.
- The reserved nil-UUID `00000000-0000-0000-0000-000000000000` (`GLOBAL_SID`)
  addresses the global parquets.

```clojure
(def GLOBAL_SID "00000000-0000-0000-0000-000000000000")
```

No backwards compatibility with the singular `:session-id` keyword.

---

## 2. Agent semantics (the "always check global last" rule)

The server / Clojure layer auto-appends `GLOBAL_SID` to the end of any stack
that doesn't already contain it. Effective defaults:

| Caller | Stack passed | Effective stack | Meaning |
|---|---|---|---|
| Agent in session S, no scope arg | _omitted_ | `[S, GLOBAL_SID]` | Session overrides; global fallback |
| Agent explicit global-only | `[GLOBAL_SID]` | `[GLOBAL_SID]` | Baseline only |
| Operator inspecting session A | `[A]` + `:strict? true` | `[A]` | True isolation, debugging |
| Cross-session compare | `[A, B]` | `[A, B, GLOBAL_SID]` | A wins over B wins over global |
| Explicit duplicate | `[A, GLOBAL_SID, GLOBAL_SID]` | `[A, GLOBAL_SID]` | Dedup preserves first occurrence |

### Why session-overrides-global

1. **Recency.** A session captures current context; "call me Bob" must beat an
   older promoted "user is Alice".
2. **Containment.** Session mistakes stay local; correct session updates
   aren't masked by stale global facts.
3. **Promotion is explicit.** A session fact only reaches global via
   `memory/promote!`. Priority should match scope until then.
4. **Symmetry.** Same as `let`-shadowing, dict-merge, classpath order.

### Why "always include global" as fallback

1. **No accidental amnesia.** Tools, kinds, identities live in global. An
   agent can never silently lose them.
2. **Opt-out, not opt-in.** Strict isolation is a debugging affordance, not
   the agent default.

### Write path (unchanged)

Writes still target exactly one session: `(:session-id ctx)`. The stack only
affects reads. Internal merge-on-write reads (e.g. `db-upsert-entity!`
loading existing aliases) read from the **write-target parquet only** — not
the stack — so the stack change does not affect write-side merge semantics.
This is documented explicitly to avoid implementer confusion.

---

## 3. Stack normalisation rule (single source of truth)

One helper handles all stack input. Implemented once in `loom.scope` (new ns):

```clojure
(ns loom.scope)

(def GLOBAL_SID "00000000-0000-0000-0000-000000000000")

(defn normalize-stack
  "Canonicalise a session-ids vector.
   - drops nil/empty entries
   - dedups while preserving first occurrence
   - appends GLOBAL_SID at the end unless strict? is true
   - returns a non-empty vector or throws"
  [session-ids & {:keys [strict?] :or {strict? false}}]
  (let [seen (volatile! #{})
        kept (reduce (fn [acc sid]
                       (if (or (nil? sid) (= "" sid) (contains? @seen sid))
                         acc
                         (do (vswap! seen conj sid) (conj acc sid))))
                     [] (or session-ids []))
        with-global (if (or strict? (some #{GLOBAL_SID} kept))
                      kept
                      (conj kept GLOBAL_SID))]
    (when (empty? with-global)
      (throw (ex-info "empty session stack" {:input session-ids})))
    with-global))

(defn default-stack
  "Standard read scope for an agent."
  [ctx]
  (normalize-stack [(:session-id ctx)]))
```

Every read fn calls `normalize-stack` before passing to `load-graph-scope!`.
This collapses concerns about dedup, idempotency, and the strict-mode rule
(reviewer concern 6) into one place.

---

## 4. `load-graph-scope!` — N-stack version

The DROP+INSERT sequence is **not atomic** on its own. Under N-stack the
likelihood of two callers requesting different stacks rises (the v2 plan
acknowledged this in §11 but did not mitigate it). We add a `locking`
guard around the load sequence keyed on the connection, so concurrent
readers serialise around the singleton in-memory graph tables.

```clojure
(defn- load-graph-scope!
  "Load each scope in priority order. Element index = scope_rank.
   GLOBAL_SID resolves to global parquets; any other sid to session parquets.
   Caller must pass an already-normalised vector (use loom.scope/normalize-stack).

   Concurrency: the in-memory `graph_entities` / `graph_relations` tables are
   a singleton per connection. We `locking` on the conn so two callers with
   different stacks cannot interleave DROP+INSERT and observe a torn state."
  [conn ctx session-ids]
  (let [paths (mapv (fn [sid]
                      (if (= sid GLOBAL_SID)
                        {:e (entities-path ctx) :r (relations-path ctx)}
                        {:e (session-entities-path ctx sid)
                         :r (session-relations-path ctx sid)}))
                    session-ids)
        ;; cache key includes full ordered stack + each parquet's mtime
        cache-key (mapv (fn [sid p] [sid (parquet-mtime (:e p)) (parquet-mtime (:r p))])
                        session-ids paths)]
    (locking conn
      (let [last-key (get @graph-scope-mtimes [::stack])]
        (when (not= cache-key last-key)
          (exec! conn "DROP TABLE IF EXISTS graph_entities")
          (exec! conn graph-entities-ddl)
          (exec! conn "DROP TABLE IF EXISTS graph_relations")
          (exec! conn graph-relations-ddl)
          (doseq [[rank {:keys [e r]}] (map vector (range) paths)]
            (when (and e (.exists (io/file e)))
              (exec! conn (str "INSERT INTO graph_entities
                                SELECT id, canonical_name, kind, aliases, attrs, vector,
                                       confidence, source_count, source_sessions,
                                       created_at, updated_at, retired,
                                       " rank " AS scope_rank
                                FROM read_parquet('" e "')")))
            (when (and r (.exists (io/file r)))
              (exec! conn (str "INSERT INTO graph_relations
                                SELECT …,
                                       " rank " AS scope_rank
                                FROM read_parquet('" r "')"))))
          (swap! graph-scope-mtimes assoc [::stack] cache-key))))))
```

**Key changes vs v2:**
- `locking conn` wraps the cache-check + DROP/INSERT/swap! sequence so the
  in-memory tables and the cache atom move together as one critical
  section. The connection is held in `(:conn ctx)` and is shared across
  any threads sharing that ctx (the FastAPI server holds one ctx; nREPL
  sessions hold one ctx). DuckDB's JDBC driver is documented as
  thread-safe at the connection level (statements serialise internally),
  but the **singleton in-memory `graph_entities` / `graph_relations`
  tables** are not — two threads racing different stacks would observe
  torn DROP+INSERT state without this lock. `locking conn` is therefore
  necessary even granting DuckDB's own internal serialisation; it
  protects the cache-atom + DDL ordering, not the connection itself. If
  Loom later moves to a connection pool, the lock object must change
  (e.g. lock on `graph-scope-mtimes` instead of `conn`); this is flagged
  in §11.
- Cache key is keyed on the full normalised stack, not a single sid (fixes
  v1 reviewer concern 4).
- Single global atom slot `[::stack]` — only one stack is loaded into the
  in-memory graph tables at a time (it's a singleton). If two callers want
  different stacks, the loader reloads behind the lock. This is the
  existing model, just generalised and now made race-free.

---

## 5. Eliminating the hardcoded `scope_rank = 0/1` filter

**Reviewer concern 2 + 3.** `db-list-entities` (db.clj:1431-1434) currently
filters by `:scope :session` → `(= 0 scope_rank)` and `:scope :global` →
`(= 1 scope_rank)`. With N-stack these constants are wrong.

**Fix:** deprecate the `:scope` opt entirely. Replace with explicit stacks at
all call sites:

| Old | New |
|---|---|
| `{:scope :session :session-id sid}` | `:session-ids [sid] :strict? true` |
| `{:scope :global}` | `:session-ids [GLOBAL_SID] :strict? true` |
| `{:scope :both …}` | `:session-ids [sid GLOBAL_SID]` (or just default) |

`db-list-entities` then has no `scope_rank` filter at all — it returns the
deduped union of whatever was loaded. Callers that want only-this-rank
filtering can post-filter in Clojure (rare; only the promotion path needs it).

Affected call sites — must be updated:

- `graph.clj:163-164` (`auto-promote!`) — replace `{:scope :session :session-id sid}` with `:session-ids [sid] :strict? true`.
- `graph.clj:181-197` (`session-export`) — same.
- `session.clj:23` (`session-context`) — same.

Note: every `{:scope :session …}` opt anywhere in the tree must be
audited; the three sites above are the confirmed ones at the time of
writing. The audit gate (§7, criterion 6) catches any miss.

---

## 6. `db-subgraph` double-load

**Reviewer concern 5.** `db-subgraph` (db.clj:1748) calls `load-graph-scope!`
then calls `db-bfs` which calls it again. Today it's safe by accident
(same sid → mtime cache hit). Under N-stack we keep it safe by:

1. Both calls receive the **same normalised vector**. `db-subgraph` does the
   normalisation once at entry; passes the already-normalised vector down.
2. `load-graph-scope!` becomes a no-op when its cache key matches the
   currently-loaded stack (already true).

**Convention:** internal db-* fns receive `:session-ids` as **already-
normalised**. Only public `graph.clj` fns call `normalize-stack`. This avoids
double-normalisation and makes the internal contract explicit.

---

## 7. Blast radius (full enumeration)

**Reviewer concern 1.** Every caller of `:session-id` to a read fn must be
updated. Final list with line refs:

### Core (must rename signatures)

| File | Lines | Fn | Change |
|---|---|---|---|
| `src/loom/db.clj` | 1165 | `load-graph-scope!` | Take vector |
| `src/loom/db.clj` | 1386, 1429, 1453, 1482, 1512, 1536, 1632, 1669, 1687, 1748 | each `load-graph-scope!` callsite | Pass vector |
| `src/loom/db.clj` | 1354 | `db-get-entity` | **Positional** sid → vector (see §7a) |
| `src/loom/db.clj` | 1377 | `db-get-entities-by-ids` | **Positional** sid → vector (see §7a) |
| `src/loom/db.clj` | 1737 | `db-subgraph` | Map opts; rename `:session-id` → `:session-ids` |
| `src/loom/db.clj` | 1680 | `db-bfs` | Map opts; rename |
| `src/loom/db.clj` | (~9 db-* fns) | `db-neighbor-counts`, `db-search-relations`, `db-search-entities`, `db-search-entities-by-name`, `db-list-entities`, `db-neighbors`, `db-name-prefix-search` | Opt rename `:session-id` → `:session-ids` |
| `src/loom/graph.clj` | 51-56 | `search-entities` | Opt rename + `normalize-stack` |
| `src/loom/graph.clj` | 58-65 | `search-entities-by-name` | Opt rename + `normalize-stack` |
| `src/loom/graph.clj` | 67-74 | `search-relations` | Opt rename + `normalize-stack` |
| `src/loom/graph.clj` | 77-83 | `neighbors` | Opt rename + `normalize-stack` |
| `src/loom/graph.clj` | 86-92 | `bfs` | Opt rename + `normalize-stack` |
| `src/loom/graph.clj` | 95-101 | `subgraph` | Opt rename + `normalize-stack` |
| `src/loom/graph.clj` | 103-107 | `neighbor-counts` | Opt rename + `normalize-stack` |
| `src/loom/graph.clj` | 109-127 | `resolve-entity!` | Internal `db-search-entities` call at L116 must pass `:session-ids` |
| `src/loom/graph.clj` | 135-157 | `promote-entity!` | Internal `db-get-entity` (L142, L151, positional sid) and `db-search-relations` (L147) callsites |
| `src/loom/graph.clj` | 159-173 | `auto-promote!` | Internal `db-list-entities` at L164 (`:scope :session …`) → `:session-ids [sid] :strict? true` |
| `src/loom/graph.clj` | 175-211 | `extract-and-link!` | Internal `db-search-entities` calls at L192, L197, L206 must pass `:session-ids` (write-path `{:scope …}` opts unchanged) |

### Other callers requiring updates

| File | Lines | What it does | Status |
|---|---|---|---|
| `src/loom/guard.clj` | 227, 263 | Passes `:session-id (:session-id ctx)` to graph reads | confirmed |
| `src/loom/session.clj` | 23, 31 | Passes `:session-id` / `{:scope :session :session-id …}` to db reads | confirmed |
| `src/loom/seed/db.clj` | 38 | Constructs opts `:session-id` | confirmed |
| `src/loom/budget.clj` | 125, 165, 183 | Budget queries with `:session-id` | confirmed |
| `src/loom/goals.clj` | 23, 74, 81 | Passes `:session-id (:session-id ctx)` to `graph/bfs` (L74), `graph/promote-entity!` (L81), and event log writes (L23) — read-path entries migrate via §7; line 99 (`open-goals` → `db/list-goals`) is **not** a graph read and migrates via §7b | confirmed |
| `src/loom/blob.clj` | 130 | Passes `:session-id` to event-search reads | confirmed |
| `src/loom/tools.clj` | (audit) | `source_sessions` writes — write path unaffected; any read-path opts must use `:session-ids` | verify |
| `src/loom/memory.clj` | (audit) | `memory/promote!` writes — write path unaffected; reads must migrate | verify |
| `src/loom/scratch.clj` | (audit) | Scratch reads/writes | verify |
| `src/loom/metrics.clj` | (audit) | Metrics reads | verify |

**Audit gate:** before merging,
`grep -rn ':session-id ' src/loom/` must show zero hits passing into a read
fn. Only `(:session-id ctx)` accessor usage and write-path opts
(`{:scope … :session-id …}`) may remain.

### Server / UI

| File | Change |
|---|---|
| `ui/server.py` | All endpoints accept `?session_ids=sid1,sid2`. New `?strict=true`. Drop `"global"` magic string. New `_clj_vec` helper. |
| `ui/server.py` | New `/api/sessions` returns `{global_sid, sessions:[…]}`. |
| `ui/react-app/src/api.js` | Every fn takes `sessionIds: string[]`. |
| `ui/react-app/src/App.jsx` | State `sessionStack: string[]`; threaded through every API call. |
| `ui/react-app/src/components/SessionPicker.jsx` (new) | Dropdown + "include global as fallback" checkbox. |

---

## 7a. Positional `session-id` arguments

Three internal db fns currently take `session-id` as a **positional** arg
(not a `{:session-id …}` map opt). The N-stack migration must address
these explicitly.

| Fn | File:line | Current signature | New signature |
|---|---|---|---|
| `db-get-entity` | `db.clj:1354` | `[ctx entity-id session-id]` | `[ctx entity-id session-ids]` (vector) |
| `db-get-entities-by-ids` | `db.clj:1377` | `[ctx ids session-id]` | `[ctx ids session-ids]` (vector) |

**Decision: keep them positional, change the type to `session-ids`
vector.** Rationale: these are internal db-* fns called from a small
number of sites; switching to map opts here would be churn for no
ergonomic gain. Public `graph.clj` fns remain map-opts (`:session-ids`).

**Contract** (matches §6): callers pass an **already-normalised** vector.
Public `graph.clj` wrappers normalise once at entry and forward.

**`db-get-entity` body rewrite.** The current body (db.clj:1359-1375)
hard-codes a session-then-global lookup against two parquet paths. With
a vector input it must change. **Spec:** delegate to `load-graph-scope!`
+ a single SQL query, mirroring the existing `db-get-entities-by-ids`
pattern (db.clj:1387-…). Sketch:

```clojure
(defn db-get-entity
  "Get an entity by id with stack-priority semantics.
   Lower scope_rank wins; first hit returned."
  [ctx entity-id session-ids]
  (with-provenance "loom.db/db-get-entity" 1
    (let [conn (:conn ctx)]
      (load-graph-scope! conn ctx session-ids)
      (some-> (first (query conn
                       "WITH ranked AS (
                          SELECT *, row_number() OVER
                                 (PARTITION BY id ORDER BY scope_rank ASC, updated_at DESC) AS rn
                          FROM graph_entities
                          WHERE retired = false AND id = ?
                        )
                        SELECT id, canonical_name, kind, aliases, attrs, vector,
                               confidence, source_count, source_sessions,
                               created_at, updated_at, retired
                        FROM ranked WHERE rn = 1"
                       entity-id))
              entity-row->domain))))
```

This eliminates the bespoke two-path lookup, reuses the cached
`load-graph-scope!` machinery, and keeps semantics identical for the
common case `[sid GLOBAL_SID]`. Implementer note: the existing
`db-get-entity` body must be **deleted**, not patched, to avoid
double-load.

**Callsites to migrate** (from §7 plus internal):

- `graph.clj:142` — `(db/db-get-entity ctx entity-id sid)` → `(db/db-get-entity ctx entity-id (normalize-stack [sid] :strict? true))`
- `graph.clj:151` — same pattern (inside `promote-entity!` rel walk).
- `db.clj:1381,1755` — internal callsites of `db-get-entities-by-ids`; pass through the already-normalised vector from the calling db-* fn.
- `db.clj:1748` — `db-subgraph` already takes opts; ensure it normalises
  once at entry and forwards the vector to `db-bfs` and
  `db-get-entities-by-ids` per §6.

**Rejected alternative:** convert to map opts. Rejected because (a) it
balloons the diff in `db.clj`, (b) it requires touching every callsite
twice (signature + opt key), (c) the positional form is fine for
internal fns and the audit gate (`grep ':session-id '`) doesn't apply
to positional args anyway.

---

## 7b. Single-parquet-with-`session_id`-column reads

Entities and relations live in **per-session parquet files** under
`sessions/<sid>/`, plus a global parquet. `load-graph-scope!` UNIONs
them with a `scope_rank` discriminator. The N-stack model fits cleanly.

A second storage pattern exists: **goals** (and any future kind that
follows it) live in a **single global parquet** with a `session_id`
**column**. There are no per-session goal parquets. `db/list-goals`
filters with `WHERE session_id = ?` when `:scope :session`.

**Confirmed callsite:** `db.clj:931` `list-goals` — body at L941-943
builds `WHERE status IN (...) AND session_id = ?` from
`{:scope :session :session-id sid}` opts.

**Confirmed caller:** `goals.clj:99` (`open-goals`) → `db/list-goals`
with `{:scope :session :session-id (:session-id ctx)}`.

**Audit:** the only file in `src/loom/` with the `session_id = ?` /
`session_id IN` pattern is `db.clj:942` (`list-goals`). No other
single-parquet read uses this discriminator at present. If new ones are
added, they must follow the same rule below.

### Migration rule

Replace the `:scope` + scalar `:session-id` opts with `:session-ids`
(vector) and rewrite the WHERE clause to use SQL `IN` over the stack:

```clojure
;; old
[ctx {:keys [scope session-id statuses]}]
…
where  (cond-> (str "status IN " status-list)
         (= scope :session) (str " AND session_id = ?"))
params (if (= scope :session) [session-id] [])

;; new
[ctx {:keys [session-ids strict? statuses]}]
…
(let [stack       (loom.scope/normalize-stack session-ids :strict? strict?)
      sid-list    (str "(" (str/join "," (repeat (count stack) "?")) ")")
      where       (str "status IN " status-list
                       " AND session_id IN " sid-list)
      params      (vec stack)]
  …)
```

**Semantics:** the goal store has no per-session priority concept (a
goal exists in exactly one session). The stack acts as a **filter**, not
a priority ordering: rows whose `session_id` is anywhere in the stack
match. This is consistent with the entities/relations behaviour for the
common case `[sid GLOBAL_SID]` (you see your-session goals plus global
goals) but does NOT need `scope_rank`.

**Strict mode:** identical to entities — `:strict? true` skips the
auto-append of `GLOBAL_SID`, so callers can ask "only this session's
goals" by passing `:session-ids [sid] :strict? true`.

**Acceptance criterion (added to §9):** `(db/list-goals ctx
{:session-ids [sid] :strict? true :statuses ["open"]})` returns only
goals whose `session_id = sid`. With strict false, also includes
`session_id = GLOBAL_SID`.

### Blast radius update

| File | Lines | Fn | Change |
|---|---|---|---|
| `src/loom/db.clj` | 931-947 | `list-goals` | Drop `:scope`; take `:session-ids` + `:strict?`; rewrite WHERE to `session_id IN (…)` |
| `src/loom/goals.clj` | 99 | `open-goals` | Replace `{:scope :session :session-id (:session-id ctx)}` with `{:session-ids [(:session-id ctx)]}` (default-stack semantics) |

---

## 8. Edge cases (resolved)

| Case | Behavior |
|---|---|
| Empty stack `[]` | `normalize-stack` throws `ex-info` |
| `[GLOBAL_SID, GLOBAL_SID]` | Dedup → `[GLOBAL_SID]` |
| `[A, GLOBAL_SID]` + `strict?=true` | Kept as-is, no extra append |
| `[A, B]` no strict | → `[A, B, GLOBAL_SID]` |
| Sid → nonexistent parquet | Loader skips silently (matches today) |
| Same entity in two sessions | Lower-rank wins; aliases unioned only on `db-merge-entities!` |
| `(:session-id ctx)` is `nil` | `default-stack` returns `[GLOBAL_SID]` |
| Two callers want different stacks | Loader reloads (mtime cache miss); single-stack invariant preserved |

---

## 9. Acceptance criteria

1. `(graph/neighbor-counts ctx eid :session-ids [GLOBAL_SID] :strict? true)` returns counts over global parquets only.
2. `(graph/neighbor-counts ctx eid :session-ids [sid] :strict? true)` returns counts over session `sid` only — no global mixing.
3. `(graph/neighbor-counts ctx eid :session-ids [sid])` returns `[sid GLOBAL_SID]` union, sid winning on ties.
4. `(graph/neighbor-counts ctx eid)` (no opt) returns `[(:session-id ctx), GLOBAL_SID]` union.
5. UI session picker switches the visible graph between scopes without page reload; inspector counts match the picked stack.
6. `grep -rn ':session-id ' src/loom/` shows zero hits passing into read fns.
7. `(graph/auto-promote! ctx :session-id sid)` still promotes session entities to global correctly (write path API unchanged externally; internal `db-list-entities` call migrated to `:session-ids [sid] :strict? true`).
8. `(load-graph-scope! conn ctx [sid GLOBAL_SID])` then `(load-graph-scope! conn ctx [sid GLOBAL_SID])` is a no-op on the second call (cache hit).
9. New unit test: `normalize-stack` covers all rows in §8.
10. `(db/list-goals ctx {:session-ids [sid] :strict? true :statuses ["open"]})` returns only goals with `session_id = sid`; with `:strict? false` also returns goals with `session_id = GLOBAL_SID`. (See §7b.)
11. `(db/db-get-entity ctx eid [sid GLOBAL_SID])` returns the session-`sid` row when both session and global rows exist (priority); returns the global row when only global exists; returns nil when neither exists. (See §7a body sketch.)

---

## 10. Implementation order (suggested PRs)

1. **PR 1 — foundation.** New `loom.scope` ns with `GLOBAL_SID` +
   `normalize-stack` + `default-stack`. Unit tests. No behavioural change.
2. **PR 2 — db core.** `load-graph-scope!` takes vector; internal db-* fns
   renamed; `:scope :session/:global` filters in `db-list-entities` removed
   (replaced by explicit stacks at the two `graph.clj` call sites).
3. **PR 3 — public graph API.** `graph.clj` fns renamed + `normalize-stack`
   at entry. All in-tree callers (`guard`, `session`, `seed/db`, `budget`,
   etc.) migrated. Audit gate enforced.
4. **PR 4 — server.** Endpoints accept `?session_ids=`; `/api/sessions`;
   `_clj_vec` helper.
5. **PR 5 — UI.** `SessionPicker`, `sessionStack` state, threaded through all
   API calls.

Each PR independently mergeable and testable.

---

## 11. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Silent destructuring of removed `:session-id` key | Audit gate (criterion 6) + per-fn renamed → `:session-ids`; old key absent at compile time as map-arg destructuring |
| Two parallel readers want different stacks (load thrash) | `locking conn` in `load-graph-scope!` (§4) serialises the DROP+INSERT so reloads can't tear; existing single-stack singleton preserved; profile if thrash bites |
| Future move to a connection pool would invalidate `locking conn` (different conn objects per checkout) | If/when Loom adopts a pool, switch the lock target to `graph-scope-mtimes` (the cache atom) which remains process-singleton; flagged here so the migration isn't silently broken |
| Forgotten caller in non-`loom.*` code (user code, REPL scripts) | Document the breaking change in CHANGELOG; provide one-line migration in `loom.scope` docstring |
| UI ships before backend | Sequence PRs 4 → 5 strictly; UI uses `/api/sessions` to discover sentinel |

---

## 12. Out of scope (follow-ups)

- Multi-select reorderable stack UI (drag-to-reorder).
- Per-write session selector.
- Concurrent multi-stack readers (would require per-call temp tables, not a
  shared singleton).
