# Loom Ingest — Authored KG from Source Files

Status: REVIEWED — APPROVE WITH CHANGES applied (2026-05-04)
Date: 2026-05-04
Builds on: `plans/architecture.md` (v2 foundation, complete)
Scope: First milestone is **`ingest-document!`** (markdown / plain-text docs).
       `ingest-codebase!` and `resync!` are sketched for context but not in
       this milestone.

## 1. Goal

The v2 KG is empty after boot — it contains only the seed-tool entities created by `loom.tools/register!`. Loom is supposed to be the project model the LLM consults to "understand how to operate", which means the KG has to be populated from authored sources: business documents, design notes, the project's source code, etc.

`loom.ingest` is the canonical writer of project content into the KG. Agents read from it; they don't fill it. Re-running ingestion against unchanged input is a no-op (deterministic ids).

## 2. Public Surface (target)

```clojure
(ns loom.ingest)

(ingest-document!  ctx path opts)   ;; this milestone
(ingest-codebase!  ctx root opts)   ;; later — see §4
(resync!           ctx       opts)  ;; later — see §5
```

Three public fns. Same envelope contract as everything else (`with-provenance`).

The reserved kwargs on `ingest-document!` declare future capabilities now so the surface doesn't break when those features land:

- `:parallel?` — embed chunks in parallel (default `false`; v2).
- `:batch-size` — split a long doc into N batched write thunks for resumability (default `nil` = whole-doc thunk; v2 will turn it on).
- `:llm-extract?` — second-pass LLM extraction of business entities from chunks (default `false`; throws `:reason :llm-extract-pending` if `true` until v2 wires it).

## 3. `ingest-document!` — detailed design

```clojure
(ingest-document! ctx path
  {:title?    string                   ;; default: first H1 or file basename
   :project?  string                   ;; tag carried in attrs
   :as?       :doc | :business-model   ;; metadata in attrs.role; doc kind is ALWAYS "file"
   :tags?     [string …]
   :max-chunk-chars? int               ;; default 1000
   :min-chunk-chars? int               ;; default 50
   :parallel?     bool                 ;; reserved; default false
   :batch-size    int|nil              ;; reserved; default nil (whole-doc thunk)
   :llm-extract?  bool})               ;; default false; throws :llm-extract-pending if true
```

### 3.1 Pipeline

1. **Read & hash.** Load the file, compute `sha256(content)` once. Compute `sha256(abs-path)` for the doc identity.
2. **Store the blob.** Reuse `loom.blob/ingest!` — gzip to disk, write a row in `blobs`. Returns a content-hash blob-id.
3. **Chunk.** Two-mode:
   - **Markdown** (`.md`/`.markdown`): split on heading lines (`^#{1,4}\s`). Each chunk is `heading + body until next heading`. If a chunk exceeds `:max-chunk-chars`, split it on paragraph boundaries. Drop chunks shorter than `:min-chunk-chars`.
   - **Plain text** (anything else): split on blank-line paragraph blocks. Same min/max rules.
4. **Embed.** For each chunk: `embedder/embed ctx chunk-text`. Done **before** entering the write thunk so HTTP latency does not serialise the writer queue.
5. **Compose entities + relations** (in memory, no writes yet):
   - **Doc entity** — `kind` is **always `"file"`** for local paths and `"external"` for URLs. The optional `:as` is recorded as `attrs.role ∈ {"doc","business-model"}` — it does NOT change the kind. Reason: `business_entity` is reserved for actual domain objects (User, Order); a markdown file describing them is still a file. The future `:llm-extract?` pass mints real `business_entity` rows from chunk content. Id: `file:<sha256-abs-path>` or `external:<sha256-url>`.
   - **Chunk-concept entities** — one per chunk, `kind = "concept"`, id `concept/<sha256-chunk-content>`, vector = the chunk's embedding, `attrs.heading`, `attrs.chunk_offset`.
   - **`PART_OF` chunk → doc entity** (one per chunk). This is the only relation v1 emits for the structural edge. We do NOT also emit `MENTIONED_IN` — that predicate is reserved for the v2 extraction pass that links a *named entity inside a chunk* to the chunk that names it. Doubling up `PART_OF` and `MENTIONED_IN` for the same edge would be redundant.
6. **Single `kg/write!` thunk.** Inside one outer `kg/write!`:
   - Doc entity goes first (endpoint check in `upsert-relation*` requires it).
   - Then all chunk entities.
   - Then all `PART_OF` relations (both endpoints exist by now).
   - Then chunk rows in the `chunks` table (same `blob_id` as the underlying blob; `vector` matches the chunk-concept entity).

### 3.2 Idempotency and concept lifecycle

- All ids deterministic from content / path. Re-running `ingest-document!` on unchanged input rewrites the same rows.
- If the file changes, the blob id changes (new sha256). Old chunk-concept entities remain in the KG; nothing automatically retires them. **`resync!` is the future fn that retires orphaned entities.**
- **Concept entity lifecycle is independent of any single doc.** Two documents quoting the same paragraph share one concept entity (id is `sha256` of content). That concept then carries N `PART_OF` edges, one per doc. `resync!` retires a concept entity only when *no* live `PART_OF` edge remains. This is a feature: cross-doc deduplication of identical content.

### 3.3 Atomicity

The whole doc is one `kg/write!` thunk by default. Trade-off vs batched thunks:

- **Whole-doc** (default, `:batch-size nil`): stronger atomicity (no half-ingested doc). Write queue is held for the duration of the INSERT batch. For typical docs (<200 chunks) the lock window is tens of ms — fine. **Failure cost**: if ANY chunk's pre-thunk embed fails (Ollama hiccup at chunk 999/1000), the whole job is discarded. Retry from scratch.
- **Batched** (`:batch-size N`): split the chunk list into batches of N. Each batch does its own embed-then-write thunk. Deterministic ids make every batch idempotent on retry; partial progress survives. Doc entity is upserted in the first batch's thunk so subsequent batches' `PART_OF` endpoint check passes.

V1 ships with whole-doc default; the `:batch-size` opt is parsed and reserved (returns `:not-implemented` if non-nil) until v2 wires the batched path. Reason: a 1000-chunk failure cost is real but rare — better to ship the simple path and add batching once we have a doc that hits the limit in anger.

### 3.4 Predicate ontology fit

Ontology already has `MENTIONED_IN`, `PART_OF`, `DEFINED_IN`, `ABOUT`. Document ingestion uses `MENTIONED_IN` (chunk content references doc) and `PART_OF` (chunk is structurally part of doc). No new predicates required.

### 3.5 What's NOT in v1 of `ingest-document!`

- LLM-extracted typed business entities (User, Order, …). Reserved for `:llm-extract? true`. **V1 throws** `(ex-info "not yet implemented" {:reason :llm-extract-pending})` if the caller passes `true` — better than silently ignoring.
- Embedding parallelism (`:parallel? true`). V1 throws on non-default; v2 implements.
- Batched-thunk resumability (`:batch-size N`). V1 throws on non-default; v2 implements.
- URL ingestion. Deferred — requires HTTP fetch + content-type sniff. Local files only for v1.
- Cross-document concept linking (this chunk in doc A is the same as that chunk in doc B). Already falls out automatically: identical content → same `concept/<sha256>` id → both docs' `PART_OF` edges land on the same concept entity. No extra logic.

## 4. `ingest-codebase!` — sketch (deferred)

- Walk a source root (skip `.git`, `node_modules`, `.loom`, `target`, etc.).
- For Clojure: parse each file with `tools.reader`. Emit:
  - `kind="module"` entity per `ns` form. Id `module:<ns-name>`.
  - `kind="function"` per `defn`. Id `function:<ns-name>/<fn-name>`.
  - Relations: `DEFINED_IN` (function → module), `REQUIRES` (module → module).
  - Syntactic-only call sites: optionally emit a `USES` edge per top-level symbol the function body literally references — but only when the symbol resolves to a known `function:`/`module:` entity. **Do not** promise semantic call resolution: macros, multimethods, `requiring-resolve`, runtime dispatch all produce false negatives or noise. Better to under-emit than mislabel.
- For other languages: tree-sitter integration. Out of scope for now.
- Same deterministic-id + single-thunk pattern as `ingest-document!`.

## 5. `resync!` — sketch (deferred)

- Compares `(file:* | external:* | function:* | module:*)` entities in the KG against the current filesystem.
- Re-ingests changed files (mtime + content hash differ).
- Retires entities whose source artifact no longer exists.
- Single fn, idempotent. Operationally: a cron / pre-commit hook style invocation.

## 6. Cutover for `ingest-document!`

Single PR. Each step compiles.

1. **Skeleton.** Create `src/loom/ingest.clj` with a stub `ingest-document!` that throws `:not-implemented`. Reserved kwargs (`:parallel?`, `:batch-size`, `:llm-extract?`) are validated and rejected with `:reason :…-pending` when non-default. Not added to `seed-namespaces` — write-side primitive, not a callable seed tool.
2. **Markdown chunker.** Pure-fn `(chunk-markdown text {:max :min})` returning `[{:heading :body :offset}]`. Tests: heading split, max-chunk overflow with hard-cut for over-long single paragraphs, paragraph fallback for no-heading docs, deeply nested headings (H1 inside H6 region), unicode/emoji-only chunks, empty input.
3. **Wire-up.** Integrate chunker + embedder + `loom.blob/ingest!` + `kg/upsert-entity*` / `kg/upsert-relation*` + chunks-table INSERT, all inside one `kg/write!` thunk. Reuse `loom.blob/ingest!` for the gzip-and-blob-row write only; do NOT call `loom.blob/chunk!` (its line/LLM chunking duplicates this work).
4. **Tests.** End-to-end against a sample markdown file in `test/loom/fixtures/`. Verify: 1 blob row, N chunk rows, 1 doc entity, N concept entities, N `PART_OF` relations, idempotent on second run, single `kg/write!` thunk per doc, rejection on `:llm-extract? true`. Plus the test gaps from §7.
5. **Repoint LOOM.md boot ingestion.** Update `core.clj/maybe-ingest-loom-md!` to call `loom.ingest/ingest-document!` instead of `loom.seed.project/ingest-project-md!`. Delete `loom.seed.project` (drop from `seed-namespaces` too).
6. **`loom.blob/chunk!` disposition.** With `ingest-document!` doing structural chunking and `ingest-codebase!` planned, `blob/chunk!` has no caller in v2. Mark it deprecated in its docstring; delete in the cutover PR for `ingest-codebase!`.
7. **Skill doc.** Add `loom/ingest-document!` helper to `.opencode/skills/loom/SKILL.md`.

## 7. Acceptance

1. `(ingest-document! ctx "path/to.md" {})` returns `{:doc-id "file:<sha>" :chunks N :entities (+ N 1) :relations N}`.
2. Re-running on identical file is a no-op (entities/blob unchanged, no errors, return shape identical).
3. After ingest, `(kg/neighbors ctx doc-id {:direction :in :predicates ["PART_OF"]})` returns N rows.
4. **Verifiable relevance.** Test fixture: ingest a doc with two known-distinct chunks (e.g. one about "payments", one about "auth"). Embed the literal text of chunk 1 as a query vector; assert the first row of `(kg/query-entities ctx {:vector q :kind "concept" :limit 2})` is chunk 1's concept entity (not chunk 2's).
5. Single `kg/write!` thunk per doc — verified by `with-redefs` counting test (matches §2.1 acceptance pattern from architecture.md).
6. Markdown without headings ingests via paragraph chunking.
7. Empty file ingests cleanly (1 blob, 0 chunks, 1 doc entity, 0 relations).
8. `:llm-extract? true` throws `ex-info` with `:reason :llm-extract-pending`. Same for `:parallel? true` (`:parallel-pending`) and `:batch-size N` non-nil (`:batch-pending`).
9. **Concurrent-call idempotency.** Two `ingest-document!` invocations on the same file from different threads serialise through the writer queue and produce one consistent end state — no duplicate rows, no FK errors.
10. **Edge cases**: malformed/unterminated code-fence markdown, single paragraph exceeding `:max-chunk-chars` (hard-cut), unicode/emoji chunks, deeply nested headings (H1 inside H6 region) all ingest without throwing.
11. Tests under `test/loom/ingest_test.clj` pass.
12. After step 5 cutover, `loom.seed.project` is deleted and `grep -rn "loom\\.seed\\.project" src/ .opencode/` returns zero matches.

## 8. Resolved decisions and remaining questions

### Resolved (closed during review)

- **Doc-entity kind** → always `"file"` / `"external"`, never `"business_entity"`. `:as` lives in `attrs.role`.
- **`MENTIONED_IN` vs `PART_OF`** → only `PART_OF` for the structural chunk→doc edge. `MENTIONED_IN` reserved for the v2 LLM-extract pass.
- **Concept lifecycle** → independent of any single doc; cross-doc dedup via shared sha256 id; `resync!` retires only when no live `PART_OF` remains.
- **`:llm-extract? true` / `:parallel? true` / `:batch-size N`** → throw `ex-info` with descriptive `:reason`. No silent no-ops.
- **`loom.seed.project`** → subsumed and deleted in cutover step 5.
- **`loom.blob/chunk!`** → deprecated; deleted with `ingest-codebase!`.
- **Embedding the doc itself** → yes, doc entity gets a vector embedded from `(or title basename)` so top-level search finds the file.

### Remaining questions

1. **`loom.init` overlap.** `loom.init/run!` indexes project source into chunks today. With `ingest-codebase!` planned, do we delete `loom.init` (subsume) or keep it as a "shallow chunk-only" mode? Recommend: subsume into `ingest-codebase!` once that lands; for now leave `loom.init` alone.
2. **Heading depth.** Should H4/H5/H6 also create chunk boundaries? V1 default H1–H4 only; configurable via `:heading-depth N` (max 6). Acceptable?
