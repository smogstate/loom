---
name: loom
description: How to connect to Loom (semantic memory + tool registry), call its API over nREPL, and correctly unwrap envelope results. Load this before any Loom operation.
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
- `db`, `session`, `memory`, `tools`, `embedder`, `blob`, `init` — all aliased

---

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

## Named helpers

Use these instead of writing raw Loom calls. Each expands to the exact bash command to run.

### loom/search

Search session memory, tools, facts, and chunks for a query.

```bash
# Session memory (no embed needed)
python3 ~/Projects/loom/loom_eval.py '(unwrap! (session/search-facts ctx "QUERY" 5))'

# Tools
python3 ~/Projects/loom/loom_eval.py '(let [q (unwrap! (embedder/embed ctx "QUERY"))] (unwrap! (db/search-tools ctx q 5)))'

# Global facts
python3 ~/Projects/loom/loom_eval.py '(let [q (unwrap! (embedder/embed ctx "QUERY"))] (unwrap! (db/search-facts ctx q 5)))'

# Source chunks
python3 ~/Projects/loom/loom_eval.py '(let [q (unwrap! (embedder/embed ctx "QUERY"))] (unwrap! (db/search-chunks ctx q 5)))'

# Events (findings, conclusions, approvals)
python3 ~/Projects/loom/loom_eval.py '(let [q (unwrap! (embedder/embed ctx "QUERY"))] (unwrap! (db/search-events ctx q 10)))'
```

---

### loom/log-fact!

Write a discovery to session memory.

```bash
python3 ~/Projects/loom/loom_eval.py '(unwrap! (session/log-fact! ctx "FACT"))'
```

---

### loom/promote!

Promote a stable fact to global memory (persists across sessions).

```bash
python3 ~/Projects/loom/loom_eval.py '(unwrap! (memory/promote! ctx "FACT" {:tags ["TAG"] :type :stable}))'
```

---

### loom/log-finding!

Log a finding event (call after each major discovery).

```bash
python3 ~/Projects/loom/loom_eval.py '(db/log-event! ctx {:type "finding" :content "CONTENT" :session-id (:session-id ctx) :agent-id "AGENT_ID" :goal-id "GOAL_ID"})'
```

Omit `:goal-id` if no active goal.

---

### loom/log-conclusion!

Log a conclusion event.

```bash
python3 ~/Projects/loom/loom_eval.py '(db/log-event! ctx {:type "conclusion" :content "CONTENT" :session-id (:session-id ctx) :agent-id "loom" :goal-id "GOAL_ID"})'
```

---

### loom/log-approval!

Log a reviewer approval.

```bash
python3 ~/Projects/loom/loom_eval.py '(db/log-event! ctx {:type "approval" :content "CONTENT" :session-id (:session-id ctx) :agent-id "reviewer" :goal-id "GOAL_ID"})'
```

---

### loom/log-rejection!

Log a reviewer rejection.

```bash
python3 ~/Projects/loom/loom_eval.py '(db/log-event! ctx {:type "rejection" :content "CONTENT" :session-id (:session-id ctx) :agent-id "reviewer" :goal-id "GOAL_ID"})'
```

---

### loom/log-failure!

Log a task failure event (use before retrying a failed subagent task).

```bash
python3 ~/Projects/loom/loom_eval.py '(db/log-event! ctx {:type "failure" :content "CONTENT" :session-id (:session-id ctx) :agent-id "AGENT_ID" :goal-id "GOAL_ID"})'
```

Omit `:goal-id` if no active goal.

---

### loom/active-goal

Get the current active goal (returns nil if none).

```bash
python3 ~/Projects/loom/loom_eval.py '(unwrap! (loom.goals/active ctx))'
```

---

### loom/create-goal!

Create a parent goal and return its id into `gid`.

```bash
python3 ~/Projects/loom/loom_eval.py '(def gid (unwrap! (loom.goals/create-goal! ctx {:title "TITLE" :description "DESCRIPTION" :status "active"})))'
```

---

### loom/create-subgoal!

Create a sub-goal linked to a parent.

```bash
python3 ~/Projects/loom/loom_eval.py '(unwrap! (loom.goals/create-goal! ctx {:title "TITLE" :parent-id gid :status "open"}))'
```

---

### loom/close-goal!

Mark a goal done.

```bash
python3 ~/Projects/loom/loom_eval.py '(unwrap! (loom.goals/update-status! ctx gid "done"))'
```

---

### loom/register-tool!

Define and register a new reusable tool.

```bash
python3 ~/Projects/loom/loom_eval.py '
(defn ^{:doc "DESCRIPTION" :tags ["TAG"]}
  TOOL_NAME [ctx ARG]
  (loom.envelope/with-provenance "TOOL_NAME" 1
    BODY))
(unwrap! (tools/register! ctx #'"'"'TOOL_NAME))
'
```

---

## Envelope rules

- Always call `unwrap!` on any Loom return value before using it as data.
- On error `unwrap!` throws — the error message describes what failed.
- `(:provenance result-env)` contains `:op`, `:duration-ms`, `:version` for debugging.
