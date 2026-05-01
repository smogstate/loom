# Loom

Loom is a semantic memory and tool registry for AI agents, designed to be called over nREPL from an external agent (e.g. OpenCode).

It gives agents:
- **Persistent memory** across sessions (facts, events, session notes)
- **Semantic search** over memory and tools using local embeddings
- **A tool registry** — agents discover, register, and reuse Clojure functions
- **Blob ingestion** — chunk and embed documents for RAG
- **An nREPL server** — the agent connects and calls everything as live Clojure

---

## Architecture

```
External agent (OpenCode / human at REPL)
  │
  │  nREPL :7888
  ▼
loom.core/start!
  ├── loom.db          — DuckDB: tools, facts, events, session facts, blobs
  ├── loom.embedder    — Ollama nomic-embed-text (768-dim)
  ├── loom.tools       — register!, scan-ns!, search
  ├── loom.session     — per-session fact logging (tier 2 memory)
  ├── loom.memory      — promote to global facts, forget, suggest
  ├── loom.blob        — ingest documents, chunk, embed
  ├── loom.state       — in-process atom mirror of tool registry
  └── loom.repl        — nREPL server on port 7888
```

### Memory tiers

| Tier | Namespace | Storage | Scope |
|---|---|---|---|
| 1 — Working | `scratchpad` atom | In-process | Single turn |
| 2 — Session | `loom.session` | DuckDB session facts table | Current session |
| 3 — Global | `loom.memory` | DuckDB facts table | All sessions |

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
clojure -M:dev   # starts nREPL via dev/dev.clj
```

Or start programmatically from another process:

```clojure
(require '[loom.core :as loom])
(def ctx (loom/start!))   ; connects DB, seeds tools, starts nREPL on :7888
```

### Options

```clojure
(loom/start! {:db-path    ".loom/loom.db"      ; default
              :loom-dir   ".loom"               ; default
              :ollama-url "http://localhost:11434"  ; default
              :session-id "my-session"})        ; optional, auto-generated if omitted
```

---

## Usage from an agent

Connect to nREPL on port 7888, then:

### Find relevant tools
```clojure
(def q (unwrap! (embedder/embed ctx "parse csv")))
(db/search-tools ctx q 5)
```

### Register a new tool
```clojure
(defn ^{:doc "Parse a CSV string into rows." :tags ["csv" "parse"]}
  parse-csv [ctx s]
  (with-provenance "parse-csv" 1
    (map #(clojure.string/split % #",") (clojure.string/split-lines s))))

(tools/register! ctx #'parse-csv)
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
(blob/ingest! ctx "docs/spec.md" {:project "myapp"})
```

### Index all project source files
```clojure
(require '[loom.init :as init])
(unwrap! (init/run! ctx))   ; idempotent — skips already-indexed files
```

### Log an event (audit trail)
```clojure
(db/log-event! ctx {:type "finding" :content "..." :session-id (:session-id ctx) :agent-id "finder"})
```

---

## Agent personas

Pre-written agent personas for OpenCode are in `.opencode/agents/` (canonical) and mirrored to `agents/` for reference:

| File | Role |
|---|---|
| `router.md` | Classifies tasks, dispatches to other agents |
| `finder.md` | Pure retrieval — search, fetch, list |
| `analyzer.md` | Reasoning, tool creation, pattern promotion |
| `reviewer.md` | Quality assurance, approval/rejection |

---

## Envelope contract

Every Loom function returns a provenance envelope:

```clojure
{:ok?        true
 :result     <value>
 :provenance {:op          "loom.db/save-tool!"
              :version     1
              :duration-ms 12
              :started-at-ms 1234567890}
 :error      nil}
```

Use `(unwrap! envelope)` to get the result or throw on failure.

---

## Project structure

```
src/loom/
  core.clj       — start!, make-ctx, bootstrap!
  db.clj         — DuckDB schema, write queue, all save/search fns
  embedder.clj   — Ollama HTTP embed call
  envelope.clj   — with-provenance macro, unwrap!
  memory.clj     — promote!, forget!, search (global facts)
  session.clj    — log-fact!, search-facts (session-scoped)
  state.clj      — in-process atom tool registry
  tools.clj      — register!, scan-ns!, start-watcher!
  blob.clj       — ingest!, chunk!, embed documents
  repl.clj       — nREPL server start!/stop!
  seed/          — built-in tool libraries (http, fs, text, data, math, db, project)
  agents/        — agent impl stubs (reserved for future use)

agents/          — agent persona prompts for OpenCode
test/loom/       — unit tests
```
