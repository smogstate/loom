#!/usr/bin/env python3
"""
Loom Agent Performance Dashboard — pretty CLI output.
Usage: python3 ~/Projects/loom/loom_dashboard.py
"""

import subprocess
import json
import sys
import os

EVAL = os.path.expanduser("~/Projects/loom/loom_eval.py")

EXPR = """
(require '[loom.metrics :as m])
(cheshire.core/generate-string (loom.envelope/unwrap! (m/dashboard-data ctx)))
"""

# ── ANSI colours ──────────────────────────────────────────────────────────────
RESET  = "\033[0m"
BOLD   = "\033[1m"
CYAN   = "\033[36m"
GREEN  = "\033[32m"
YELLOW = "\033[33m"
RED    = "\033[31m"
DIM    = "\033[2m"

def colour(s, c): return f"{c}{s}{RESET}"
def bold(s):      return colour(s, BOLD)
def header(s):    print(f"\n{BOLD}{CYAN}{s}{RESET}")
def rule():       print(DIM + "─" * 60 + RESET)

# ── Table printer ─────────────────────────────────────────────────────────────
def table(rows, cols=None):
    if not rows:
        print(DIM + "  (no data)" + RESET)
        return
    if cols is None:
        cols = list(rows[0].keys())
    # compute widths
    widths = {c: max(len(str(c)), max(len(str(r.get(c, ""))) for r in rows)) for c in cols}
    fmt = "  " + "  ".join(f"{{:<{widths[c]}}}" for c in cols)
    # header row
    print(DIM + fmt.format(*[c.upper() for c in cols]) + RESET)
    print(DIM + "  " + "  ".join("-" * widths[c] for c in cols) + RESET)
    for r in rows:
        vals = [str(r.get(c, "")) for c in cols]
        print(fmt.format(*vals))

# ── Scalar printer ────────────────────────────────────────────────────────────
def kv(d):
    if not d:
        print(DIM + "  (no data)" + RESET)
        return
    for k, v in d.items():
        print(f"  {DIM}{k}{RESET}  {bold(str(v))}")

# ── Fetch data ────────────────────────────────────────────────────────────────
def fetch():
    result = subprocess.run(
        ["python3", EVAL, EXPR.strip()],
        capture_output=True, text=True
    )
    if result.returncode != 0:
        print(RED + "ERROR: " + result.stderr + RESET, file=sys.stderr)
        sys.exit(1)
    # loom_eval.py prints "nil\n" for side effects then the result on the last line
    lines = [l for l in result.stdout.strip().splitlines() if l and l != "nil"]
    if not lines:
        print(RED + "ERROR: no output from loom_eval" + RESET, file=sys.stderr)
        sys.exit(1)
    out = lines[-1].strip()
    # nREPL returns strings with surrounding quotes — unwrap
    if out.startswith('"'):
        out = json.loads(out)
    return json.loads(out)

# ── Render ────────────────────────────────────────────────────────────────────
def render(d):
    print(f"\n{BOLD}{CYAN}{'═' * 60}{RESET}")
    print(f"{BOLD}{CYAN}  LOOM AGENT PERFORMANCE DASHBOARD{RESET}")
    print(f"{BOLD}{CYAN}{'═' * 60}{RESET}")

    # Goal completion
    header("🎯  Goal Completion")
    rule()
    gc = d.get("goal-completion", {})
    done  = gc.get("done", 0)
    total = gc.get("total", 0)
    rate  = gc.get("rate", 0)
    bar_len = 30
    filled = int(bar_len * (rate or 0) / 100)
    bar = GREEN + "█" * filled + DIM + "░" * (bar_len - filled) + RESET
    print(f"  {bar}  {bold(str(rate)+'%')}  ({done}/{total} goals done)")

    ttc = d.get("time-to-completion", {})
    if ttc.get("avg_ms"):
        avg_s = round(ttc["avg_ms"] / 1000, 1)
        min_s = round((ttc.get("min_ms") or 0) / 1000, 1)
        max_s = round((ttc.get("max_ms") or 0) / 1000, 1)
        print(f"  avg {bold(str(avg_s)+'s')}  min {min_s}s  max {max_s}s")

    # Event breakdown
    header("📋  Event Breakdown")
    rule()
    table(d.get("event-breakdown", []), ["type", "agent_id", "n"])

    # Failure rate
    header("❌  Failure Rate by Agent")
    rule()
    fr = d.get("failure-rate", [])
    for row in fr:
        pct = row.get("failure_pct", 0) or 0
        c = RED if pct > 10 else (YELLOW if pct > 0 else GREEN)
        print(f"  {row['agent_id']:<20} {colour(str(pct)+'%', c)}  ({row['failures']}/{row['total']})")

    gwf = d.get("goals-with-failures", {})
    if gwf.get("total_failures"):
        print(f"\n  {gwf['total_failures']} total failures across {gwf['goals_with_failures']} goals")

    # Usage summary
    header("💰  Usage Summary")
    rule()
    table(d.get("usage-summary", []),
          ["agent_id", "op", "calls", "tokens_in", "tokens_out", "usd_total", "avg_ms", "errors"])

    cpg = d.get("cost-per-goal", {})
    if cpg.get("usd_per_goal"):
        print(f"\n  Cost per completed goal: {bold('$'+str(cpg['usd_per_goal']))}")

    # Slowest ops
    header("⚡  Slowest Ops (top 10)")
    rule()
    table(d.get("slowest-ops", []), ["op", "agent_id", "calls", "avg_ms", "max_ms"])

    # Search calls
    header("🔍  Search Call Counts")
    rule()
    table(d.get("search-call-counts", []), ["session_id", "op", "calls"])

    print(f"\n{DIM}{'─' * 60}{RESET}\n")

# ── Main ──────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    try:
        data = fetch()
        render(data)
    except KeyboardInterrupt:
        pass
