#!/usr/bin/env python3
"""Minimal nREPL client that evals Clojure code and returns the result.

Output modes (controlled by LOOM_VERBOSE env var):
  unset / 0  — compact summary line  🔧 op · ✅/❌ · short preview
  1          — full raw output (values + stdout + errors)

The bencode response is decoded with a real bencode parser (not regex)
so that string contents containing `5:value` or `l4:doneee` byte
sequences cannot fool the framing logic.

Errors are surfaced in this priority:
  1. `:err`  — Clojure's printed exception message (most useful)
  2. `:root-ex` / `:ex` — the bare exception class
  3. `:status` containing "error"
"""
import socket, uuid, sys, re, os

# ── ANSI colours (disabled if not a tty) ────────────────────────────────────
_tty = sys.stdout.isatty()
def _c(code, s): return f"\033[{code}m{s}\033[0m" if _tty else s
DIM   = lambda s: _c("2",    s)
BOLD  = lambda s: _c("1",    s)
GREEN = lambda s: _c("32",   s)
RED   = lambda s: _c("31",   s)
CYAN  = lambda s: _c("36",   s)
GRAY  = lambda s: _c("90",   s)
YEL   = lambda s: _c("33",   s)

VERBOSE = os.environ.get("LOOM_VERBOSE", "0") == "1"


# ── Bencode decoder ──────────────────────────────────────────────────────────
class BencodeError(Exception): pass

def _bdecode(data, i=0):
    """Return (value, new_index). Raises BencodeError on malformed input.

    Strings are returned as `bytes`; the caller decodes when it knows the
    field is textual.
    """
    if i >= len(data):
        raise BencodeError("unexpected EOF")
    c = data[i:i+1]
    if c == b'i':
        end = data.index(b'e', i)
        return int(data[i+1:end]), end + 1
    if c == b'l':
        i += 1
        out = []
        while data[i:i+1] != b'e':
            v, i = _bdecode(data, i)
            out.append(v)
        return out, i + 1
    if c == b'd':
        i += 1
        out = {}
        while data[i:i+1] != b'e':
            k, i = _bdecode(data, i)
            v, i = _bdecode(data, i)
            # Keys are always byte strings; decode to str for ergonomics.
            out[k.decode('utf-8', 'replace') if isinstance(k, bytes) else k] = v
        return out, i + 1
    if c.isdigit():
        colon = data.index(b':', i)
        length = int(data[i:colon])
        start = colon + 1
        return data[start:start+length], start + length
    raise BencodeError(f"unexpected byte {c!r} at {i}")


def _bdecode_stream(buf):
    """Yield successive top-level bencode values from a byte buffer.

    Returns (values, leftover_bytes). Used for streaming reads where the
    server may send multiple framed messages back-to-back.
    """
    values, i = [], 0
    while i < len(buf):
        try:
            v, i = _bdecode(buf, i)
            values.append(v)
        except (BencodeError, ValueError):
            break
    return values, buf[i:]


# ── nREPL transport ──────────────────────────────────────────────────────────
def _bstr(s):
    """Encode a Python str/bytes as a bencoded byte string."""
    b = s.encode('utf-8') if isinstance(s, str) else s
    return f"{len(b)}:".encode() + b


def _benc_msg(d):
    parts = [b'd']
    for k, v in d.items():
        parts.append(_bstr(k))
        parts.append(_bstr(v))
    parts.append(b'e')
    return b''.join(parts)


def nrepl_eval(code, port=7888, timeout=30):
    s = socket.socket()
    s.settimeout(timeout)
    s.connect(('localhost', port))

    msg_id = str(uuid.uuid4())
    s.sendall(_benc_msg({'id': msg_id, 'op': 'eval', 'code': code}))

    buf = b''
    messages = []
    done = False
    while not done:
        try:
            data = s.recv(8192)
        except socket.timeout:
            break
        if not data:
            break
        buf += data
        msgs, buf = _bdecode_stream(buf)
        for m in msgs:
            messages.append(m)
            status = m.get('status')
            if isinstance(status, list) and any(
                (x == b'done' if isinstance(x, bytes) else x == 'done')
                for x in status
            ):
                done = True
    s.close()

    values, errs, outs, ex = [], [], [], None
    for m in messages:
        if 'value' in m:
            v = m['value']
            values.append(v.decode('utf-8', 'replace') if isinstance(v, bytes) else str(v))
        if 'err' in m:
            v = m['err']
            errs.append(v.decode('utf-8', 'replace') if isinstance(v, bytes) else str(v))
        if 'out' in m:
            v = m['out']
            outs.append(v.decode('utf-8', 'replace') if isinstance(v, bytes) else str(v))
        if 'ex' in m and ex is None:
            v = m['ex']
            ex = v.decode('utf-8', 'replace') if isinstance(v, bytes) else str(v)
        if 'root-ex' in m and ex is None:
            v = m['root-ex']
            ex = v.decode('utf-8', 'replace') if isinstance(v, bytes) else str(v)

    return {
        'values': values,
        'errs':   [e for e in (s.strip() for s in errs) if e],
        'outs':   outs,
        'ex':     ex,
    }


# ── Pretty summary ───────────────────────────────────────────────────────────
def _op_from_code(code):
    """Best-effort: extract the *last* top-level op name from the code.

    For multi-form input (require, def, then the real call), the last form
    is usually the one whose result the caller cares about.
    """
    matches = re.findall(r'\(([\w./\-!?]+)', code)
    if matches:
        return matches[-1].split('/')[-1]
    return "eval"


def _summarise(result_str):
    """Return a short human-readable preview of a Clojure result string."""
    if result_str is None:
        return ""
    s = result_str.strip()

    ok_m  = re.search(r':ok\?\s*(true|false)', s)
    err_m = re.search(r':error\s+\{[^}]*:message\s+"([^"]{0,80})', s)
    res_m = re.search(r':result\s+"([^"]{0,80})"', s)
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

    preview = s[:160].replace('\n', ' ')
    if len(s) > 160:
        preview += "…"
    return "  " + GRAY(preview)


def _type_icon(result_str, has_errors):
    if has_errors:
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


def _best_error(errs, ex):
    """Choose the most informative error string.

    `:err` (printed exception with message + stack) is always more useful
    than the bare `:ex` class name. We prefer the longest non-empty `:err`
    since some servers split the trace across multiple frames.
    """
    if errs:
        return max(errs, key=len)
    return ex


def pretty_print(code, result):
    values   = result['values']
    errs     = result['errs']
    ex       = result['ex']
    last_val = values[-1] if values else None
    has_err  = bool(errs or ex)

    op   = BOLD(_op_from_code(code))
    icon = _type_icon(last_val, has_err)

    if has_err:
        msg = _best_error(errs, ex) or "(unknown error)"
        # First non-empty line is usually the message; keep it short.
        first_line = next((ln for ln in msg.splitlines() if ln.strip()), msg)
        preview = first_line.strip()[:240]
        print(f"{icon} {op}  {RED(preview)}")
        # If we have partial successful values before the failure, hint at them.
        if values:
            n = len(values)
            plural = "s" if n != 1 else ""
            print(f"   {DIM(f'({n} form{plural} succeeded before error)')}")
    else:
        summary = _summarise(last_val)
        print(f"{icon} {op}{summary}")
        if len(values) > 1:
            print(f"   {DIM(f'({len(values)} forms evaluated; showing last)')}")


# ── Entry point ──────────────────────────────────────────────────────────────
if __name__ == '__main__':
    code = sys.argv[1] if len(sys.argv) > 1 else '(+ 1 2)'
    result = nrepl_eval(code)

    if VERBOSE:
        for v in result['values']:
            print(v)
        for o in result['outs']:
            sys.stdout.write(o)
        for e in result['errs']:
            print("ERROR:", e, file=sys.stderr)
        if result['ex']:
            print("EX:", result['ex'], file=sys.stderr)
    else:
        pretty_print(code, result)
