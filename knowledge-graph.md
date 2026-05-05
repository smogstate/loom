# Loom Knowledge Graph Plan

**Status:** SUPERSEDED by `plans/architecture.md` (2026-05-04)
**Author:** OpenCode
**Date:** 2026-05-03
**Goal ID:** `601b99ed-30e4-4621-8c9b-8e4de0a91f9f`

## 1) Objective

Replace flat facts memory with a first-class Knowledge Graph (KG) while preserving operational subsystems.

- Graph becomes the memory system of record.
- `goals.parquet` and `tools.parquet` remain operational tables (status machine and executable code), and are mirrored into graph entities.
- Session data remains in `sessions/<session-id>/` using standard table filenames.

## 2) Scope and Non-Scope

### In scope

- New global graph tables: `entities.parquet`, `relations.parquet`.
- New session graph tables: `sessions/<sid>/entities.parquet`, `sessions/<sid>/relations.parquet`.
- Migration from global `facts.parquet` and session `facts.parquet` (`session_facts`) into graph.
- Entity extraction, linking, resolution, promotion, traversal.
- Bridges from existing operational writes (`tools/register!`, `goals/create-goal!`, `blob/index!`) into global KG.

### Out of scope

- Replacing goals/tools operational logic with graph-only logic.
- Real-time extraction on hot write paths.
- Full ontology governance beyond initial predicate set.

## 3) Data Model

### 3.1 Entity kinds

Allowed `kind` values:

1. `concept`
2. `tool`
3. `goal`
4. `agent`
5. `file`
6. `external`

Facts/chunks/events are **not** entities. They are provenance sources for edges and confidence.

### 3.2 Predicate ontology

Exact predicate set (15):

- `IS_A`
- `PART_OF`
- `HAS_PART`
- `USES`
- `DEPENDS_ON`
- `IMPLEMENTS`
- `DEFINED_IN`
- `MENTIONED_IN`
- `CAUSES`
- `BLOCKS`
- `RELATES_TO`
- `CONTRADICTS`
- `SUPERSEDES`
- `AUTHORED_BY`
- `ABOUT`

### 3.3 Global entities schema

```sql
CREATE TABLE IF NOT EXISTS entities (
  id               VARCHAR PRIMARY KEY,
  canonical_name   VARCHAR,
  kind             VARCHAR,
  aliases          VARCHAR,              -- JSON array string
  attrs            VARCHAR,              -- JSON object string
  vector           VARCHAR,              -- DuckDB FLOAT[768] literal string
  confidence       DOUBLE,
  source_count     INTEGER DEFAULT 1,
  source_sessions  VARCHAR,              -- JSON array string of distinct session IDs
  created_at       TIMESTAMP DEFAULT now(),
  updated_at       TIMESTAMP DEFAULT now(),
  retired          BOOLEAN DEFAULT false
)
```

### 3.4 Global relations schema

```sql
CREATE TABLE IF NOT EXISTS relations (
  id               VARCHAR PRIMARY KEY,
  subject_id       VARCHAR,
  predicate        VARCHAR,
  object_id        VARCHAR,
  confidence       DOUBLE,
  source_id        VARCHAR,              -- id in facts/chunks/events/tools/goals/blob
  source_table     VARCHAR,              -- e.g. facts, session_facts, chunks, events
  source_sessions  VARCHAR,              -- JSON array string
  created_at       TIMESTAMP DEFAULT now(),
  updated_at       TIMESTAMP DEFAULT now(),
  retired          BOOLEAN DEFAULT false
)
```

### 3.5 Session schemas

Session tables use the same columns as global tables and the same filenames:

- `sessions/<sid>/entities.parquet`
- `sessions/<sid>/relations.parquet`

No special naming variant is introduced.

## 4) Storage and Mutation Pattern (Loom conventions)

All graph mutations follow current `loom.db` write discipline:

- `with-provenance` on every public function.
- Per operation: load parquet -> mutate in-memory table -> flush parquet.
- Mutations serialized by `write!` queue.
- No standalone public `load-graph!` / `persist-graph!` APIs.
- Vector comparisons cast from VARCHAR to arrays with `::FLOAT[768]`.
- JSON arrays/objects persisted as VARCHAR.

## 5) API Surface

## 5.1 New namespace: `src/loom/graph.clj`

All public fns must be envelope-wrapped.

- `extract-and-link!`
- `upsert-entity!`
- `upsert-relation!`
- `resolve-entity!`
- `merge-entities!`
- `promote-entity!`
- `auto-promote!`
- `search-entities`
- `search-relations`
- `neighbors`
- `bfs`

Each function returns an envelope and uses `unwrap!` on downstream calls.

## 5.2 New DB functions in `src/loom/db.clj`

All new public DB fns must also use `with-provenance`.

- Entity write/read/list/search for global + session tiers.
- Relation write/read/list/search for global + session tiers.
- Merge and rewrite helpers executed inside a single `write!` thunk.
- Traversal helpers (`db-neighbors`, `db-bfs`) over loaded in-memory tables.

### Required read semantic fix

`db-get-entity` signature includes `session-id` and resolves session-first:

1. Try `sessions/<sid>/entities.parquet` (if present).
2. Fallback to global `entities.parquet`.

## 6) Entity Resolution and Merge Rules

### 6.1 Resolution gate

Merge candidates only when all are true:

- cosine similarity >= `0.92`
- Jaro-Winkler similarity >= `0.70`
- identical `kind`

Use `org.apache.commons.text.similarity.JaroWinklerSimilarity`.

### 6.2 Merge atomicity requirement

`merge-entities!` must execute atomically in one `write!` thunk:

1. Guard: if `from-id == to-id`, return no-op.
2. Delete/retire `from-id`.
3. Rewrite all relation rows where `subject_id = from-id` to `to-id`.
4. Rewrite all relation rows where `object_id = from-id` to `to-id`.
5. Merge aliases/attrs/source metadata.
6. Increment `source_count` and union distinct `source_sessions`.
7. Flush mutated tables.

No multi-thunk or partially committed merge path.

## 7) Extraction and Promotion

### 7.1 Extraction policy

- Lazy/batch extraction, not synchronous on hot path.
- LLM structured extraction capped at <= 8 triples per call.
- Source provenance attached to each relation (`source_id`, `source_table`).

### 7.2 Promotion tiers

- Session graph is default write target for conversational extraction.
- Global graph is durable memory tier.
- Search order: session first, then global fallback.

### 7.3 Auto-promotion criteria

Entity is auto-promoted when:

- `source_count >= 2`, and
- `confidence >= 0.85`, and
- at least 2 distinct session IDs in `source_sessions`.

### 7.4 Explicit and cascade promotion

- Explicit promotion via API remains available.
- Cascade promotion on goal transition to `done` traverses `RELATES_TO` for reachability but only promotes relation/entity paths involving `IMPLEMENTS`, `USES`, `DEPENDS_ON`.
- `RELATES_TO` edges are traversal-only in cascade, not directly promoted by predicate type alone.

## 8) Operational Bridges (direct to global)

Operational subsystems emit graph entities/relations directly to global tier with `confidence = 1.0`:

- `tools/register!` -> tool entity + relation edges (e.g. `IMPLEMENTS`, `AUTHORED_BY`).
- `goals/create-goal!` -> goal entity + relation edges (e.g. `ABOUT`, `DEPENDS_ON`).
- `blob/index!` -> file/external entity + `MENTIONED_IN`/`DEFINED_IN` edges.

These bridges do not route through session graph.

## 9) Migration Plan (facts -> graph)

Migration is required for both tiers:

1. Global: read `.loom/facts.parquet` and convert rows to entities/relations.
2. Session: for each `sessions/<sid>/facts.parquet` (table shape `session_facts`), convert to session entities/relations.
3. Preserve provenance mapping:
   - global row -> `source_table = facts`
   - session row -> `source_table = session_facts`
4. Seed `source_sessions` JSON arrays.
5. Set initial `source_count` based on distinct sources merged.

Facts storage is retired as active memory once migration completes.

## 10) Traversal (`db-bfs`) SQL

Before traversal, both entity and relation parquet data must be loaded into in-memory DuckDB tables for the selected scope(s).

- Load session tables first when `session-id` is provided.
- Load global tables second.
- Query over the combined in-memory view.

Concrete recursive CTE:

```sql
WITH RECURSIVE walk(node_id, depth, path) AS (
  SELECT
    ?::VARCHAR AS node_id,
    0::INTEGER AS depth,
    [ ?::VARCHAR ]::VARCHAR[] AS path

  UNION ALL

  SELECT
    r.object_id AS node_id,
    w.depth + 1 AS depth,
    array_concat(w.path, [r.object_id]::VARCHAR[]) AS path
  FROM walk w
  JOIN relations r
    ON r.subject_id = w.node_id
  WHERE w.depth < ?
    AND r.retired = false
    AND list_position(w.path, r.object_id) IS NULL
)
SELECT node_id, MIN(depth) AS min_depth
FROM walk
GROUP BY node_id
ORDER BY min_depth ASC, node_id ASC
LIMIT ?
```

For undirected traversal, union an inverse edge projection in a CTE before `walk`.

## 11) Implementation Work Breakdown

### G1-G13 tasks

- **G1** Create `entities`/`relations` DDL + load/flush helpers in `db.clj`.
- **G2** Add session path helpers for graph tables using standard filenames.
- **G3** Add DB CRUD/search fns with envelope and per-op load/mutate/flush.
- **G4** Implement `graph.clj` public API (all envelope-wrapped).
- **G5** Implement extraction pipeline (batch, <=8 triples/call).
- **G6** Implement resolver thresholds and Jaro-Winkler comparator.
- **G7** Implement atomic `merge-entities!` single-thunk rewrite path.
- **G8** Implement promotion APIs (`promote-entity!`, `auto-promote!`).
- **G9** Implement traversal (`neighbors`, `db-bfs`) with recursive CTE and in-memory table loads.
- **G10** Add operational bridges from tools/goals/blob to global graph.
- **G11** Implement facts + session_facts migration to graph.
- **G12** Wire goal-done cascade promotion policy.
- **G13** Add tests for DB + graph + migration + traversal.

Dependencies:

- G3 depends on G1,G2
- G4 depends on G3
- G6 depends on G3
- G7 depends on G3,G6
- G8 depends on G3,G6
- G9 depends on G1,G3
- G10 depends on G3
- G11 depends on G1,G2,G3
- G12 depends on G8,G10
- **G13 depends on G3,G7,G8,G9,G11,G12**

## 12) Testing Strategy

Required tests:

- Schema load/flush parity for global and session graph tables.
- Session-first lookup for `db-get-entity`.
- Resolver thresholds and kind guard.
- Merge atomicity + self-merge no-op.
- `source_sessions` union and `source_count` increment semantics.
- BFS traversal correctness and cycle avoidance.
- Migration coverage for both `facts.parquet` and `sessions/<sid>/facts.parquet`.
- Bridge writes to global tier with confidence `1.0`.

## 13) Acceptance Criteria

Plan is accepted when all are true:

1. Graph fully replaces facts as memory-of-record.
2. All public graph and new DB functions are envelope-wrapped.
3. Session-first semantics verified for entity reads.
4. Migration includes global + session facts data.
5. No standalone graph load/persist public APIs; per-op write pattern only.
6. Merge is single-thunk atomic and self-merge safe.
7. `source_sessions` and `source_count` power auto-promotion across sessions.
8. BFS has concrete recursive SQL and runs on loaded in-memory tables.
9. G13 test task explicitly depends on G9.

## 14) File-level Change Plan

- `src/loom/db.clj`
  - Add DDL, load/flush, CRUD/search, merge helpers, traversal helpers.
  - Keep queue-driven mutation model (`write!`) intact.
- `src/loom/graph.clj` (new)
  - Graph orchestration and envelope-wrapped public API.
- `src/loom/memory.clj`
  - Redirect promotion semantics to graph entities where applicable.
- `src/loom/goals.clj`
  - Hook goal done -> cascade promotion trigger.
- `src/loom/tools.clj`
  - Bridge tool registration events into global graph.
- `src/loom/blob.clj`
  - Bridge indexed artifacts into global graph.

## 15) Rollout

1. Ship DB schema + graph namespace + tests behind feature flag.
2. Run one-time migration for global + session facts.
3. Enable bridges for tools/goals/blob.
4. Switch memory reads to graph-first/session-first fallback.
5. Mark legacy facts paths as deprecated and then remove from active flows.
