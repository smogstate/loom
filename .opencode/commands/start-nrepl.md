---
description: Start Loom nREPL server on port 7888
agent: build
subtask: true
---

Start the Loom nREPL server to enable remote interaction with Loom.

## Step 1 — Check if nREPL is already running

Test if port 7888 is already in use:

```bash
clojure -M -e "(import '[java.net Socket]) (try (let [s (Socket. \"localhost\" 7888)] (.close s) (println \"Port 7888 is already in use. Kill the existing process first.\") (System/exit 1)) (catch Exception e (println \"Port 7888 is available\")))"
```

If port is in use, kill the existing process first:
```bash
kill -9 $(lsof -ti:7888)
```

## Step 2 — Start nREPL server

Start the Loom nREPL server in the background:

```bash
cd /home/denis/Projects/loom && clojure -M:dev -e "(require '[loom.core :as loom]) (def ctx (loom/start!)) (println \"nREPL server started on port 7888\") @(promise)" &
```

Wait a few seconds for the server to start.

## Step 3 — Verify nREPL is running

Test the connection:

```bash
cd /home/denis/Projects/loom && python3 loom_eval.py "(+ 1 2)"
```

You should see `3` as the output.

## What this does

- Starts Loom with nREPL server on port 7888
- Runs in the background
- Creates `.nrepl-port` file in `/home/denis/Projects/loom/`
- Loads all Loom tools and initializes the database
- Keeps running until manually killed

## Notes

- The server runs from `/home/denis/Projects/loom/` directory
- To stop: `kill -9 $(lsof -ti:7888)`
- To query via nREPL: `python3 /home/denis/Projects/loom/loom_eval.py "your-code"`
- The server will use `.loom/` in the loom project directory by default
