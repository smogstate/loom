#!/usr/bin/env python3
"""FastAPI server bridging HTTP requests to Loom nREPL calls.

Session-stack contract (plan/session-stack.md v4):
  - Reads accept ?session_ids=sid1,sid2 (CSV) and ?strict=true.
  - When session_ids is omitted, the backend default-stack (current
    ctx session + GLOBAL_SID) is used.
  - GLOBAL_SID is the canonical zero-UUID; the legacy magic string
    "global" is no longer accepted.
"""

import os
import re
import socket
import threading
import uuid
from typing import Any, Optional

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse

# ── constants ────────────────────────────────────────────────────────────────

GLOBAL_SID = "00000000-0000-0000-0000-000000000000"


# ── nREPL client ─────────────────────────────────────────────────────────────

def nrepl_eval(code: str, port: int = 7888, timeout: int = 30):
    """Evaluate Clojure code via nREPL. Returns (result_str, errors)."""
    s = socket.socket()
    s.settimeout(timeout)
    try:
        s.connect(("localhost", port))
    except OSError as exc:
        return None, [str(exc)]

    msg_id = str(uuid.uuid4())
    msg = f"d2:id{len(msg_id)}:{msg_id}2:op4:eval4:code{len(code)}:{code}e"
    s.sendall(msg.encode())

    buf = b""
    while True:
        try:
            data = s.recv(8192)
            if not data:
                break
            buf += data
            if b"l4:doneee" in buf or buf.rstrip().endswith(b"l4:doneee"):
                break
            if b"6:statusl4:done" in buf:
                break
        except socket.timeout:
            break
    s.close()

    result_parts = []
    raw = buf.decode(errors="replace")
    pos = 0
    while True:
        m = re.search(r"5:value(\d+):", raw[pos:])
        if not m:
            break
        length = int(m.group(1))
        start = pos + m.end()
        result_parts.append(raw[start : start + length])
        pos = start + length

    errors = []
    for m in re.finditer(r"3:err(\d+):", raw):
        length = int(m.group(1))
        start = m.end()
        errors.append(raw[start : start + length])
    for m in re.finditer(r"2:ex(\d+):", raw):
        length = int(m.group(1))
        start = m.end()
        errors.append(raw[start : start + length])

    if result_parts:
        result = "\n".join(result_parts)
        result = re.sub(r",?\s*:vector\s*\[[^\]]*\]", "", result)
        return result, errors
    elif errors:
        return None, errors
    return raw, []


_nrepl_lock = threading.Lock()
_search_lock = threading.Lock()  # separate lock so search never waits on subgraph


def nrepl_eval_locked(code: str) -> tuple[Optional[str], list]:
    """Thread-safe nREPL eval (for heavy ops: subgraph, entity)."""
    with _nrepl_lock:
        return nrepl_eval(code)


def nrepl_eval_search(code: str) -> tuple[Optional[str], list]:
    """Separate lock for search so it never queues behind subgraph calls."""
    with _search_lock:
        return nrepl_eval(code)


def nrepl_available() -> bool:
    """Check if nREPL is reachable."""
    try:
        s = socket.socket()
        s.settimeout(2)
        s.connect(("localhost", 7888))
        s.close()
        return True
    except OSError:
        return False


# ── Clojure literal helpers ──────────────────────────────────────────────────

def _clj_str(s: str) -> str:
    """Escape a Python string for embedding inside a Clojure string literal."""
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'


def _clj_vec(strs: list[str]) -> str:
    """Render a Python list[str] as a Clojure vector of string literals.
       Empty list → `[]`."""
    if not strs:
        return "[]"
    return "[" + " ".join(_clj_str(x) for x in strs) + "]"


def _parse_session_ids(csv: Optional[str]) -> list[str]:
    """Parse `?session_ids=a,b,c` query string into a list, dropping blanks."""
    if not csv:
        return []
    return [s.strip() for s in csv.split(",") if s.strip()]


def _stack_clj(session_ids: list[str], strict: bool) -> str:
    """Build a normalised Clojure read-stack literal — for callers that
       need an already-normalised positional vector (e.g. `db/db-get-entity`).

       NOTE: do NOT use this for `graph/*` public fns; those re-normalise
       internally and need the raw `:session-ids` + `:strict?` forwarded
       so they apply the global-tail rule exactly once. See `_stack_opts`.
    """
    return f"(loom.scope/normalize-stack {_clj_vec(session_ids)} :strict? {str(strict).lower()})"


def _stack_opts(session_ids: list[str], strict: bool) -> str:
    """Build the `:session-ids […] :strict? bool` opt fragment for graph fns.

       Graph public wrappers normalise their input themselves; double-
       normalisation (once here, once there) silently re-appends GLOBAL_SID
       because the second pass loses the strict flag.
    """
    return f":session-ids {_clj_vec(session_ids)} :strict? {str(strict).lower()}"


# ── EDN parser ────────────────────────────────────────────────────────────────

def _parse_edn(text: str) -> Any:
    """Recursive EDN-to-Python converter.
       Handles maps, vectors, strings, numbers, nil, true/false, #uuid, keywords."""
    text = text.strip()
    if not text:
        return None
    return _edn_value(text, 0)[0]


def _edn_value(s: str, pos: int) -> tuple[Any, int]:
    pos = _skip_ws(s, pos)
    if pos >= len(s):
        return None, pos

    ch = s[pos]

    if s[pos:pos+3] == "nil":
        return None, pos + 3
    if s[pos:pos+4] == "true":
        return True, pos + 4
    if s[pos:pos+5] == "false":
        return False, pos + 5

    if s[pos:pos+5] == '#uuid':
        pos2 = _skip_ws(s, pos + 5)
        val, pos3 = _edn_string(s, pos2)
        return val, pos3

    if ch == '#':
        end = pos + 1
        while end < len(s) and s[end] not in (' ', '\t', '\n', '\r', '{', '[', '(', '"'):
            end += 1
        pos2 = _skip_ws(s, end)
        return _edn_value(s, pos2)

    if ch == '"':
        return _edn_string(s, pos)

    if ch == ':':
        end = pos + 1
        while end < len(s) and s[end] not in (' ', '\t', '\n', '\r', ',', '}', ']', ')'):
            end += 1
        return s[pos + 1 : end], end

    if ch == '{':
        return _edn_map(s, pos)
    if ch == '[':
        return _edn_vector(s, pos)
    if ch == '(':
        return _edn_list(s, pos)

    end = pos
    while end < len(s) and s[end] not in (' ', '\t', '\n', '\r', ',', '}', ']', ')'):
        end += 1
    token = s[pos:end]
    try:
        return int(token), end
    except ValueError:
        pass
    try:
        return float(token), end
    except ValueError:
        pass
    return token, end


def _skip_ws(s: str, pos: int) -> int:
    while pos < len(s) and s[pos] in (' ', '\t', '\n', '\r', ','):
        pos += 1
    return pos


def _edn_string(s: str, pos: int) -> tuple[str, int]:
    assert s[pos] == '"'
    pos += 1
    result = []
    while pos < len(s):
        ch = s[pos]
        if ch == '\\':
            pos += 1
            esc = s[pos] if pos < len(s) else ''
            result.append({'n': '\n', 't': '\t', 'r': '\r', '"': '"', '\\': '\\'}.get(esc, esc))
        elif ch == '"':
            return ''.join(result), pos + 1
        else:
            result.append(ch)
        pos += 1
    return ''.join(result), pos


def _edn_map(s: str, pos: int) -> tuple[dict, int]:
    assert s[pos] == '{'
    pos += 1
    result = {}
    while True:
        pos = _skip_ws(s, pos)
        if pos >= len(s) or s[pos] == '}':
            return result, pos + 1
        key, pos = _edn_value(s, pos)
        pos = _skip_ws(s, pos)
        val, pos = _edn_value(s, pos)
        result[key] = val


def _edn_vector(s: str, pos: int) -> tuple[list, int]:
    assert s[pos] == '['
    pos += 1
    result = []
    while True:
        pos = _skip_ws(s, pos)
        if pos >= len(s) or s[pos] == ']':
            return result, pos + 1
        val, pos = _edn_value(s, pos)
        result.append(val)


def _edn_list(s: str, pos: int) -> tuple[list, int]:
    assert s[pos] == '('
    pos += 1
    result = []
    while True:
        pos = _skip_ws(s, pos)
        if pos >= len(s) or s[pos] == ')':
            return result, pos + 1
        val, pos = _edn_value(s, pos)
        result.append(val)


def _unwrap_envelope(parsed: Any) -> Any:
    """If `parsed` is an envelope map ({:ok? true :result …}), peel one layer."""
    if isinstance(parsed, dict) and "result" in parsed and "ok?" in parsed:
        return parsed["result"]
    return parsed


# ── FastAPI app ───────────────────────────────────────────────────────────────

_UI_DIR = os.path.dirname(os.path.abspath(__file__))
_DIST_DIR = os.path.join(_UI_DIR, "dist")

app = FastAPI(title="Loom UI Server", version="0.2.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


def _ensure_graph_alias():
    """Verify ctx, db, graph, and scope are available in nREPL user ns."""
    result, errors = nrepl_eval(
        "(and (bound? #'ctx) (some? db/db-get-entity) "
        "     (some? graph/subgraph) (some? loom.scope/normalize-stack))"
    )
    if not (result and "true" in result):
        print(f"[warn] nREPL user ns missing ctx/db/graph/scope — errors: {errors}, result: {result}")


_ensure_graph_alias()


# ── serve index.html at / ────────────────────────────────────────────────────

@app.get("/")
def root():
    react_index = os.path.join(_DIST_DIR, "index.html")
    legacy_index = os.path.join(_UI_DIR, "index.html")
    if os.path.exists(react_index):
        return FileResponse(react_index)
    return FileResponse(legacy_index)


# ── /health ───────────────────────────────────────────────────────────────────

@app.get("/health")
def health():
    return {"status": "ok", "nrepl": nrepl_available()}


# ── /api/sessions ─────────────────────────────────────────────────────────────

def _resolve_loom_dir() -> str:
    """Ask nREPL for the active loom-dir (falls back to ./.loom)."""
    result, _errs = nrepl_eval_locked("(get-in ctx [:config :loom-dir])")
    if result:
        s = result.strip().strip('"')
        if s and s not in ("nil", "null"):
            return s
    return os.path.abspath(".loom")


@app.get("/api/sessions")
def list_sessions():
    """List session ids by reading <loom-dir>/sessions/.

    Response shape (plan §4, augmented):
        {"global_sid": <UUID>,
         "sessions":   [{"id": "sid1", "mtime": 1714831200.0}, …],
         "current":    <ctx session id or null>,
         "loom_dir":   <abs path>}

    `mtime` is the unix timestamp (seconds, float) of the session directory's
    last modification — used by the UI to render human-readable labels
    ("May 04 14:32") instead of opaque UUIDs. Sessions are sorted newest first.

    The legacy magic string "global" is no longer surfaced; the frontend
    should treat `global_sid` as the canonical id for the global scope and
    render it with whatever label it likes."""
    loom_dir = _resolve_loom_dir()
    sessions_dir = os.path.join(loom_dir, "sessions")
    sessions: list[dict] = []
    if os.path.isdir(sessions_dir):
        for name in os.listdir(sessions_dir):
            path = os.path.join(sessions_dir, name)
            if os.path.isdir(path) and name != GLOBAL_SID:
                try:
                    mtime = os.path.getmtime(path)
                except OSError:
                    mtime = 0.0
                sessions.append({"id": name, "mtime": mtime})
    # newest first
    sessions.sort(key=lambda s: s["mtime"], reverse=True)

    current_raw, _ = nrepl_eval_locked("(:session-id ctx)")
    current = None
    if current_raw:
        s = current_raw.strip().strip('"')
        if s and s not in ("nil", "null"):
            current = s

    return {
        "global_sid": GLOBAL_SID,
        "sessions": sessions,
        "current": current,
        "loom_dir": loom_dir,
    }


# ── /api/subgraph ─────────────────────────────────────────────────────────────

@app.get("/api/subgraph")
def subgraph(
    root_id: str = Query(...),
    session_ids: Optional[str] = Query(None, description="CSV of session ids"),
    strict: bool = Query(False),
    max_depth: int = Query(2),
    direction: str = Query("out"),
):
    sids = _parse_session_ids(session_ids)
    opts = _stack_opts(sids, strict)
    code = (
        f'(graph/subgraph ctx {_clj_str(root_id)} '
        f'{{{opts} :max-depth {max_depth} :direction :{direction}}})'
    )
    result, errors = nrepl_eval_locked(code)
    if errors and not result:
        raise HTTPException(status_code=502, detail={"nrepl_errors": errors})

    try:
        data = _parse_edn(result) if result else {}
    except Exception as exc:
        raise HTTPException(status_code=502, detail={"parse_error": str(exc), "raw": result})

    inner = _unwrap_envelope(data) if isinstance(data, dict) else {}
    if not isinstance(inner, dict):
        inner = {}

    return {
        "nodes": inner.get("nodes", []),
        "edges": inner.get("edges", []),
        "root_id": root_id,
    }


# ── /api/search ───────────────────────────────────────────────────────────────

def _parse_entity_list(raw: Optional[str]) -> list[dict]:
    """Parse an envelope-wrapped entity list. Returns []."""
    if not raw:
        return []
    try:
        parsed = _parse_edn(raw)
    except Exception:
        return []
    inner = _unwrap_envelope(parsed)
    if isinstance(inner, list):
        return inner
    if isinstance(inner, dict):
        # tolerate {:results [...]} too
        if isinstance(inner.get("results"), list):
            return inner["results"]
    return []


@app.get("/api/search")
def search(
    q: str = Query(...),
    session_ids: Optional[str] = Query(None, description="CSV of session ids"),
    strict: bool = Query(False),
    limit: int = Query(20),
):
    sids = _parse_session_ids(session_ids)
    opts = _stack_opts(sids, strict)
    code = (
        f'(graph/search-entities-by-name ctx {_clj_str(q)} '
        f'{{{opts} :limit {limit}}})'
    )
    result, errors = nrepl_eval_search(code)
    if errors and not result:
        raise HTTPException(status_code=502, detail={"nrepl_errors": errors})

    items = _parse_entity_list(result)
    entities, seen = [], set()
    for item in items:
        if not isinstance(item, dict):
            continue
        eid = item.get("id")
        if not eid or eid in seen:
            continue
        seen.add(eid)
        entities.append({
            "id": eid,
            "canonical_name": item.get("canonical_name", ""),
            "label": item.get("canonical_name") or item.get("label") or item.get("name") or eid,
            "kind": item.get("kind", ""),
            "attrs": item.get("attrs", {}),
            "source_sessions": item.get("source_sessions", []),
            "confidence": item.get("confidence", 1.0),
        })
    return {"entities": entities, "count": len(entities)}


# ── /api/all-entities ────────────────────────────────────────────────────────

@app.get("/api/all-entities")
def all_entities(
    session_ids: Optional[str] = Query(None, description="CSV of session ids"),
    strict: bool = Query(False),
    limit: int = Query(500),
):
    sids = _parse_session_ids(session_ids)
    opts = _stack_opts(sids, strict)
    code = (
        f'(graph/search-entities-by-name ctx "" '
        f'{{{opts} :limit {limit}}})'
    )
    result, errors = nrepl_eval_search(code)
    if errors and not result:
        raise HTTPException(status_code=502, detail={"nrepl_errors": errors})

    items = _parse_entity_list(result)
    entities, seen = [], set()
    for item in items:
        if not isinstance(item, dict):
            continue
        eid = item.get("id")
        if not eid or eid in seen:
            continue
        seen.add(eid)
        entities.append({
            "id": eid,
            "canonical_name": item.get("canonical_name", ""),
            "label": item.get("canonical_name") or eid,
            "kind": item.get("kind", ""),
            "attrs": item.get("attrs", {}),
            "source_sessions": item.get("source_sessions", []),
        })
    return {"entities": entities, "count": len(entities)}


# ── /api/entity/{entity_id} ───────────────────────────────────────────────────

@app.get("/api/entity/{entity_id}")
def get_entity(
    entity_id: str,
    session_ids: Optional[str] = Query(None, description="CSV of session ids"),
    strict: bool = Query(False),
):
    sids = _parse_session_ids(session_ids)
    stack = _stack_clj(sids, strict)
    # db-get-entity takes a positional already-normalised stack vector.
    code = f'(db/db-get-entity ctx {_clj_str(entity_id)} {stack})'
    result, errors = nrepl_eval_locked(code)

    if errors and not result:
        raise HTTPException(status_code=502, detail={"nrepl_errors": errors})

    if not result or result.strip() in ("nil", "null", ""):
        raise HTTPException(status_code=404, detail="Entity not found")

    try:
        data = _parse_edn(result)
    except Exception as exc:
        raise HTTPException(status_code=502, detail={"parse_error": str(exc), "raw": result})

    if data is None:
        raise HTTPException(status_code=404, detail="Entity not found")

    data = _unwrap_envelope(data)
    if data is None:
        raise HTTPException(status_code=404, detail="Entity not found")

    return data


# ── /api/neighbor-counts/{entity_id} ─────────────────────────────────────────

@app.get("/api/neighbor-counts/{entity_id}")
def neighbor_counts(
    entity_id: str,
    session_ids: Optional[str] = Query(None, description="CSV of session ids"),
    strict: bool = Query(False),
):
    sids = _parse_session_ids(session_ids)
    opts = _stack_opts(sids, strict)
    code = (
        f'(graph/neighbor-counts ctx {_clj_str(entity_id)} '
        f'{{{opts}}})'
    )
    result, errors = nrepl_eval_locked(code)

    if errors and not result:
        raise HTTPException(status_code=502, detail={"nrepl_errors": errors})

    try:
        data = _parse_edn(result) if result else {}
    except Exception as exc:
        raise HTTPException(status_code=502, detail={"parse_error": str(exc), "raw": result})

    inner = _unwrap_envelope(data) if isinstance(data, dict) else {}
    if not isinstance(inner, dict):
        inner = {}

    return {"in": inner.get("in", 0), "out": inner.get("out", 0)}


# ── Entry point ───────────────────────────────────────────────────────────────

# Serve JS/CSS/etc. as static files (must be mounted after API routes)
_static_dir = _DIST_DIR if os.path.isdir(_DIST_DIR) else _UI_DIR
app.mount("/", StaticFiles(directory=_static_dir, html=True), name="static")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8765)
