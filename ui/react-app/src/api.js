// api.js — Loom UI HTTP client.
//
// All read endpoints accept a `sessionStack` (array of session ids, lowest
// rank first) and an optional `strict` flag. When `sessionStack` is null or
// undefined, the backend's default-stack (current ctx session + GLOBAL_SID)
// is used.

const BASE = ''  // same origin via Vite proxy

async function apiFetch(url) {
  const res = await fetch(url)
  if (!res.ok) {
    const text = await res.text()
    throw new Error(`HTTP ${res.status}: ${text}`)
  }
  return res.json()
}

/** Append session-stack params to a URL. Mutates and returns the URL string.
 *  When `sessionStack` is empty, neither `session_ids` nor `strict` is sent —
 *  the backend will fall back to its default-stack and an empty `:session-ids`
 *  + `:strict? true` would otherwise throw "empty session stack" server-side. */
function withStack(url, sessionStack, strict = false) {
  const u = new URL(url, window.location.origin)
  if (Array.isArray(sessionStack) && sessionStack.length > 0) {
    u.searchParams.set('session_ids', sessionStack.join(','))
    if (strict) u.searchParams.set('strict', 'true')
  }
  return u.pathname + u.search
}

// ── session metadata ─────────────────────────────────────────────────────────

export async function apiSessions() {
  // {global_sid, sessions:[…], current, loom_dir}
  return apiFetch(`${BASE}/api/sessions`)
}

// ── search / subgraph / entity / counts ──────────────────────────────────────

export async function apiSearch(q, { sessionStack, strict, limit = 20 } = {}) {
  const url = withStack(`${BASE}/api/search?q=${encodeURIComponent(q)}&limit=${limit}`,
                        sessionStack, strict)
  return apiFetch(url)
}

export async function apiSubgraph(rootId, { sessionStack, strict, depth = 2, direction = 'both' } = {}) {
  const url = withStack(
    `${BASE}/api/subgraph?root_id=${encodeURIComponent(rootId)}&max_depth=${depth}&direction=${direction}`,
    sessionStack, strict
  )
  return apiFetch(url)
}

export async function apiEntity(id, { sessionStack, strict } = {}) {
  const url = withStack(`${BASE}/api/entity/${encodeURIComponent(id)}`, sessionStack, strict)
  return apiFetch(url)
}

export async function apiNeighborCounts(id, { sessionStack, strict } = {}) {
  const url = withStack(`${BASE}/api/neighbor-counts/${encodeURIComponent(id)}`,
                        sessionStack, strict)
  return apiFetch(url)
}

export async function apiAllEntities({ sessionStack, strict, limit = 500 } = {}) {
  const url = withStack(`${BASE}/api/all-entities?limit=${limit}`, sessionStack, strict)
  return apiFetch(url)
}

export async function apiHealth() {
  return apiFetch(`${BASE}/health`)
}

// ── Node detail cache ────────────────────────────────────────────────────────
// Cache key includes the session stack so switching stacks invalidates.

export const attrsCache = new Map()
export const countsCache = new Map()

function cacheKey(id, sessionStack, strict) {
  const stack = Array.isArray(sessionStack) ? sessionStack.join(',') : ''
  return `${id}|${stack}|${strict ? 1 : 0}`
}

/** Fetch attrs + counts for a node and store in cache, scoped by stack. */
export async function prefetchNode(id, opts = {}) {
  const k = cacheKey(id, opts.sessionStack, opts.strict)
  const fetches = []
  if (!attrsCache.has(k)) {
    fetches.push(
      apiEntity(id, opts)
        .then(e => attrsCache.set(k, e.attrs || {}))
        .catch(() => {})
    )
  }
  if (!countsCache.has(k)) {
    fetches.push(
      apiNeighborCounts(id, opts)
        .then(c => countsCache.set(k, c))
        .catch(() => {})
    )
  }
  await Promise.all(fetches)
}

export function getCachedAttrs(id, opts = {}) {
  return attrsCache.get(cacheKey(id, opts.sessionStack, opts.strict))
}

export function getCachedCounts(id, opts = {}) {
  return countsCache.get(cacheKey(id, opts.sessionStack, opts.strict))
}

export function hasCachedAttrs(id, opts = {}) {
  return attrsCache.has(cacheKey(id, opts.sessionStack, opts.strict))
}

export function hasCachedCounts(id, opts = {}) {
  return countsCache.has(cacheKey(id, opts.sessionStack, opts.strict))
}

// ── promotion ────────────────────────────────────────────────────────────────

export async function apiPromoteEntity(entityId, { sessionStack, sessionId } = {}) {
  // POST /api/promote/entity/{entityId}?session_ids=…  (or ?session_id=…)
  // returns { ok, promoted: [id], result }
  const u = new URL(`${BASE}/api/promote/entity/${encodeURIComponent(entityId)}`, window.location.origin)
  if (Array.isArray(sessionStack) && sessionStack.length > 0) {
    u.searchParams.set('session_ids', sessionStack.join(','))
  }
  if (sessionId != null) u.searchParams.set('session_id', sessionId)
  const res = await fetch(u.pathname + u.search, { method: 'POST' })
  if (!res.ok) {
    const text = await res.text()
    throw new Error(`HTTP ${res.status}: ${text}`)
  }
  const data = await res.json()
  // Invalidate all attrsCache / countsCache entries whose key starts with `${entityId}|`
  const prefix = `${entityId}|`
  for (const k of attrsCache.keys()) {
    if (k.startsWith(prefix)) attrsCache.delete(k)
  }
  for (const k of countsCache.keys()) {
    if (k.startsWith(prefix)) countsCache.delete(k)
  }
  return data
}

export async function apiPromoteRelation(relationId, { sessionStack, sessionId } = {}) {
  // POST /api/promote/relation/{relationId}?session_ids=… (or ?session_id=…)
  const u = new URL(`${BASE}/api/promote/relation/${encodeURIComponent(relationId)}`, window.location.origin)
  if (Array.isArray(sessionStack) && sessionStack.length > 0) {
    u.searchParams.set('session_ids', sessionStack.join(','))
  }
  if (sessionId != null) u.searchParams.set('session_id', sessionId)
  const res = await fetch(u.pathname + u.search, { method: 'POST' })
  if (!res.ok) {
    const text = await res.text()
    throw new Error(`HTTP ${res.status}: ${text}`)
  }
  return res.json()
}

export async function apiCandidates({ sessionId, limit = 100 } = {}) {
  // GET /api/promotion/candidates?session_id=...&limit=...
  // returns { candidates: [...] }
  const u = new URL(`${BASE}/api/promotion/candidates`, window.location.origin)
  if (sessionId != null) u.searchParams.set('session_id', sessionId)
  u.searchParams.set('limit', limit)
  return apiFetch(u.pathname + u.search)
}
