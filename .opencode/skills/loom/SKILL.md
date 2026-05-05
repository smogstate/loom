---
name: loom
description: How to connect to Loom (project-scoped knowledge graph + tool registry + RAG + audit log) and call its v2 API over nREPL. Load this before any Loom operation.
compatibility: opencode
---

## Transport

All Loom calls go through the nREPL client. Never use `clojure -e` or `clojure -M`.

```bash
python3 ~/Projects/loom/loom_eval.py '<clojure expr>'
```

Already available in the nREPL namespace — do not require:

- `unwrap!`, `ok?` — from `loom.envelope`
- `ctx` — the Loom context
- `kg`, `tools`, `blob`, `audit`, `embedder`, `scratch`, `init` — aliased

## Check / start nREPL

```bash
nc -z localhost 7888 2>&1 && echo "running" || echo "not running"
```

If not running:

```bash
tmux new-session -d -s loom -c ~/Projects/loom 'clojure -M:dev' 2>/dev/null || true
for i in $(seq 1 40); do nc -z localhost 7888 2>/dev/null && echo "nREPL ready" && break || sleep 1; done
```

---

## What's persisted, what's not

The KG is **authored** — by you (manual upsert) or by future ingestion pipelines. Agents read; they don't write knowledge from chatter. Subagent outputs (findings, plans, verdicts) flow back via tool responses, **not** persisted. The audit log is narrow: governance + tracing only.

| Persisted | Not persisted |
|---|---|
| `entities`, `relations`, `tools`, `scratch_tools`, `blobs`, `chunks`, `audit`, `usage` | findings, conclusions, approvals, rejections, failures, goals |

---

## Read helpers

### loom/query-entities

Filter precedence: `:vector` → ORDER BY distance, `:name-prefix` → ORDER BY name, `:ids` → batch get, none → ORDER BY updated_at DESC. `:kind` and `:limit` always apply.

```bash
# semantic
python3 ~/Projects/loom/loom_eval.py '(let [v (unwrap! (embedder/embed ctx "QUERY"))]
  (unwrap! (kg/query-entities ctx {:vector v :kind "concept" :limit 5})))'

# name prefix
python3 ~/Projects/loom/loom_eval.py '(unwrap! (kg/query-entities ctx {:name-prefix "User" :kind "business_entity"}))'

# batch get by id
python3 ~/Projects/loom/loom_eval.py '(unwrap! (kg/query-entities ctx {:ids ["concept/foo" "concept/bar"]}))'
```

### loom/query-relations

```bash
python3 ~/Projects/loom/loom_eval.py '(unwrap! (kg/query-relations ctx {:subject-id "tool/loom.seed.fs/read-file" :predicate "USES"}))'
```

### loom/neighbors

```bash
python3 ~/Projects/loom/loom_eval.py '(unwrap! (kg/neighbors ctx "concept/foo" {:direction :out :predicates ["IMPLEMENTS"]}))'
```

### loom/search-tools

```bash
python3 ~/Projects/loom/loom_eval.py '(let [v (unwrap! (embedder/embed ctx "parse csv"))]
  (unwrap! (kg/query-entities ctx {:vector v :kind "tool" :limit 5})))'
```

### loom/search-chunks

```bash
python3 ~/Projects/loom/loom_eval.py '(let [v (unwrap! (embedder/embed ctx "QUERY"))]
  (unwrap! (loom.seed.db/search-chunks ctx "QUERY")))'
```

---

## Write helpers (rare; agents normally read only)

### loom/ingest-document!

Ingest a markdown / plain-text file into the project KG. Idempotent — re-running on the same content rewrites the same rows. Creates: 1 `kind="file"` entity (with vector embedded from title), N `kind="concept"` entities (one per chunk), N `PART_OF` relations (concept → file), N rows in `chunks`.

```bash
python3 ~/Projects/loom/loom_eval.py '(unwrap! (loom.ingest/ingest-document! ctx "docs/spec.md" {:project "loom" :tags ["spec"]}))'
```

Optional opts: `:title` (override), `:as` (`:doc` / `:business-model` — recorded in attrs), `:max-chunk-chars` (default 1000), `:min-chunk-chars` (default 50), `:heading-depth` (default 4).

Reserved (throws if used): `:parallel?`, `:batch-size`, `:llm-extract?` — wired in v2.

### loom/upsert-entity!

Use only for explicit user-authored entities. Deterministic ids preferred.

```bash
python3 ~/Projects/loom/loom_eval.py '(unwrap! (kg/upsert-entity! ctx
  {:id "concept/payment-flow"
   :kind "concept"
   :canonical_name "Payment Flow"
   :attrs {:source "manual"}
   :vector (unwrap! (embedder/embed ctx "Payment Flow"))}))'
```

### loom/register-tool!

```bash
python3 ~/Projects/loom/loom_eval.py '
(defn ^{:doc "DESCRIPTION" :tags ["TAG"]}
  TOOL_NAME [ctx ARG]
  (loom.envelope/with-provenance "TOOL_NAME" 1
    BODY))
(unwrap! (tools/register! ctx (quote user/TOOL_NAME)))'
```

---

## Audit log

Narrow types only — `^(guard|system|agent)\.…`. Never use for findings/conclusions.

### loom/audit-log

```bash
# system warning
python3 ~/Projects/loom/loom_eval.py '(unwrap! (audit/log! ctx {:type "system.warning" :content "MESSAGE"}))'

# agent tracing (orchestrator only — see loom.md)
python3 ~/Projects/loom/loom_eval.py '(unwrap! (audit/log! ctx {:type "agent.start" :agent-id "analyzer" :content "task X"}))'
```

### loom/audit-query

```bash
# recent guard denials
python3 ~/Projects/loom/loom_eval.py '(unwrap! (audit/query ctx {:type-prefix "guard." :limit 50}))'
```

---

## Envelope rules

- Always call `unwrap!` on any Loom return value before using it as data.
- On error `unwrap!` throws — the error message describes what failed.
- `(:provenance result-env)` contains `:op`, `:duration-ms`, `:version` for debugging.
