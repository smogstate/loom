const BASE = 'http://localhost:8765';

async function apiFetch(url, opts = {}) {
  const res = await fetch(url, opts);
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`HTTP ${res.status}: ${text}`);
  }
  return res.json();
}

export async function fetchSubgraph(rootId, sessionId = 'global', maxDepth = 2, direction = 'out') {
  return apiFetch(`${BASE}/api/subgraph?root_id=${encodeURIComponent(rootId)}&session_id=${encodeURIComponent(sessionId)}&max_depth=${maxDepth}&direction=${direction}`);
}

export async function searchEntities(q, sessionId = 'global', limit = 10) {
  return apiFetch(`${BASE}/api/search?q=${encodeURIComponent(q)}&session_id=${encodeURIComponent(sessionId)}&limit=${limit}`);
}

export async function fetchEntity(entityId, sessionId = 'global') {
  return apiFetch(`${BASE}/api/entity/${encodeURIComponent(entityId)}?session_id=${encodeURIComponent(sessionId)}`);
}

export async function fetchNeighborCounts(entityId, sessionId = 'global') {
  return apiFetch(`${BASE}/api/neighbor-counts/${encodeURIComponent(entityId)}?session_id=${encodeURIComponent(sessionId)}`);
}

export async function fetchHealth() {
  return apiFetch(`${BASE}/health`);
}
