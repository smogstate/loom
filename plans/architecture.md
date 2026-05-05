# Loom Architecture v2

Status: DRAFT — supersedes `plans/kg-rewrite.md` and `knowledge-graph.md` in full.
Date: 2026-05-04
No backward compatibility. `.loom/` is regenerated.

## 1. Premise

Loom is a **project-scoped knowledge graph + tool registry + RAG index + audit log + budget meter**, exposed over nREPL.

The KG is **authored** (manually or via future ingestion), **never inferred from agent chatter**. Agents are readers, not writers. Cross-session "agent learning" is removed: it never paid off and the storage shape was wrong for it.

One Loom instance = one project. One `.loom/loom.db` = one global model. No session partitioning anywhere in the data layer.

## 2. Final Tables

Single `.loom/loom.db` (DuckDB file). One attached `.loom/usage.db` for telemetry.

> **DuckDB quirks discovered during implementation (cutover step 1-2).** Three pragmatic
> deviations from the original DDL, all forced by ART-index limitations in DuckDB 1.1.x:
> 1. **No secondary indexes** on any table. ART indexes can't be UPDATEd; a secondary
>    index on a mutable column produces phantom "duplicate key" errors on subsequent
>    writes. Filter scans rely on DuckDB's columnar zone-maps instead.
> 2. **No FK constraints** on `relations.subject_id`/`object_id` or `chunks.blob_id`.
>    DuckDB implements FK as an implicit ART index on the referencing column; this blocks
>    `merge-entities!`'s `UPDATE relations SET subject_id = ?`. Endpoint validation moved
>    to the API layer (`loom.kg/upsert-relation!` SELECTs both endpoints, throws if
>    missing).
> 3. **Entity upsert via `DELETE` + `INSERT`** (preserving `id`), not `ON CONFLICT DO
>    UPDATE`. DuckDB rejects `UPDATE` on `FLOAT[768]` array columns
>    (`Not implemented Error: Array Update is not supported`). The two-step is atomic
>    inside one `db/write!` thunk. Relations have no array columns and use
>    `INSERT ... ON CONFLICT DO UPDATE` cleanly.

```sql
-- Knowledge graph (project model)
CREATE TABLE entities (
  id              VARCHAR PRIMARY KEY,
  kind            VARCHAR NOT NULL,           -- concept | tool | file | function | module |
                                              --  business_entity | external | …
  canonical_name  VARCHAR NOT NULL,
  aliases         JSON,
  attrs           JSON,
  vector          FLOAT[768],
  retired         BOOLEAN DEFAULT false,
  created_at      TIMESTAMP DEFAULT now(),
  updated_at      TIMESTAMP DEFAULT now()
);
-- No secondary indexes (DuckDB ART can't be updated; see note above).

CREATE TABLE relations (
  id          VARCHAR PRIMARY KEY,
  subject_id  VARCHAR NOT NULL,                -- FK enforced in loom.kg/upsert-relation!
  predicate   VARCHAR NOT NULL,
  object_id   VARCHAR NOT NULL,                -- FK enforced in loom.kg/upsert-relation!
  attrs       JSON,
  retired     BOOLEAN DEFAULT false,
  created_at  TIMESTAMP DEFAULT now(),
  updated_at  TIMESTAMP DEFAULT now()
);

-- Tools (canonical registry)
CREATE TABLE tools (
  id          VARCHAR PRIMARY KEY,
  name        VARCHAR NOT NULL,
  doc         VARCHAR,
  tags        JSON,
  vector      FLOAT[768],
  code        VARCHAR,
  version     INTEGER DEFAULT 1,
  supersedes  VARCHAR,
  retired     BOOLEAN DEFAULT false,
  created_at  TIMESTAMP DEFAULT now()
);

-- Scratch tools (in-flight drafts; promoted to `tools` on graduation)
-- No session_id: `loom.scratch/load-all!` re-registers from .clj files on each boot;
-- the table is just the latest persisted view. UNIQUE on name; latest write wins.
CREATE TABLE scratch_tools (
  id          VARCHAR PRIMARY KEY,
  name        VARCHAR NOT NULL UNIQUE,
  doc         VARCHAR,
  tags        JSON,
  vector      FLOAT[768],
  code        VARCHAR,
  file        VARCHAR,
  retired     BOOLEAN DEFAULT false,
  created_at  TIMESTAMP DEFAULT now()
);

-- Tool popularity. Counts accumulate over project history (no per-session reset).
-- Reviewer pushed back on session_id here — sessions don't exist as a first-class concept,
-- so per-session counts have no defined source. Whole-project counts are simpler and at
-- least as useful for scratch promotion thresholds.
CREATE TABLE hits (
  tool_name   VARCHAR PRIMARY KEY,
  count       INTEGER DEFAULT 0
);

-- RAG
CREATE TABLE blobs (
  id          VARCHAR PRIMARY KEY,
  path        VARCHAR,
  source      VARCHAR,
  size_bytes  BIGINT,
  ts          TIMESTAMP DEFAULT now()
);

CREATE TABLE chunks (
  id            VARCHAR PRIMARY KEY,
  blob_id       VARCHAR NOT NULL,              -- FK enforced in loom.blob (see DuckDB note)
  chunk_offset  INTEGER,
  vector        FLOAT[768],
  summary       VARCHAR,
  content       VARCHAR
);

-- Audit (governance / tracing — append-only, narrow).
-- session_id IS retained here as plain metadata for grouping a conversation's
-- trace. It is NOT a partition key, NOT load-bearing for any read path, and
-- never appears on KG / tools / scratch tables.
CREATE TABLE audit (
  id          VARCHAR PRIMARY KEY,
  ts          TIMESTAMP DEFAULT now(),
  session_id  VARCHAR,
  agent_id    VARCHAR,
  type        VARCHAR NOT NULL,                -- guard.denial | guard.redaction |
                                               --  system.warning |
                                               --  agent.start | agent.stop | agent.failure
  content     VARCHAR,
  provenance  JSON
);
-- No secondary indexes (DuckDB ART can't be updated; see note at top of §2).

-- Audit policy: narrow. Three categories only:
--   guard.*   policy enforcement (denials, redactions)
--   system.*  operational warnings (e.g. blob ingest hiccups)
--   agent.*   multi-agent run tracing (start, stop, failure) — NOT findings/conclusions
-- Subagent outputs (findings, plans, verdicts) are returned via tool responses, not persisted here.

-- Attached: .loom/usage.db
CREATE TABLE usage.usage (
  id           VARCHAR PRIMARY KEY,
  ts           TIMESTAMP DEFAULT now(),
  session_id   VARCHAR,
  agent_id     VARCHAR,
  op           VARCHAR,
  version      INTEGER,
  duration_ms  BIGINT,
  ok           BOOLEAN,
  usd_cost     DOUBLE,
  tokens_in    INTEGER,
  tokens_out   INTEGER
);
```

8 tables in main DB + 1 attached. Down from 17 (many of which were duplicated per session).

### Predicate ontology

Same 15 predicates from the prior design (`IS_A PART_OF HAS_PART USES DEPENDS_ON IMPLEMENTS DEFINED_IN MENTIONED_IN CAUSES BLOCKS RELATES_TO CONTRADICTS SUPERSEDES AUTHORED_BY ABOUT`). No `SAME_AS` (no cross-session merge case). Validation lives in `loom.kg`, not `loom.db`.

### Entity kinds

`concept | tool | file | function | module | business_entity | external`. Open to extend; validated at API boundary.

### Deterministic IDs (convention, not schema)

When ingestion exists, entity IDs derive from a stable locator:
- `function:<ns>/<name>` → sha256
- `file:<absolute-path>` → sha256
- `concept:<canonical-name>` → sha256
- Manually-authored: UUID

Re-ingestion is idempotent without a fuzzy-match resolver.

## 3. Final Namespaces

```
src/loom/
  core.clj        start!, make-ctx, bootstrap!
  db.clj          PRIVATE: connection + schema init + low-level helpers
  envelope.clj    with-provenance, unwrap!
  embedder.clj    embed (Ollama)
  state.clj       in-process atom (tools mirror)
  kg.clj          NEW: graph CRUD + queries  (replaces graph.clj)
  tools.clj       register!, scan-ns!, rollback!
  blob.clj        ingest!, search-chunks      (chunk + embed only; no business ingestion yet)
  audit.clj       NEW: log!, query             (replaces parts of db/events + guard/search-denials)
  budget.clj      call, report, current-usage  (uses attached usage.db)
  guard.clj       wrap-tool, init!, reload-policy!, call (writes via audit)
  scratch.clj     load-all!, save!, search, inc-hit!, promote!
  repl.clj        nREPL :7888
  seed/           built-in tool libs (http, fs, text, data, math, db, project, eval)
```

12 namespaces. Down from 18.

## 4. Final Public API

```
loom.kg          upsert-entity!     ctx entity
                 upsert-relation!   ctx relation
                 retire-entity!     ctx id
                 retire-relation!   ctx id
                 merge-entities!    ctx from-id to-id
                 query-entities     ctx {:keys [ids kind name-prefix vector limit]}
                 query-relations    ctx {:keys [subject-id object-id predicate limit]}
                 neighbors          ctx id {:keys [direction predicates limit]}
                 bfs                ctx id {:keys [max-depth limit undirected?]}
                 subgraph           ctx id {:keys [max-depth direction]}
                                                                     (10 fns)

loom.tools       register!, scan-ns!, rollback!                       (3)
loom.blob        ingest!, search-chunks                                (2)
loom.audit       log!, query                                           (2)
loom.budget      call, report, current-usage, *agent-id*               (3 fns + 1 var)
loom.guard       init!, reload-policy!, call, search-denials           (4)
loom.scratch     load-all!, save!, search, inc-hit!, promote!          (5)
loom.embedder    embed                                                 (1)
loom.envelope    with-provenance, unwrap!                              (1 macro + 1 fn)
loom.repl        start!, stop!                                         (2)
loom.core        start!, make-ctx                                      (2)
```

≈35 public fns total. Down from ~90.

`loom.db` is **private** (`^:private` on every def, OR move to `loom.db.internal` and have only `loom.kg`/`loom.audit`/`loom.scratch`/`loom.tools`/`loom.blob`/`loom.budget` import it).

### `query-entities` filter precedence

When multiple keys are passed:
1. `:vector` → ORDER BY array_distance; other keys narrow.
2. `:name-prefix` (no `:vector`) → ORDER BY canonical_name ASC; other keys narrow.
3. `:ids` (neither above) → batch get; result order matches input.
4. None → list ordered by updated_at DESC.
`:kind`, `:limit` always apply.

### Compound write atomicity

Every bridge that touches multiple rows runs in **one** `write!` thunk so FKs hold:
- `loom.tools/register!` — tool row + KG entity for the tool (kind="tool")
- `loom.blob/ingest!` — blob row + N chunk rows + (optional, future) file/function entities
- `loom.scratch/promote!` — scratch_tools row update + tools row + KG tool entity (this is a compound writer; explicitly migrated in cutover step 3)

**Statement order within a thunk is load-bearing.** Endpoint existence is checked by `loom.kg/upsert-relation!` at the API layer (it `SELECT`s both endpoints from `entities` and throws if missing/retired). Every compound thunk MUST insert/upsert referenced entities before any relation that points at them, so the SELECT inside `upsert-relation!` succeeds within the same thunk.

Acceptance: a test asserts each compound writer enqueues exactly one item on the `write!` channel, and a second test forces a reorder (relation before entity) and asserts the API-layer endpoint check throws.

## 5. What's Deleted

| Today | Status |
|---|---|
| `loom.graph` ns | **delete** (replaced by `loom.kg`) |
| `loom.memory` ns | **delete** — manual user-curated facts go through `kg/upsert-entity!` directly |
| `loom.session` ns | **delete** — no session-scoped entities; agents don't write to KG |
| `loom.scope` ns | **delete** — no stack semantics; no `strict?` |
| `loom.goals` ns | **delete** — goals live in markdown / agent prompts |
| `db.clj` parquet load/flush helpers (~500 lines) | **delete** |
| `db.clj` `graph_entities` / `graph_relations` / `scope_rank` / `graph-scope-mtimes` | **delete** |
| `db.clj` `migrate-facts-to-graph!` | **delete** |
| `db.clj` `save-fact!`, `search-facts`, `retire-fact!`, `save-session-fact!`, `search-session-facts` | **delete** |
| `facts.parquet` and `sessions/<sid>/facts.parquet` | **delete** |
| `entities.parquet` and `sessions/<sid>/entities.parquet` (and relations) | **delete** (regenerated as DB tables) |
| `goals.parquet` | **delete** |
| `events.parquet` (renamed → `audit` table; **narrow scope**) | replace |
| Event types `finding`, `conclusion`, `approval`, `rejection`, `failure`, `promotion-suggested` | **delete** — agents communicate via tool returns / context, not persistence |
| Promotion machinery: `promote!`, `auto-promote!`, `list-promotion-candidates`, `source_count`, `source_sessions`, `entity-eligible-for-promotion?`, `promotion-min-*` | **delete** |
| `resolve-entity!` cosine+JW fuzzy match | **delete** — IDs are deterministic |
| `extract-and-link!` heuristic line-extractor | **delete** |
| `strict?` flag everywhere | **delete** |

## 6. Cutover Order

Single PR, each commit compiles.

1. **Introduce file-backed connection + new DDL alongside old code.**
   - Add `connect-file!` to `loom.db`. Open `.loom/loom.db` and `.loom/usage.db` (attached).
   - Add `init-schema!` issuing the new DDL above. Old in-memory + parquet machinery still present, untouched.
   - Add `:db-conn` to ctx alongside existing `:conn`. Nothing yet uses it.

2. **Write `loom.kg` against the new connection.**
   - 10 public fns. Internal helpers private.
   - `test/loom/kg_test.clj`: schema init idempotent, FK enforcement, filter precedence, single-`write!` compound write tests.

3. **Migrate writers** to `loom.kg` and the file-backed conn:
   - `loom.tools/register!` → tool row + tool-entity in **one** `write!` thunk (entity inserted before any relation referencing it).
   - `loom.blob/ingest!` → blob row + chunk rows in one thunk (blob before chunks; FK on `chunks.blob_id`).
   - `loom.scratch/save!` → scratch_tools writes use file-backed conn.
   - `loom.scratch/promote!` → scratch_tools update + tools row + KG tool-entity in **one** thunk (entity before its `IMPLEMENTS`/`AUTHORED_BY` relations).

4. **Migrate readers** (anything still calling `loom.graph`/`loom.memory`/`loom.session`).

5. **Replace `events` with `audit`.** This step MUST land before step 7 (which deletes the parquet event helpers).
   - Rename table; narrow types accepted (`^(guard|system|agent)\.` only — see §4).
   - Type validation lives in **`loom.audit/log!`** (API layer), not as a DB CHECK constraint. Cleaner errors, schema stays plain. Unit test pins this layer.
   - Repoint **`loom.guard`** log calls (`guard.clj:225, 261`) and `search-denials` (`guard.clj:330–344`) to `loom.audit`.
   - Repoint **`loom.blob`** warning log (`blob.clj:126`) to `loom.audit/log! :type "system.warning"`.
   - `loom.budget` writes through attached `usage.db`.
   - After this step no caller of `loom.db/log-event!` / `db/events-path` / `db/search-events*` remains, so step 7 can safely delete them.

6. **Drop `strict?`** from every signature, every test.

7. **Delete dead code.** `loom.graph`, `loom.memory`, `loom.session`, `loom.scope`, `loom.goals`, all parquet helpers in `loom.db`, all promotion machinery, `migrate-facts-to-graph!`, legacy facts/session_facts. After this step `wc -l src/loom/db.clj` ≤ 600.

8. **Make `loom.db` private.** All defs `^:private` or moved into `loom.db.internal`.

9. **Update agent personas + skill doc** (`.opencode/agents/*.md`, `.opencode/skills/loom/SKILL.md`).
   - **Drop** every reference to `loom/log-finding!`, `loom/log-conclusion!`, `loom/log-approval!`, `loom/log-rejection!`, `loom/log-failure!`, `loom/log-fact!`, `loom/promote!`, `loom/active-goal`, `loom/create-goal!`, `loom/create-subgoal!`, `loom/close-goal!`, `db/search-facts`, `db/search-events`, `session/search-facts`, `session/log-fact!`, `memory/promote!`. The "Event-logging ownership" table in `loom.md` goes away entirely.
   - **Replace** `loom/search` helper in `SKILL.md` with: `loom/query-entities`, `loom/query-relations`, `loom/neighbors`, `loom/search-chunks`, `loom/search-tools`. No more facts/events helpers.
   - **Add** narrow audit helpers to `SKILL.md`: `loom/audit/log!` (`:type` ∈ `guard.* | system.* | agent.*` only — never `finding`/`conclusion`/etc.), `loom/audit/query`.
   - **Add** narrow `agent.*` tracing in `loom.md` orchestrator: log `agent.start` when dispatching a subagent, `agent.stop` on success, `agent.failure` on failure (was `loom/log-failure!`). These are the *only* agent-emitted audit entries.
   - Subagent outputs (findings, plans, verdicts) flow back as tool responses; the orchestrator passes them along verbatim. No persistence.
   - Drop the entire goal-tracking flow (Step 0 in `loom.md`).
   - Result line counts: `loom.md` 181 → ~60; `finder.md` 52 → ~30; `analyzer.md` 81 → ~55; `reviewer.md` 90 → ~75; `coder.md` 65 → ~55; `SKILL.md` 197 → ~100.

10. **Add `loom.export`.** `(export! ctx out-dir)` does `EXPORT DATABASE` to parquet. Optional, can ship later.

11. **Regenerate.** First boot deletes any pre-existing `.loom/` and recreates. Bootstrap re-registers seed tools.

## 7. Acceptance

Verifiable.

1. `.loom/loom.db` and `.loom/usage.db` are the only state files (plus `scratch/*.clj`).
2. `grep -rn 'strict?' src/ test/` returns zero matches.
3. `grep -rn 'loom\.\(graph\|memory\|session\|scope\|goals\)' src/` returns zero.
4. `loom.kg` exposes the 10 contract fns (`upsert-entity!`, `upsert-relation!`, `retire-entity!`, `retire-relation!`, `merge-entities!`, `query-entities`, `query-relations`, `neighbors`, `bfs`, `subgraph`) plus a thin support surface for bridge writers: 2 in-thunk variants (`upsert-entity*`, `upsert-relation*`), 2 SQL helpers (`exec!`, `query`), and 4 lifecycle re-exports of the privatised `loom.db` defs (`connect-file!`, `init-schema!`, `start-writer!`, `write!`) — total 18. The re-exports exist so §7.4 holds: every namespace except `loom.kg` itself goes through `loom.kg` rather than reaching into the private `loom.db`.
5. `loom.db` exposes zero public fns (every `defn`/`def` has `^:private`).
6. `wc -l src/loom/db.clj` ≤ 600 (currently 1857).
7. `(register! ctx 'foo/bar)` enqueues exactly one item on the `write!` channel.
8. Endpoint integrity: `loom.kg/upsert-relation!` with non-existent subject_id or object_id throws an `ex-info` — verified by test. (DB-level FK omitted; see §2 quirks note.)
9. All tests green; new tests for endpoint validation, filter precedence, audit append-only.
10. Agent personas no longer reference `db/log-event!` for `finding`/`conclusion`/`approval`/`rejection`/`failure`/`log-fact!`/`promote!`/goal helpers. `grep -rn 'log-finding\|log-conclusion\|log-approval\|log-rejection\|log-failure\|log-fact\|active-goal\|create-goal\|close-goal\|memory/promote\|session/search-facts' .opencode/` returns zero matches.
11. `SKILL.md` exposes only: `loom/query-entities`, `loom/query-relations`, `loom/neighbors`, `loom/search-chunks`, `loom/search-tools`, `loom/upsert-entity!`, `loom/register-tool!`, `loom/audit/log!`, `loom/audit/query`. ≤ 9 named helpers.
12. Audit log only accepts types matching `^(guard|system|agent)\.`. **Validation lives in `loom.audit/log!` (API layer), not as a DB CHECK constraint** — verified by a unit test that calls `(audit/log! ctx {:type "finding" …})` and asserts an `ex-info` is thrown with `:reason :invalid-type`.

## 8. Explicit Non-Goals and v1 Scope

### What v1 does NOT do

- **No conversation resumption.** With goals, log-fact!, and findings/conclusions all gone, Loom has zero persistence for "what was the user working on last turn?" The replacement is **markdown plan files in the repo** (e.g., `plans/architecture.md` — this file). Agents read those for context. Loom does not track in-flight conversational state.
- **No agent-inferred KG writes.** Agents read the KG; they don't `upsert-entity!` from chatter. The user (manually) or `loom.ingest` (future) is the only authoritative writer.
- **No cross-session learning.** Every conversation starts cold against a stable, authored project model.

### v1 KG content

After bootstrap, the KG contains:
- One `tool` entity per registered seed tool (created by `loom.tools/register!` in cutover step 3).
- The `IMPLEMENTS` / `AUTHORED_BY` relations between tools and their concept/agent entities.
- Nothing else.

User-authored entities arrive via direct `kg/upsert-entity!` calls from the nREPL. Bulk authored content (codebase, business docs) waits for `loom.ingest`. **A first user query against an empty graph is expected**, not a bug.

### Follow-up work

- **`loom.ingest`** — codebase + business-doc ingestion. The KG is "the model"; ingest fills it from authored sources. Separate plan once foundation lands.
- **Multi-language ingestion** — Clojure first via `tools.reader`. Tree-sitter for others later.
- **`loom.export`** — `EXPORT DATABASE` to parquet. Trivial; can land any time after foundation.
- **UI / dashboard** — separate concern; reads through nREPL.
