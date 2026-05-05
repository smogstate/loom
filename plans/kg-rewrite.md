# KG Rewrite — Single-DB, Coordinate-First, Reduced Surface

Status: SUPERSEDED by `plans/architecture.md` (2026-05-04)
Supersedes: `knowledge-graph.md` (2026-05-03) §3, §5, §10
Date: 2026-05-04
No backward compatibility — `.loom/` is regenerated on first boot.

## 1. Goals

1. **One DuckDB file** at `.loom/loom.db` as system of record. Parquet only on demand via `loom export`.
2. **`(session_id, id)` is the canonical KG coordinate.** Session is a column on every KG row; `'00000000-0000-0000-0000-000000000000'` is the global sentinel.
3. **Drop `strict?`.** Callers pass an explicit session stack; opting into global means including `GLOBAL_SID` in the vector.
4. **Collapse the public surface** from ~40 KG fns across `graph`/`memory`/`session`/`db` to ~11 in a single `loom.kg` namespace. `loom.db` becomes private.

## 2. New Schema

Single `.loom/loom.db`. All DDL below replaces the parquet-backed equivalents.

```sql
CREATE TABLE entities (
  session_id      VARCHAR     NOT NULL,
  id              VARCHAR     NOT NULL,
  canonical_name  VARCHAR     NOT NULL,
  kind            VARCHAR     NOT NULL,
  aliases         JSON,
  attrs           JSON,
  vector          FLOAT[768],
  confidence      DOUBLE,
  retired         BOOLEAN     DEFAULT false,
  created_at      TIMESTAMP   DEFAULT now(),
  updated_at      TIMESTAMP   DEFAULT now(),
  PRIMARY KEY (session_id, id)
);
CREATE INDEX entities_kind     ON entities(session_id, kind);
CREATE INDEX entities_name     ON entities(canonical_name);

CREATE TABLE relations (
  session_id   VARCHAR    NOT NULL,
  id           VARCHAR    NOT NULL,
  subject_sid  VARCHAR    NOT NULL,
  subject_id   VARCHAR    NOT NULL,
  predicate    VARCHAR    NOT NULL,
  object_sid   VARCHAR    NOT NULL,
  object_id    VARCHAR    NOT NULL,
  confidence   DOUBLE,
  source_id    VARCHAR,
  source_table VARCHAR,
  retired      BOOLEAN    DEFAULT false,
  created_at   TIMESTAMP  DEFAULT now(),
  updated_at   TIMESTAMP  DEFAULT now(),
  PRIMARY KEY (session_id, id),
  FOREIGN KEY (subject_sid, subject_id) REFERENCES entities(session_id, id),
  FOREIGN KEY (object_sid,  object_id)  REFERENCES entities(session_id, id)
);
CREATE INDEX relations_subject ON relations(subject_sid, subject_id);
CREATE INDEX relations_object  ON relations(object_sid, object_id);
CREATE INDEX relations_pred    ON relations(predicate);
```

Other tables migrate to the same DB (`tools`, `goals`, `events`, `chunks`, `blobs`, `hits`) keeping their current columns; `vector VARCHAR` becomes `FLOAT[768]` everywhere.

Two structural changes to the non-KG tables:

- **`events` gains `retired BOOLEAN DEFAULT false`.** Today `events.parquet` has no `retired` column but `db/table-count` filters on it (`db.clj:219`) — latent bug. Adding the column closes it.
- **`usage` is split out to `.loom/usage.db` and `ATTACH`ed.** Per-LLM-request usage rows are high-cardinality timeseries; co-locating with KG bloats `EXPORT DATABASE` and slows VACUUM. Same `write!` queue, same connection model, but `loom.budget` writes go through `loom_usage.save_usage_batch!` against the attached DB.

Dropped:
- `source_sessions` JSON column (now redundant — `session_id` is the row's authoritative session).
- `source_count` (recomputable from `COUNT(DISTINCT session_id)` over alias graph; we can re-add if a real consumer appears).
- `graph_entities` / `graph_relations` synthetic union tables.
- `scope_rank` synthetic column.
- `graph-scope-mtimes` cache atom.
- `facts.parquet` and `session_facts` legacy tables.

### 2.1 Compound write atomicity

Every bridge that touches multiple KG rows must execute inside **one** `write!` thunk so the FK from `relations → entities` holds at every statement boundary. Affected callers:

- `loom.tools/register!` — currently 1 tool row + 3 entity upserts + 2 relations across multiple thunks (`tools.clj:33-91`). Refactor to one thunk.
- `loom.goals/create-goal!` — 1 goal row + 1 entity + 1 resolve + 1 ABOUT relation + optional DEPENDS_ON (`goals.clj:25-63`). One thunk.
- `loom.blob/index!` — entity per file + MENTIONED_IN/DEFINED_IN edges. One thunk per file.
- `loom.scratch` writes — one thunk per scratch tool.
- `loom.kg/upsert-relation!` when called standalone — must verify both endpoint entities exist (read-then-insert in same thunk, or rely on FK error and surface it).

Acceptance: a test asserts that `(register! ctx 'foo/bar)` enqueues exactly one item on `write-ch`.

### 2.2 Predicate ontology

Adds `SAME_AS` to the existing 15 predicates. `SAME_AS` is the only sanctioned cross-session entity link — see §3 merge semantics.

## 3. New API: `loom.kg`

```clojure
(ns loom.kg)

;; -- writes ----------------------------------------------------------------
(upsert-entity!   ctx entity   {:keys [session-id resolve?]})
(upsert-relation! ctx relation {:keys [session-id]})
(retire-entity!   ctx entity-coord)            ; coord = {:session-id … :id …}
(retire-relation! ctx relation-coord)
(merge-entities!  ctx from-coord to-coord)     ; atomic single-thunk
(promote!         ctx coord)                   ; UPDATE session_id = GLOBAL_SID

;; -- reads -----------------------------------------------------------------
(query-entities   ctx {:keys [ids session-ids kind name-prefix vector limit]})
(query-relations  ctx {:keys [subject-coord object-coord predicate
                              session-ids limit]})
(neighbors        ctx coord {:keys [direction predicates session-ids limit]})
(bfs              ctx coord {:keys [max-depth limit undirected? session-ids]})
(subgraph         ctx coord {:keys [max-depth direction session-ids]})
```

11 public fns. All envelope-wrapped. `loom.db` is private.

### Coordinate convention

A coord is `{:session-id sid :id id}` — never bare `id`. Every `:subject-coord`, `:object-coord`, `:from`, `:to` argument follows this.

### Resolve-on-write

`upsert-entity!` accepts `:resolve? true` (default) — internally runs the cosine + Jaro-Winkler check against entities in the same `session-id` and either updates the matching row or inserts new. No separate public `resolve-entity!`.

### Merge semantics

`merge-entities!` is **intra-session only** — both `from-coord` and `to-coord` must share the same `session_id`, else throws. Within a session, merge atomically:

1. If `from == to`, no-op.
2. UPDATE relations SET subject_id = to-id WHERE (subject_sid, subject_id) = from-coord.
3. UPDATE relations SET object_id = to-id WHERE (object_sid, object_id) = from-coord.
4. Merge aliases/attrs onto the `to` row, take `max(confidence)`.
5. Mark `from` retired with `attrs.merged_into = to-id`.

Cross-session "this is the same thing" is expressed as a `SAME_AS` relation, never as a merge. Reads that want to follow `SAME_AS` do so explicitly via `neighbors :predicates ["SAME_AS"]`.

### `query-entities` filter precedence

When multiple filter keys are passed:

1. **`:vector`** — ORDER BY array_distance; all other keys narrow the candidate set via WHERE.
2. **`:name-prefix`** (when `:vector` absent) — ORDER BY canonical_name ASC; other keys narrow.
3. **`:ids`** (when neither above) — batch get; other keys still apply as filters; result order matches input order.
4. None of the above — list ordered by updated_at DESC.

`:kind`, `:session-ids`, `:limit` always apply. `:session-ids` defaults to `[(:session-id ctx)]` (no implicit global tail — see Stack semantics).

### Stack semantics

Reads take `:session-ids [sid …]` — explicit, ordered. First match wins on dedup by `id`. To include global, the caller writes `[sid GLOBAL_SID]`. No `strict?`. Empty vector throws.

Helpers (private):
```clojure
(scope/normalize-stack [sid …])  ; dedup, drop nil/empty, validate non-empty
(scope/with-global stack)        ; convenience: appends GLOBAL_SID if missing
```

## 4. Folded / Deleted

| Today | Replacement |
|---|---|
| `loom.session/log-fact!` | `kg/upsert-entity! :session-id sid` |
| `loom.session/search-facts` | `kg/query-entities :session-ids [sid]` |
| `loom.session/get-promotion-mode`, `set-promotion-mode!` | stay in `loom.session` (orthogonal concern) |
| `loom.memory/promote!` | `kg/upsert-entity! :session-id GLOBAL_SID` |
| `loom.memory/search` | `kg/query-entities :session-ids [GLOBAL_SID] :kind :concept` |
| `loom.memory/forget!` | `kg/retire-entity!` |
| `loom.memory/suggest-promotion!` | **delete** — interactive prompt belongs in agent code |
| `loom.graph/resolve-entity!` | internal to `upsert-entity!` |
| `loom.graph/promote-entity!`, `promote-relation!` | `kg/promote!` (one fn) |
| `loom.graph/list-promotion-candidates`, `auto-promote!` | `kg/query-entities` + `(map promote!)` — keep `auto-promote!` as a 4-line convenience in `loom.session` |
| `loom.graph/extract-and-link!` | move to `loom.session` (it's a session-write helper, not a KG primitive) |
| `loom.db/db-*` mirrors (17 fns) | private internals of `loom.kg` |
| `loom.db/migrate-facts-to-graph!` | **delete** — no migration; regenerate |
| `loom.db/save-fact!`, `search-facts`, `retire-fact!`, `save-session-fact!`, `search-session-facts` | **delete** |
| `loom.guard/search-denials` raw SQL on `read_parquet(events.parquet)` (`guard.clj:330-344`) | port to a private `loom.kg/query-events` (filter map: `:types :agent-id :tool :since :limit`) — no public escape hatch |
| `loom.guard/log-redaction!`, `log-denial!` (`guard.clj:225, 261`) | unchanged signature; internally call `loom.kg/log-event!` |
| `loom.scratch` `scratch_tools` table | **stays as a dedicated table** in `loom.db` — scratch tools are per-session, hit-counted, and never first-class KG nodes. Only on promotion via `scratch/promote!` does the scratch tool become a global `tools` row + KG `tool` entity. |

Files to delete: `loom.memory` becomes empty (delete ns), `loom.session` shrinks to promotion-mode + `log-fact!`/`search-facts`/`auto-promote!` thin wrappers.

## 5. Cutover (single PR, no compat layer)

Single PR, but ordered so the codebase compiles at every commit. The trick: introduce the new world *alongside* the old, then rip the old out.

1. **Introduce new schema + new `loom.kg` alongside old code.** Add the new DDL to a fresh connection helper (file-backed `.loom/loom.db`); old parquet helpers in `loom.db` stay untouched. Implement the 11 `loom.kg` fns against the new schema with resolve-on-write baked in. Add `test/loom/kg_test.clj`. At end of step both old (`loom.graph` over parquet) and new (`loom.kg` over loom.db) coexist; nothing yet calls `loom.kg`.
2. **Migrate writers to `loom.kg`.** Port `loom.tools/register!`, `loom.goals/create-goal!`, `loom.blob/index!`, `loom.scratch/*` to call `loom.kg/*` only. Each compound write goes inside **one** `write!` thunk (per §2.1). At end of step writers no longer reference `loom.graph` or `loom.memory`.
3. **Migrate readers to `loom.kg`.** Replace remaining call sites of `loom.graph/*`, `loom.memory/*`, `loom.session/search-facts`. Port `loom.guard/search-denials` to the new private `loom.kg/query-events`.
4. **Drop `strict?`.** Remove from every signature, every test. `scope/normalize-stack` loses the kwarg. Delete `scope_test.clj` strict cases.
5. **Delete dead code.** `loom.graph` ns, `loom.memory` ns, `loom.db/migrate-facts-to-graph!`, all `db-*` mirrors, all parquet-load/flush helpers, `graph_entities`/`graph_relations` DDL, `graph-scope-mtimes` atom, legacy `facts`/`session_facts` helpers. After this step `wc -l src/loom/db.clj` drops by ~900 lines.
6. **Move `usage` to attached DB.** Open `.loom/usage.db`, `ATTACH` it, repoint `loom.budget` writes/reads through the attached schema.
7. **Add `loom.export`.** New ns: `(export! ctx out-dir)` does `EXPORT DATABASE <out-dir> (FORMAT PARQUET)`. Also `(export-session! ctx sid out-dir)` for filtered single-session export via `COPY (SELECT … WHERE session_id = ?) TO`.
8. **Regenerate.** First boot deletes any pre-existing `.loom/` and recreates. Bootstrap re-runs seed tools and re-indexes the project from disk.

## 6. Tests to keep / rewrite

Keep:
- BFS correctness + cycle avoidance (port to new schema).
- Merge atomicity + self-merge no-op.
- Resolver thresholds (now exercised through `upsert-entity! :resolve? true`).
- Bridge writes (tools/goals/blob) hit global tier.

Delete:
- All `strict?` test cases (`scope_test.clj` lines 15–19, 42–44, `graph_test.clj` lines 88, 124, 133).
- Parquet load/flush parity tests.
- Migration tests (no migration).

Add:
- FK enforcement: relation insert with non-existent subject/object fails.
- Cross-session relation: subject in session A, object in global, query returns it from session A's stack.
- `query-entities` filter-map dispatch (`:vector` vs `:name-prefix` vs `:ids` vs combinations).

## 7. Resolved decisions and remaining questions

### Resolved (closed during review)

- **`hits` table** → becomes `(session_id, tool_name, count)` PRIMARY KEY in the single DB. No downstream consumer cared.
- **`loom.budget` placement** → split into attached `.loom/usage.db` (see §2). Same write queue, separate file for export hygiene.
- **Cross-session merge** → not allowed. `SAME_AS` predicate handles it (see §3 Merge semantics, §2.2).
- **Events table `retired`** → added to DDL (see §2).

### Remaining questions

1. **DuckDB `FLOAT[N]` parameter binding.** Need a 1-line REPL check before coding: does `(.setObject ps i (float-array vec))` bind cleanly to a `FLOAT[768]` parameter, or do we keep building the array literal as a string? If string-build is required, `vector` query parameter stays a literal; column type still wins on storage and read.
2. **`loom.scratch` promotion path.** Scratch tools live in their own `scratch_tools` table. On promotion via `scratch/promote!`, do we also create a KG `tool` entity at promotion time, or only when the now-promoted tool is registered via `loom.tools/register!`? Recommend: promote→register pipeline already exists, so the KG entity is created once at register-time, no double-write.

## 8. Acceptance

Verifiable via the listed commands.

1. `.loom/loom.db` and `.loom/usage.db` are the only state files (plus `scratch/*.clj` and `exports/`).
2. Every KG row has `(session_id, id)` and the FK from `relations → entities` holds — verified by a test that attempts to insert a relation with a non-existent endpoint and asserts the FK violation.
3. `grep -rn 'strict?' src/ test/` returns zero matches.
4. `grep -rn 'loom\.db/' src/` returns matches only inside `src/loom/kg.clj` (and the `loom.db` ns itself). No other namespace touches `loom.db` directly.
5. `loom.kg` exposes **exactly 11** public fns. `(count (filter #(not (:private (meta %))) (vals (ns-publics 'loom.kg))))` returns 11.
6. `loom.db` has zero public fns: `(empty? (filter #(not (:private (meta %))) (vals (ns-publics 'loom.db))))` is true.
7. `wc -l src/loom/db.clj` ≤ 1000 (currently 1857).
8. `(register! ctx 'foo/bar)` enqueues exactly one item on the `write!` channel — verified by a test that wraps `write!` and counts.
9. `loom.graph` and `loom.memory` namespaces no longer exist; `loom.session` ≤ 50 lines.
10. All tests green; added tests for FK enforcement, cross-session relation reads, `query-entities` filter precedence, and single-thunk compound writes.
