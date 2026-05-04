# Loom Agents — Context Window & Quality Improvement Plan

**Status:** DRAFT  
**Date:** 2026-05-03  
**Scope:** All agent files in `/home/denis/Projects/.opencode/agents/` and `/home/denis/Projects/loom/.opencode/agents/`

---

## Overview

Review of all 5 loom agents (finder, analyzer, reviewer, coder, loom/orchestrator) across both the global workspace and loom-local locations. Focus: context window cleanliness, correctness of instructions, and consistency between the two copies.

---

## Issues Found

### 🔴 High Priority

#### 1. `loom.md` (global) — Missing `APPROVED_WITH_REVISIONS` verdict

**File:** `/home/denis/Projects/.opencode/agents/loom.md` lines 163–167

Step 4 only handles two verdicts:
```
Parse the reviewer's first line for `VERDICT: APPROVED` or `VERDICT: REJECTED`.
- Approved: `loom/log-approval!`
- Rejected: `loom/log-rejection!`
```

The loom-local `loom.md` has a three-verdict table including `VERDICT: APPROVED_WITH_REVISIONS` with the instruction to apply revisions before dispatching `@coder`. If the reviewer returns `APPROVED_WITH_REVISIONS`, the global orchestrator has no handling for it.

**Fix:** Replace Step 4 with the three-verdict table from loom-local `loom.md`:

```markdown
Parse the reviewer's first line for one of three verdicts:

| First line | Action |
|---|---|
| `VERDICT: APPROVED` | `loom/log-approval!` — proceed to implementation |
| `VERDICT: APPROVED_WITH_REVISIONS` | `loom/log-approval!` — apply the listed required revisions before dispatching `@coder` |
| `VERDICT: REJECTED` | `loom/log-rejection!` — return the defects list to `@analyzer` for repair, then re-review |
```

---

#### 2. `loom.md` (global) — Wrong status string in Step 0 early-return

**File:** `/home/denis/Projects/.opencode/agents/loom.md` line 65

```
> If returning early (Step 1 cache hit), close the goal with status `completed` before returning.
```

The `loom/close-goal!` helper calls `loom.goals/update-status!` which expects `"done"`. The loom-local version correctly uses `"done"`. Using `"completed"` silently passes a wrong status.

**Fix:** Change `completed` → `"done"`.

---

### 🟠 Medium Priority

#### 3. `analyzer.md` (global) — Missing pre-conclusion checklist

**File:** `/home/denis/Projects/.opencode/agents/analyzer.md`

The loom-local analyzer has a rigorous 5-step checklist (lines 46–57) requiring `loom.seed.fs/search-source` before citing any code. The global version is missing it entirely. Without this guard, the analyzer can hallucinate line numbers and wrong signatures, causing reviewer rejections.

**Fix:** Add the following section before `## Self-repair`:

```markdown
## Pre-conclusion checklist

Before writing any conclusion or citing any code, you MUST:

1. **Locate** — use `loom.seed.fs/search-source` to find the exact file and line number:
   ```bash
   python3 ~/Projects/loom/loom_eval.py '(unwrap! (loom.seed.fs/search-source ctx "SYMBOL_OR_PATTERN" 5))'
   ```
2. **Read** — use the Read tool to view the actual lines around the match
3. **Verify signature** — confirm the function name, arity, and return type match what you plan to cite
4. **Verify line numbers** — the line numbers in your conclusion must match what Read returned
5. **Then conclude** — only after steps 1–4 are complete

If you skip any step and cite wrong line numbers or signatures, the reviewer will reject your conclusion.
```

---

#### 4. `finder.md` (global) — Missing nREPL-unavailable fallback

**File:** `/home/denis/Projects/.opencode/agents/finder.md`

The loom-local finder (lines 51–52) has a graceful fallback when nREPL is unreachable. The global version ends abruptly at `FOUND: nothing` with no guidance for connection failures.

**Fix:** Add at end of Output format section:

```markdown
If the Loom nREPL is unreachable (connection refused on port 7888), return:
`FOUND: nothing — nREPL unavailable (port 7888 not responding)`
```

---

#### 5. `reviewer.md` (global) — Abbreviated migration safety checklist item

**File:** `/home/denis/Projects/.opencode/agents/reviewer.md` line 37

Global version:
```
5. **Migration safety** — schema changes backward compatible?
```

Loom-local version has the full guidance:
```
5. **Migration safety** — are schema changes backward compatible? Check `resources/migrations/` for new column/table additions; verify no existing columns are dropped or renamed without a migration; confirm DuckDB schema changes use `ALTER TABLE … ADD COLUMN IF NOT EXISTS` or equivalent safe patterns.
```

**Fix:** Expand checklist item 5 to match the loom-local version.

---

### 🟡 Low Priority

#### 6. `router.md` — Orphaned conclusion event, superseded by `loom.md`

**File:** `/home/denis/Projects/.opencode/agents/router.md`

- The router logs a `conclusion` event with `agent-id "router"` but never creates a goal, producing orphaned events with no `:goal-id`.
- The router's dispatch table is a strict subset of `loom.md`'s and lacks `[plan]`, `[implement]`, `[fix]` fast-path tags.
- `loom.md` (primary mode) supersedes the router for all routing decisions.

**Fix options (pick one):**
- **Retire:** Delete `router.md` and update any references to it.
- **Demote:** Add a header note: `> Lightweight fallback only. Prefer the loom orchestrator for all non-trivial tasks.` Remove the `log-event!` call or make it conditional on a goal existing.

---

#### 7. All subagents — Full skill load bloats context

**Affected:** `finder.md`, `analyzer.md`, `reviewer.md` (both locations)

Every subagent starts with `Load skill: loom`, which injects the full SKILL.md (~122 lines) into their context. The skill contains the full nREPL transport docs, all named helpers, and envelope rules. Most subagents only use 2–3 of those helpers.

**Fix:** Create a `loom-minimal` skill (or inline reference block) per agent containing only the commands that agent is permitted to call. The orchestrator (`loom.md`) keeps the full skill load.

Example for `finder.md`:
```markdown
## Loom quick-reference (finder)

```bash
# Search session memory
python3 ~/Projects/loom/loom_eval.py '(unwrap! (session/search-facts ctx "QUERY" 5))'

# Search tools / facts / chunks
python3 ~/Projects/loom/loom_eval.py '(let [q (unwrap! (embedder/embed ctx "QUERY"))] (unwrap! (db/search-chunks ctx q 5)))'

# Write a session fact
python3 ~/Projects/loom/loom_eval.py '(unwrap! (session/log-fact! ctx "FACT"))'
```
```

---

## Fix Summary Table

| Priority | File(s) | Fix |
|---|---|---|
| 🔴 High | global `loom.md` | Add `APPROVED_WITH_REVISIONS` to Step 4 verdict table |
| 🔴 High | global `loom.md` | Fix `completed` → `"done"` in Step 0 early-return |
| 🟠 Medium | global `analyzer.md` | Add pre-conclusion checklist section |
| 🟠 Medium | global `finder.md` | Add nREPL-unavailable fallback message |
| 🟠 Medium | global `reviewer.md` | Expand checklist item 5 with full migration safety guidance |
| 🟡 Low | global `router.md` | Retire or demote; remove orphaned `log-event!` |
| 🟡 Low | all subagents | Replace full `Load skill: loom` with per-agent minimal inline reference |

---

## Files to Edit

```
/home/denis/Projects/.opencode/agents/loom.md       — fixes 1, 2
/home/denis/Projects/.opencode/agents/analyzer.md   — fix 3
/home/denis/Projects/.opencode/agents/finder.md     — fix 4
/home/denis/Projects/.opencode/agents/reviewer.md   — fix 5
/home/denis/Projects/.opencode/agents/router.md     — fix 6
```

Both global and loom-local copies should be kept in sync after applying fixes.
