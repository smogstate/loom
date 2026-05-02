#!/usr/bin/env python3
"""Minimal nREPL client that evals Clojure code and returns the result.

Output modes (controlled by LOOM_VERBOSE env var):
  unset / 0  — compact summary line  🔧 op · ✅/❌ · short preview
  1          — full raw output (original behaviour)
"""
import socket, uuid, sys, re, os, json

# ── ANSI colours (disabled if not a tty) ────────────────────────────────────
_tty = sys.stdout.isatty()
def _c(code, s): return f"\033[{code}m{s}\033[0m" if _tty else s
DIM   = lambda s: _c("2",    s)
BOLD  = lambda s: _c("1",    s)
GREEN = lambda s: _c("32",   s)
RED   = lambda s: _c("31",   s)
CYAN  = lambda s: _c("36",   s)
GRAY  = lambda s: _c("90",   s)

VERBOSE = os.environ.get("LOOM_VERBOSE", "0") == "1"

# ── nREPL transport ──────────────────────────────────────────────────────────
def nrepl_eval(code, port=7888, timeout=30):
    s = socket.socket()
    s.settimeout(timeout)
    s.connect(('localhost', port))

    msg_id = str(uuid.uuid4())
    msg = f'd2:id{len(msg_id)}:{msg_id}2:op4:eval4:code{len(code)}:{code}e'
    s.sendall(msg.encode())

    buf = b''
    while True:
        try:
            data = s.recv(8192)
            if not data:
                break
            buf += data
            if b'l4:doneee' in buf or buf.rstrip().endswith(b'l4:doneee'):
                break
            if b'6:statusl4:done' in buf:
                break
        except socket.timeout:
            break
    s.close()

    result_parts = []
    raw = buf.decode(errors='replace')
    pos = 0
    while True:
        m = re.search(r'5:value(\d+):', raw[pos:])
        if not m:
            break
        length = int(m.group(1))
        start = pos + m.end()
        result_parts.append(raw[start:start+length])
        pos = start + length

    errors = []
    for m in re.finditer(r'3:err(\d+):', raw):
        length = int(m.group(1))
        start = m.end()
        errors.append(raw[start:start+length])
    for m in re.finditer(r'2:ex(\d+):', raw):
        length = int(m.group(1))
        start = m.end()
        errors.append(raw[start:start+length])

    if result_parts:
        result = '\n'.join(result_parts)
        result = re.sub(r',?\s*:vector\s*\[[^\]]*\]', '', result)
        return result, errors
    elif errors:
        return None, errors
    return raw, []

# ── Pretty summary ───────────────────────────────────────────────────────────
def _op_from_code(code):
    """Best-effort: extract the innermost op name from the Clojure expression."""
    # e.g. (loom.budget/record! ...) → record!
    m = re.search(r'\([\w./\-!?]+', code)
    if m:
        token = m.group(0)[1:]          # strip leading (
        return token.split('/')[-1]     # keep only local name
    return "eval"

def _summarise(result_str):
    """Return a short human-readable preview of a Clojure result string."""
    if result_str is None:
        return ""
    s = result_str.strip()

    # Envelope: {:ok? true/false ...}
    ok_m  = re.search(r':ok\?\s*(true|false)', s)
    err_m = re.search(r':error\s+\{[^}]*:message\s+"([^"]{0,60})', s)
    res_m = re.search(r':result\s+"([^"]{0,60})"', s)
    op_m  = re.search(r':op\s+"([^"]{0,40})"', s)

    if ok_m:
        ok   = ok_m.group(1) == "true"
        icon = GREEN("✅") if ok else RED("❌")
        op   = CYAN(op_m.group(1)) if op_m else ""
        if not ok and err_m:
            detail = RED(err_m.group(1))
        elif res_m:
            detail = GRAY(f'"{res_m.group(1)}"')
        else:
            detail = ""
        parts = [p for p in [icon, op, detail] if p]
        return "  " + "  ".join(parts)

    # Plain value — truncate
    preview = s[:120].replace('\n', ' ')
    if len(s) > 120:
        preview += "…"
    return "  " + GRAY(preview)

def _type_icon(result_str, errors):
    """Pick a leading emoji based on result shape."""
    if errors:
        return "🔴"
    if result_str is None:
        return "⚪"
    s = result_str.strip()
    if s.startswith("{:ok? true"):  return "🟢"
    if s.startswith("{:ok? false"): return "🔴"
    if s.startswith("["):           return "📋"
    if s.startswith('"'):           return "💬"
    if s in ("nil", "null", ""):    return "⚫"
    if re.match(r'^-?\d', s):       return "🔢"
    if s.startswith("{"):           return "🗂️ "
    return "🔧"

def pretty_print(code, result_str, errors):
    op   = BOLD(_op_from_code(code))
    icon = _type_icon(result_str, errors)
    summary = _summarise(result_str)

    if errors:
        err_preview = errors[-1].strip()[:200]
        print(f"{icon} {op}  {RED(err_preview)}")
    else:
        print(f"{icon} {op}{summary}")

# ── Entry point ──────────────────────────────────────────────────────────────
if __name__ == '__main__':
    code = sys.argv[1] if len(sys.argv) > 1 else '(+ 1 2)'
    result, errors = nrepl_eval(code)

    if VERBOSE:
        if result:
            print(result)
        if errors:
            for e in errors:
                print("ERROR:", e, file=sys.stderr)
    else:
        pretty_print(code, result, errors)
