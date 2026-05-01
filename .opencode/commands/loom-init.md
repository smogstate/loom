---
description: Initialize DuckDB database for Loom in current folder
agent: build
subtask: true
---

Initialize the Loom DuckDB database in the current folder by creating the `.loom` directory with parquet files.

## Step 1 — Verify current directory

```bash
pwd
```

## Step 2 — Initialize DuckDB database

Run this command to create the `.loom` directory and initialize the parquet files:

```bash
clojure -Sdeps '{:deps {loom/loom {:local/root "/home/denis/Projects/loom"}}}' -M -e "(require '[loom.core :as loom]) (loom/start! {:loom-dir \".loom\"}) (println \"DuckDB initialized in\" (str (System/getProperty \"user.dir\") \"/.loom\")) (System/exit 0)"
```

## Step 3 — Confirm

Verify the `.loom` directory was created:

```bash
ls -la .loom/
```

You should see:
- `tools.parquet`
- `chunks.parquet`
- `sessions/` directory

## What this does

- Creates `.loom/` directory in current folder
- Initializes DuckDB with parquet files
- Bootstraps the tool library (19 seed tools)
- Does NOT start nREPL server
- Does NOT index any project files
- Exits immediately after initialization

## Notes

- This ONLY initializes the database structure
- Re-running is safe - will reuse existing files
- To start nREPL server, use `/loom-start`
- To index files, run `(loom.init/run! ctx)` manually from REPL
