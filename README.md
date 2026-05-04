# Loom

Loom is a semantic memory and tool registry for AI agents, designed to be called over nREPL from an external agent (e.g. OpenCode).

It gives agents:
- **Knowledge graph memory** across sessions (`entities` + `relations`, session-first reads)
- **Semantic search** over graph memory and tools using local embeddings
- **A tool registry** — agents discover, register, and reuse Clojure functions
- **Blob ingestion** — chunk and embed documents for RAG
- **Goal tracking** — hierarchical goals with status transitions and event linkage
- **Budget enforcement** — per-agent call/duration limits with usage recording
- **An nREPL server** — the agent connects and calls everything as live Clojure

---

## Architecture

```
External agent (OpenCode / human at REPL)
  │
  │  nREPL :7888
  ▼
loom.core/start!
  ├── loom.db          — DuckDB over parquet files (tools, goals, chunks, events, KG tables)
  ├── loom.graph       — KG APIs: extract/link, resolve/merge, promote, traversal
  ├── loom.embedder    — Ollama nomic-embed-text (768-dim)
  ├── loom.tools       — register!, scan-ns!, search
  ├── loom.session     — per-session observation logging + session KG entities
  ├── loom.memory      — promote/search/forget over global KG entities
  ├── loom.blob        — ingest documents, chunk, embed
  ├── loom.state       — in-process atom mirror of tool registry
  ├── loom.scratch     — session-scoped tool creation, hit tracking, promotion
  ├── loom.goals       — hierarchical goal tracking, status transitions
  ├── loom.budget      — per-agent usage recording and budget enforcement
  └── loom.repl        — nREPL server on port 7888
```

### Memory tiers

| Tier | Namespace | Storage | Scope |
|---|---|---|---|
| 1 — Scratch | `loom.scratch` | `scratch/*.clj` + session `hits.parquet` | Session (persisted, not promoted) |
| 2 — Session | `loom.session`, `loom.graph` | `.loom/sessions/<id>/{entities,relations}.parquet` | Current session |
| 3 — Global | `loom.memory`, `loom.graph` | `.loom/{entities,relations}.parquet` | All sessions |

### Parquet files

| File | Contents |
|---|---|
| `.loom/tools.parquet` | Registered tool definitions |
| `.loom/entities.parquet` | Global KG entities (memory system of record) |
| `.loom/relations.parquet` | Global KG relations (memory system of record) |
| `.loom/events.parquet` | Audit trail (findings, conclusions, approvals) |
| `.loom/chunks.parquet` | Blob document chunks for RAG |
| `.loom/goals.parquet` | Hierarchical goals |
| `.loom/usage.parquet` | Per-agent tool call usage (budget) |
| `.loom/sessions/<id>/entities.parquet` | Session KG entities |
| `.loom/sessions/<id>/relations.parquet` | Session KG relations |
| `.loom/sessions/<id>/hits.parquet` | Scratch tool hit counts |
| `.loom/facts.parquet` | Legacy facts store (migration source / compatibility) |
| `.loom/sessions/<id>/facts.parquet` | Legacy session facts store (migration source / compatibility) |

---

## Requirements

- Java 11+
- [Clojure CLI](https://clojure.org/guides/install_clojure)
- [Ollama](https://ollama.com) running locally with `nomic-embed-text` pulled:
  ```sh
  ollama pull nomic-embed-text
  ```

---

## Setup

```sh
git clone https://github.com/smogstate/loom loom
cd loom
clojure -M:dev -e "(require 'dev) (dev/start!)"   # starts Loom + nREPL via dev/dev.clj
```

Or start programmatically from another process:

```clojure
(require '[loom.core :as loom])
(def ctx (loom/start!))   ; connects DB, seeds tools, starts nREPL on :7888
```

### Options

```clojure
(loom/start! {:loom-dir   ".loom"                    ; default — parquet files stored here
              :ollama-url "http://localhost:11434"    ; default
              :session-id "my-session"})              ; optional, auto-generated if omitted
```

---

## Usage from an agent

Connect to nREPL on port 7888, then:

### Find relevant tools
```clojure
(def q (unwrap! (embedder/embed ctx "parse csv")))
(unwrap! (db/search-tools ctx q 5))
```

### Register a new tool
```clojure
(defn ^{:doc "Parse a CSV string into rows." :tags ["csv" "parse"]}
  parse-csv [ctx s]
  (with-provenance "parse-csv" 1
    (map #(clojure.string/split % #",") (clojure.string/split-lines s))))

(tools/register! ctx 'user/parse-csv)
```

### Log a session observation
```clojure
(session/log-fact! ctx "The API returns paginated results with cursor.")
```

### Promote a stable fact to global memory
```clojure
(memory/promote! ctx "Service X runs on port 9090." {:tags ["infra"] :type :stable})
```

### Search global memory
```clojure
(memory/search ctx "which port does service X use?" 3)
```

### Ingest a document
```clojure
(blob/index! ctx (slurp "docs/spec.md") {:source "docs/spec.md"})
```

### Index all project source files
```clojure
(require '[loom.init :as init])
(unwrap! (init/run! ctx))   ; idempotent — skips already-indexed files
```

### Log an event (audit trail)
```clojure
(db/log-event! ctx {:type       "finding"          ; or "conclusion" "approval" "rejection"
                    :content    "..."
                    :session-id (:session-id ctx)
                    :agent-id   "finder"
                    :goal-id    "goal-uuid"         ; optional — links event to a goal
                    :thread-id  "plan/X/step/1"})   ; optional — groups related events
```

### Track goals
```clojure
(require '[loom.goals :as goals])

(def gid (unwrap! (goals/create-goal! ctx {:title       "Refactor auth module"
                                           :description "Extract JWT logic into its own ns"
                                           :status      "open"})))

(goals/update-status! ctx gid "active")
(goals/link-event!    ctx event-id gid)
```

### Meter agent tool calls (budget)
```clojure
(require '[loom.budget :as budget])

;; Wrap every agent tool call — enforces limits, records usage
(binding [budget/*agent-id* "analyzer"]
  (budget/call ctx db/search-tools [query-vec 5]))

;; Inspect usage for the current session
(unwrap! (budget/current-usage ctx "analyzer"))
;; => {:usd 0.0 :duration_ms 340 :calls 12}

;; Full report grouped by agent + op
(unwrap! (budget/report ctx {}))
```

Budget limits are configured in `.loom/budget.edn`:
```clojure
{:budgets {:default   {:usd 1.00 :duration-ms 60000  :calls 1000}
           "analyzer" {:usd 5.00 :duration-ms 300000 :calls 5000}
           "finder"   {:usd 1.00 :duration-ms 60000  :calls 2000}}}
```

---

## Agent personas

Pre-written agent personas for OpenCode live in `.opencode/agents/` in the workspace root (not inside the loom repo). They are versioned here in `loom/.opencode/agents/` as the canonical source of truth.

| File | Role |
|---|---|
| `loom.md` | Orchestrator — classifies tasks, dispatches to subagents |
| `finder.md` | Pure retrieval — search, fetch, list |
| `analyzer.md` | Reasoning, tool creation, pattern promotion |
| `reviewer.md` | Quality assurance, approval/rejection |

Use `/loom-sync` to propagate changes from `loom/.opencode/` to the workspace `Projects/.opencode/`.

---

## Envelope contract

Every Loom function returns a provenance envelope:

```clojure
{:ok?        true
 :result     <value>
 :provenance {:op            "loom.db/save-tool!"
              :version       1
              :duration-ms   12
              :started-at-ms 1234567890}
 :error      nil}
```

Use `(unwrap! envelope)` to get the result or throw on failure.

---

## Project structure

```
src/loom/
  core.clj       — start!, make-ctx, bootstrap!
  db.clj         — DuckDB parquet IO, KG tables, merge/traversal/migration helpers
  graph.clj      — KG orchestration APIs (resolve, merge, promote, BFS, extraction)
  embedder.clj   — Ollama HTTP embed call
  envelope.clj   — with-provenance macro, unwrap!
  memory.clj     — promote!, forget!, search (global KG concepts)
  session.clj    — log-fact!, search-facts (session KG concepts)
  state.clj      — in-process atom tool registry
  tools.clj      — register!, scan-ns!, start-watcher!
  blob.clj       — ingest!, chunk!, embed documents
  init.clj       — index project source files (idempotent)
  scratch.clj    — session-scoped tool creation, hit tracking, promotion
  goals.clj      — hierarchical goal tracking and event linkage
  budget.clj     — per-agent usage recording and budget enforcement
  repl.clj       — nREPL server start!/stop!
  seed/          — built-in tool libraries (http, fs, text, data, math, db, project, eval)

.opencode/       — OpenCode integration (agents, commands, skills) — canonical source of truth
loom_eval.py     — minimal Python nREPL client (strips :vector fields for readability)
test/loom/       — unit tests
```
