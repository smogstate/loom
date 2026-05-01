---
description: Stop the Loom nREPL server running in tmux
agent: build
subtask: true
---

Stop the Loom nREPL server by killing the tmux session.

## Step 1 — Check if Loom is running

```bash
tmux has-session -t loom 2>/dev/null && echo "RUNNING" || echo "NOT RUNNING"
```

If not running, inform the user that Loom is not running and stop.

## Step 2 — Kill the tmux session

```bash
tmux kill-session -t loom
```

## Step 3 — Verify it stopped

Wait a moment and check again:

```bash
sleep 1 && tmux has-session -t loom 2>/dev/null && echo "STILL RUNNING" || echo "STOPPED"
```

## Step 4 — Verify nREPL port is free

Check if port 7888 is still in use:

```bash
clojure -M -e "(import '[java.net Socket]) (try (let [s (Socket. \"localhost\" 7888)] (.close s) (println \"Port 7888 is still in use\")) (catch Exception e (println \"Port 7888 is free\")))"
```

## Step 5 — Confirm

Report to the user:
- Loom nREPL server has been stopped
- tmux session `loom` has been terminated
- Port 7888 is now free
- To start again: `/loom-start`

## Notes

- This only stops the nREPL server, it doesn't delete the `.loom/` database
- All data in `.loom/` is preserved
- You can restart Loom anytime with `/loom-start`
