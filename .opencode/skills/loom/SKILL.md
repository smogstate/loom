---
name: loom
description: How to connect to Loom (semantic memory + tool registry), call its API over nREPL, and correctly unwrap envelope results. Load this before any Loom operation.
compatibility: opencode
---

## Agent tool usage policy

1. **Use OpenCode tools first** — Read, Write, Edit, Glob, Grep, Bash (for git/system ops), etc.
2. **If OpenCode tools are not enough**, use `loom-eval` via nREPL instead of writing ad-hoc Python or bash scripts.
3. **Never write throwaway scripts** to solve problems.
4. If the logic is reusable, define it as a Clojure function and register it as a Loom tool via `tools/register!`.

**NEVER use `clojure -e` or `clojure -M` to evaluate expressions** — this starts a new JVM, breaks classpath, and will fail. Always use the nREPL transport below.

The nREPL transport lives at `~/Projects/loom/loom_eval.py` — it is infrastructure, not a script.
Invoke it as: `python3 ~/Projects/loom/loom_eval.py '<clojure expr>'`

All of these are already available in the nREPL namespace — **do not require them**:
- `unwrap!`, `ok?` — from `loom.envelope`
- `ctx` — the Loom context
- `db`, `session`, `memory`, `tools`, `embedder`, `blob`, `init` — all aliased

So just call them directly:
```bash
python3 ~/Projects/loom/loom_eval.py '(unwrap! (session/search-facts ctx "query" 5))'
```

## Check nREPL is running

```bash
nc -z localhost 7888 2>&1 && echo "running" || echo "not running"
```

---

## What is Loom

Loom is a semantic memory and tool registry that runs as a Clojure nREPL server on port 7888.
Every public function returns a provenance envelope `{:ok? bool :result <value> :provenance {...} :error nil|{...}}`.
Always unwrap with `unwrap!` before using the result.

---

## FIRST: Ensure nREPL is running

**Always do this before any Loom operation:**

```bash
nc -z localhost 7888 2>&1 && echo "running" || echo "not running"
```

If not running, start it in a background tmux session:

```bash
tmux new-session -d -s loom -c ~/Projects/loom 'clojure -M:dev' 2>/dev/null || true
# Wait for nREPL — it starts automatically via dev/user.clj -> dev/start!
for i in $(seq 1 40); do nc -z localhost 7888 2>/dev/null && echo "nREPL ready" && break || sleep 1; done
```

---

## Connect

After nREPL is running, bootstrap once per session:

```bash
python3 ~/Projects/loom/loom_eval.py '(require (quote [dev])) (dev/start!)'
```

Then every `loom_eval.py` call must require `unwrap!` if used:

```bash
python3 ~/Projects/loom/loom_eval.py '(do (require (quote [loom.envelope :refer [unwrap!]])) (unwrap! (loom.seed.eval/eval-expr @dev/ctx "(+ 1 2)")))'
```

`@dev/ctx` is the live context. `loom.seed.eval/eval-expr` is the registered eval tool.

---

## Embed a query

```clojure
(require '[loom.embedder :as embedder])

(def q (unwrap! (embedder/embed ctx "your query text")))
;; q is a 768-dim float vector — pass it to any search-* fn
```

---

## Search

```clojure
(require '[loom.db :as db]
         '[loom.session :as session])

;; Registered tools
(def tools  (unwrap! (db/search-tools ctx q 5)))
;; => [{:name "..." :doc "..." :tags [...] :code "..." :version 1} ...]

;; Global memory facts
(def facts  (unwrap! (db/search-facts ctx q 5)))
;; => [{:content "..." :type "..." :tags [...] :session-id "..."} ...]

;; Session-scoped facts (current session only, no vector needed)
(def hits   (unwrap! (session/search-facts ctx "plain text query" 5)))

;; Indexed document chunks (source file path is in :source)
(def chunks (unwrap! (db/search-chunks ctx q 5)))
;; => [{:source "src/loom/db.clj" :summary "..." :content "..."} ...]

;; Events (findings, conclusions, approvals)
(def events (unwrap! (db/search-events ctx q 10)))
;; => [{:type "finding|conclusion|approval" :content "..." :agent-id "..."} ...]
```

---

## Write findings / conclusions / approvals

```clojure
(db/log-event! ctx {:type       "finding"      ; or "conclusion" "approval" "rejection"
                    :content    "what you found"
                    :session-id (:session-id ctx)
                    :agent-id   "finder"})      ; or "analyzer" "reviewer" etc.
```

---

## Session memory

```clojure
(require '[loom.session :as session])

;; Write a fact scoped to this session
(unwrap! (session/log-fact! ctx "The API uses cursor-based pagination via :next_cursor."))

;; Read it back (plain text, no embed needed)
(unwrap! (session/search-facts ctx "pagination" 3))
```

---

## Global memory (persist across sessions)

```clojure
(require '[loom.memory :as memory])

;; Promote a stable fact to global memory
(unwrap! (memory/promote! ctx "Service X always runs on port 9090."
                          {:tags ["infra"] :type :stable}))

;; Search global memory (requires embed)
(unwrap! (db/search-facts ctx q 5))
```

---

## Register a new tool

```clojure
(require '[loom.tools :as tools])

(defn ^{:doc "One-line description." :tags ["tag1" "tag2"]}
  my-tool [ctx arg]
  (loom.envelope/with-provenance "my-tool" 1
    ;; implementation — must return a plain value, with-provenance wraps it
    (str "result: " arg)))

(unwrap! (tools/register! ctx #'my-tool))
;; Tool is now searchable via db/search-tools and persists across sessions
```

---

## Ingest project files

```clojure
(require '[loom.init :as init])

;; Index all source files in CWD into chunks (idempotent, skips already-indexed)
(unwrap! (init/run! ctx))
```

---

## Fetch / filesystem helpers

```clojure
;; HTTP
(def body (unwrap! (loom.seed.http/fetch ctx "https://example.com/api")))

;; Files
(def text (unwrap! (loom.seed.fs/read-file ctx "/path/to/file")))
(def entries (unwrap! (loom.seed.fs/list-dir ctx "/path/to/dir")))
```

---

## Envelope rules

- **Always** call `unwrap!` on any Loom return value before using it as data.
- On error `unwrap!` throws — catch with `try/catch ex-info` if you want to handle gracefully.
- `(:provenance result-env)` contains `:op`, `:duration-ms`, `:version` for debugging.
