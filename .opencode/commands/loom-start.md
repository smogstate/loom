---
description: Start Loom nREPL server in tmux for the current project
agent: build
subtask: true
---

Start the Loom nREPL server in a tmux session from the current directory.

## Step 1 — check for .loom/

```bash
ls .loom/tools.parquet 2>/dev/null && echo "EXISTS" || echo "NOT FOUND"
```

If `.loom/tools.parquet` does not exist, tell the user to run `/loom-init` first and stop.

## Step 2 — check if Loom is already running

```bash
tmux has-session -t loom 2>/dev/null && echo "RUNNING" || echo "STOPPED"
```

If already running, ask the user if they want to restart it. If yes, kill the session first:

```bash
tmux kill-session -t loom
```

## Step 3 — start Loom nREPL in tmux

Start Loom in a tmux session from the CURRENT directory:

```bash
CURRENT_DIR=$(pwd)
tmux new-session -d -s loom -x 220 -y 50 "cd $CURRENT_DIR && clojure -Sdeps '{:deps {loom/loom {:local/root \"/home/denis/Projects/loom\"}}}' -M -e '(require (quote [loom.core :as loom])) (def ctx (loom/start! {:loom-dir \".loom\"})) (println :loom/ready) @(promise)'"
```

Wait 5 seconds then check it started:

```bash
sleep 5 && tmux capture-pane -t loom -p | tail -10
```

## Step 4 — verify nREPL connection

Test the nREPL connection:

```bash
cd /home/denis/Projects/loom && python3 loom_eval.py "(+ 1 2)"
```

Should output `3`.

## Step 5 — confirm

Report to the user:
- Loom nREPL server is running in tmux session `loom`
- Running from directory: `$(pwd)`
- Using `.loom/` in the current directory
- To attach: `tmux attach -t loom`
- To detach: Press `Ctrl+b` then `d`
- nREPL is available on port 7888
- To stop: `tmux kill-session -t loom`

## Notes

- Loom runs from the CURRENT directory where you invoked the command
- Uses `.loom/` in the current directory (not the loom project directory)
- Ollama is optional - if not running, embeddings will be zero vectors
