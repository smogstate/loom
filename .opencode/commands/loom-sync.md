---
description: Sync agents, commands, and skills from loom repo into Projects/.opencode
agent: build
subtask: true
---

Sync the canonical `.opencode/` files from the loom repo into `Projects/.opencode/`.

The loom repo at `/home/denis/Projects/loom/.opencode/` is the single source of truth.

## Step 1 — copy agents

```bash
cp /home/denis/Projects/loom/.opencode/agents/*.md /home/denis/Projects/.opencode/agents/
```

## Step 2 — copy commands

```bash
cp /home/denis/Projects/loom/.opencode/commands/*.md /home/denis/Projects/.opencode/commands/
```

## Step 3 — copy skills

```bash
cp /home/denis/Projects/loom/.opencode/skills/loom/SKILL.md /home/denis/Projects/.opencode/skills/loom/SKILL.md
```

## Step 4 — confirm

Report which files were synced and remind the user:
- Source of truth: `/home/denis/Projects/loom/.opencode/`
- Always edit files there, then run `/loom-sync` to propagate
