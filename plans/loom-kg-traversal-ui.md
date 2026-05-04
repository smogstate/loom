# Loom KG Traversal UI — Implementation Plan

**Stack:** Cytoscape.js · FastAPI · nREPL bridge  
**Status:** Updated after code audit of `src/loom/graph.clj` and `src/loom/db.clj`

---

## Section 0: Loom-side prerequisites (L-tasks)

These tasks MUST be completed before the corresponding UI tasks. They fix blocking bugs and missing APIs discovered during the code audit.

### L1 — `graph` alias missing in `user` ns bootstrap

| Field | Value |
|---|---|
| File | `src/loom/core.clj` or new `dev/user.clj` |
| Depends on | none |
| Severity | 🔴 blocks everything |

**Description:**  
The nREPL `user` namespace has aliases for `db`, `session`, `embedder`, and `tools` but NOT for `graph`. Every call to `graph/bfs`, `graph/neighbors`, `graph/search-entities` from the nREPL session fails with a `CompilerException: No such namespace: graph`.

**Fix:**  
Add `(require '[loom.graph :as graph])` to the nREPL bootstrap. This can be done either:
- In `loom.core/start!` alongside the existing `require` calls, or
- In a dedicated `dev/user.clj` init file that is loaded on nREPL server start.

**Acceptance:** `(graph/search-entities ctx "test" 5 :session-id "s1")` returns without CompilerException in a fresh nREPL session. Note: `k` is a required positional integer (the third argument), not part of the opts map.

---

### L2 — No batch entity fetch

| Field | Value |
|---|---|
| File | `src/loom/db.clj` |
| Depends on | none |
| Severity | 🔴 N+1 hydration |

**Description:**  
`db/db-get-entity` is single-id only. BFS returns up to 200 node IDs; hydrating them requires 200 sequential nREPL calls, which is unacceptable for UI latency.

**Fix:**  
Add `db/db-get-entities-by-ids [ctx ids session-id]` that executes a single `WHERE id IN (...)` query with session-first + global scope semantics (session rows shadow global rows by the same id).

**Signature:**
```clojure
(defn db-get-entities-by-ids
  [ctx ids session-id]
  ;; Returns a vector of entity maps, session rows take precedence over global rows.
  ...)
```

**Acceptance:** A single call with 200 IDs returns all matching entities in ≤ 1 SQL round-trip.

---

### L3 — No `graph/subgraph` API

| Field | Value |
|---|---|
| File | `src/loom/graph.clj` |
| Depends on | L2 |
| Severity | 🟠 multiple round-trips |

**Description:**  
`graph/bfs` returns `[{:node_id, :min_depth}]` — IDs only, no edges. The UI needs nodes + edges together. Without a combined API, the backend must make separate calls: bfs → get-entities-by-ids → search-relations filtered to those IDs, resulting in 3+ round-trips per user interaction.

**Fix:**  
Add `graph/subgraph [ctx start-id opts]` that returns `{:nodes [entity...] :edges [relation...]}` in one call. Internally it:
1. Runs BFS to collect node IDs within `(:max-depth opts 3)` hops.
2. Hydrates entities via `db/db-get-entities-by-ids` (batch fetch, L2).
3. Fetches all connecting relations in one SQL pass filtered to the collected node IDs.

**Signature:**
```clojure
(defn subgraph
  [ctx start-id opts]
  ;; opts: {:session-id s :max-depth n :direction :out/:in/:both}
  ;; Returns {:nodes [entity-map ...] :edges [relation-map ...]}
  ...)
```

**Acceptance:** One call returns both nodes and edges; SQL query count ≤ 3 regardless of graph size.

---

### L4 — SQL filter pushdown in `db-search-relations` and `db-neighbors`

| Field | Value |
|---|---|
| File | `src/loom/db.clj` |
| Depends on | none |
| Severity | 🟠 full table scan |

**Description:**  
Both functions currently load ALL relations then filter in Clojure:
```clojure
(->> (query conn "SELECT * FROM graph_relations WHERE retired = false")
     (filter #(or (nil? subject-id) (= subject-id (:subject_id %))))
     ...)
```
This performs a full table scan on every call, which degrades badly as the graph grows.

**Fix:**  
Push `subject_id`, `object_id`, and `predicate` filters into the SQL `WHERE` clause. Apply the same treatment to `db-neighbors` direction filter (`subject_id = ?` for `:out`, `object_id = ?` for `:in`, `OR` for `:both`). Also push the `predicates` set filter in `db-neighbors` (currently a Clojure-side `pred-set` filter at line 1564) into SQL using `AND predicate IN (...)` when predicates are specified.

**Example after fix:**
```clojure
(query conn
  "SELECT * FROM graph_relations
   WHERE retired = false
     AND subject_id = ?
     AND predicate = ?"
  subject-id predicate)
```

**Acceptance:** `EXPLAIN` on the generated queries shows predicate pushdown; no full-table-scan path for filtered queries.

---

### L5 — `load-graph-scope!` called on every read

| Field | Value |
|---|---|
| File | `src/loom/db.clj` |
| Depends on | none |
| Severity | 🟠 parquet reload per call |

**Description:**  
Every read function (`search-entities`, `search-relations`, `neighbors`, `bfs`) calls `load-graph-scope!`, which executes `DROP TABLE + INSERT FROM parquet`. Multiple API calls per user interaction trigger multiple full parquet reloads, causing severe latency spikes.

**Fix:**  
Add a dirty-flag or mtime-based cache so `load-graph-scope!` is a no-op when the parquet file has not changed since the last load. Invalidate the cache (set dirty = true) on every `write!` thunk completion.

**Implementation sketch:**
```clojure
;; Cache keyed by [session-id parquet-path] — NOT a global singleton.
;; A single global atom is unsafe for multi-session concurrent reads.
(def ^:private graph-scope-mtimes (atom {}))

(defn load-graph-scope! [conn ctx session-id]
  (let [cache-key  [session-id (entities-path ctx)]
        cur-mtime  (parquet-mtime ctx session-id)
        last-mtime (get @graph-scope-mtimes cache-key 0)]
    (when (> cur-mtime last-mtime)
      (do-load-graph-scope! conn ctx session-id)
      (swap! graph-scope-mtimes assoc cache-key cur-mtime))))
```

**Important:** Dirty-flag invalidation must occur **inside the `write!` thunk** after `flush-entities!`/`flush-relations!` completes, to be race-free with the writer goroutine:
```clojure
;; Inside write! thunk, after flush:
(swap! graph-scope-mtimes dissoc [session-id (entities-path ctx)])
```

**Acceptance:** 10 consecutive read calls with no intervening writes trigger exactly 1 parquet load (verified by log or counter). Two concurrent sessions with different `session-id` values each get correct data (no cross-session cache pollution).

---

### L6 — No neighbor count API

| Field | Value |
|---|---|
| File | `src/loom/graph.clj` |
| Depends on | none |
| Severity | 🟡 UX gap |

**Description:**  
The Inspector panel needs in/out degree counts for each node. Currently this requires two full `neighbors` calls (fetching all neighbor entities) just to count them, which is wasteful.

**Fix:**  
Add `graph/neighbor-counts [ctx entity-id opts]` returning `{:in n :out n}` via two `COUNT(*)` SQL queries (no entity hydration).

**Signature:**
```clojure
(defn neighbor-counts
  [ctx entity-id opts]
  ;; opts: {:session-id s}
  ;; Returns {:in <integer> :out <integer>}
  ...)
```

**Acceptance:** Returns correct counts; generates exactly 2 SQL queries (one per direction); does not hydrate entity rows.

---

### L7 — No name/prefix search

| Field | Value |
|---|---|
| File | `src/loom/db.clj`, `src/loom/graph.clj` |
| Depends on | none |
| Severity | 🟡 UX gap |

**Description:**  
`search-entities` is embedding-only. Users typing exact tool names or goal titles get no results if the embedding similarity is low (e.g., short strings, acronyms, exact identifiers). This makes the search box feel broken for power users.

**Fix:**  
1. Add `db/db-search-entities-by-name [ctx name-prefix opts]` using `WHERE lower(canonical_name) LIKE lower(?) || '%'` with session-first semantics.
2. Expose as `graph/search-entities-by-name [ctx name-prefix opts]` in `graph.clj`.

**Signature:**
```clojure
;; db.clj
(defn db-search-entities-by-name
  [ctx name-prefix opts]
  ;; opts: {:session-id s :limit n}
  ;; Returns vector of entity maps, session rows shadow global rows.
  ...)

;; graph.clj
(defn search-entities-by-name
  [ctx name-prefix opts]
  ...)
```

**Acceptance:** `(graph/search-entities-by-name ctx "loom" {:session-id "s1"})` returns all entities whose `canonical_name` starts with "loom" (case-insensitive), without requiring an embedding.

---

## Section 1: Overview

The **Loom KG Traversal UI** is a browser-based graph explorer that lets users visually navigate the Loom knowledge graph. It renders entities as nodes and relations as directed edges, supports interactive traversal (click to expand neighbors), and provides an inspector panel for entity details.

### Goals

1. Provide a visual interface for exploring the Loom knowledge graph.
2. Support session-scoped and global graph views.
3. Enable interactive BFS traversal starting from any entity.
4. Show entity metadata (type, canonical name, neighbor counts) in an inspector panel.
5. Support semantic search and name/prefix search to locate starting nodes.
6. Perform well on graphs with hundreds of nodes (lazy loading, SQL pushdown, batch hydration).

### Non-goals (see Section 6)

- Graph editing (create/delete entities or relations) via the UI.
- Real-time collaborative editing.
- Export to external graph formats (GraphML, GEXF).
- Authentication / multi-user access control.

---

## Section 2: Architecture

```
Browser
  └── Cytoscape.js (graph canvas)
  └── Inspector panel (vanilla JS / Alpine.js)
  └── Search bar
        │  HTTP/JSON
        ▼
FastAPI server  (Python, port 8765)
  └── /api/subgraph          ← graph/subgraph
  └── /api/search            ← graph/search-entities + search-entities-by-name
  └── /api/entity/{id}       ← db/db-get-entity
  └── /api/neighbor-counts/{id} ← graph/neighbor-counts
        │  nREPL (port 7888)
        ▼
Loom nREPL server  (Clojure)
  └── loom.graph  (graph/subgraph, graph/search-entities, graph/search-entities-by-name, graph/neighbor-counts)
  └── loom.db     (db/db-get-entity, db/db-get-entities-by-ids, db/db-search-entities-by-name)
  └── loom.core   (ctx, session management)
```

### Key design decisions

| Decision | Rationale |
|---|---|
| FastAPI as middle tier | Keeps browser JS simple; nREPL protocol stays server-side |
| nREPL bridge in Python | Reuses existing `loom_eval.py` pattern; no new Clojure HTTP server needed |
| Cytoscape.js | Mature, well-documented, handles 500+ nodes smoothly with WebGL renderer |
| Lazy BFS expansion | Load only immediate neighbors on click; avoid loading the full graph upfront |
| `graph/subgraph` as primary API | Single round-trip for nodes + edges; see L3 |

---

## Section 3: Data model (UI-facing)

### Node (entity)

```json
{
  "id": "uuid",
  "label": "canonical_name",
  "type": "tool | fact | goal | chunk | event",
  "session_id": "s1 | null",
  "metadata": { "...": "..." }
}
```

### Edge (relation)

```json
{
  "id": "uuid",
  "source": "subject_id",
  "target": "object_id",
  "label": "predicate",
  "weight": 1.0
}
```

### Subgraph response

```json
{
  "nodes": [ <node>, ... ],
  "edges": [ <edge>, ... ],
  "root_id": "uuid"
}
```

### Search response

```json
{
  "results": [
    { "id": "uuid", "label": "canonical_name", "type": "...", "score": 0.92 }
  ]
}
```

### Neighbor counts response

```json
{ "in": 4, "out": 7 }
```

---

## Section 4: Work breakdown

Tasks are ordered by dependency. L-tasks are Loom-side prerequisites; U-tasks are UI layer tasks.

### Phase 0 — Loom-side prerequisites

| Task | Description | File(s) | Depends on |
|---|---|---|---|
| **L1** | Add `graph` alias to nREPL bootstrap | `src/loom/core.clj` or `dev/user.clj` | — |
| **L2** | Add `db/db-get-entities-by-ids` batch fetch | `src/loom/db.clj` | — |
| **L3** | Add `graph/subgraph` combined BFS+hydrate+edges API | `src/loom/graph.clj` | L2 |
| **L4** | SQL filter pushdown in `db-search-relations`, `db-neighbors` | `src/loom/db.clj` | — |
| **L5** | Dirty-flag / mtime cache for `load-graph-scope!` | `src/loom/db.clj` | — |
| **L6** | Add `graph/neighbor-counts` COUNT-only API | `src/loom/graph.clj` | — |
| **L7** | Add `db/db-search-entities-by-name` + `graph/search-entities-by-name` | `src/loom/db.clj`, `src/loom/graph.clj` | — |

### Phase 1 — FastAPI server scaffold

| Task | Description | Depends on |
|---|---|---|
| **U1** | Create `ui/server.py`: FastAPI app, nREPL client wrapper, health endpoint | — |
| **U2** | Implement nREPL probe: verify `graph`, `db`, `session` aliases are available | L1 |
| **U3** | Implement API route builders: `/api/subgraph`, `/api/search`, `/api/entity/{id}`, `/api/neighbor-counts/{id}` | U2, L3, L6, L7 |

### Phase 2 — Frontend scaffold

| Task | Description | Depends on |
|---|---|---|
| **U4** | Create `ui/index.html`: page shell, Cytoscape.js CDN import, Alpine.js CDN import | — |
| **U5** | Create `ui/graph.js`: Cytoscape instance, layout config (dagre or cose-bilkent), style sheet | U4 |
| **U6** | Create `ui/api.js`: thin fetch wrappers for all FastAPI endpoints | U4 |

### Phase 3 — Core graph interactions

| Task | Description | Depends on |
|---|---|---|
| **U7** | Search bar: debounced input → `/api/search` → result dropdown → select to load subgraph | U5, U6, U3 |
| **U8** | Initial subgraph load: call `/api/subgraph` with selected root, render nodes + edges via `graph/subgraph` | U5, U6, U3 |
| **U9** | Click-to-expand: tap a node → call `/api/subgraph` with that node as root → merge new nodes/edges into canvas | U8 |
| **U10** | Collapse subtree: right-click → context menu → remove descendants from canvas | U9 |

### Phase 4 — Inspector panel

| Task | Description | Depends on |
|---|---|---|
| **U11** | Inspector panel HTML/CSS: slide-in panel, entity name, type badge, metadata table | U4 |
| **U12** | Wire node-select event → `/api/entity/{id}` + `/api/neighbor-counts/{id}` → populate inspector | U11, U6, U3 |
| **U13** | "Expand from here" button in inspector → triggers U9 flow | U12, U9 |

### Phase 5 — Visual polish

| Task | Description | Depends on |
|---|---|---|
| **U14** | Node color/shape by entity type (tool=circle/blue, fact=diamond/green, goal=star/orange, chunk=rect/grey, event=hexagon/purple) | U5 |
| **U15** | Edge label display toggle (show/hide predicate labels) | U5 |
| **U16** | Session filter toggle: show session-scoped nodes only / global only / both | U8 |
| **U17** | Minimap overlay (Cytoscape navigator extension) | U5 |

### Dependency graph (full)

```
L1 ──────────────────────────────────────────► U2 ──► U3 ──► U7, U8, U9, U12
L2 ──► L3 ──────────────────────────────────────────► U3
L4 (no UI dep, improves perf of all graph calls)
L5 (no UI dep, improves perf of all graph calls)
L6 ──────────────────────────────────────────────────► U3 ──► U12
L7 ──────────────────────────────────────────────────► U3 ──► U7
U1 ──► U2 ──► U3
U4 ──► U5, U6, U11
U5 ──► U7, U8, U9, U10, U14, U15, U16, U17
U6 ──► U7, U8, U12
U8 ──► U9 ──► U10, U13
U11 ──► U12 ──► U13
```

---

## Section 5: Acceptance criteria

### L-task criteria

| Task | Criterion |
|---|---|
| L1 | `(graph/search-entities ctx "test" 5 :session-id "s1")` returns without CompilerException in a fresh nREPL session (`k` is a required positional integer) |
| L2 | `(db/db-get-entities-by-ids ctx ids "s1")` with 200 IDs completes in ≤ 1 SQL round-trip; returns correct entities |
| L3 | `(graph/subgraph ctx root-id {:session-id "s1" :max-depth 2})` returns `{:nodes [...] :edges [...]}` in ≤ 3 SQL queries |
| L4 | `EXPLAIN` on filtered `db-search-relations` / `db-neighbors` queries shows no full-table-scan path |
| L5 | 10 consecutive read calls with no writes trigger exactly 1 parquet load (verified by counter or log) |
| L6 | `(graph/neighbor-counts ctx entity-id {:session-id "s1"})` returns `{:in n :out n}` using exactly 2 SQL queries |
| L7 | `(graph/search-entities-by-name ctx "loom" {:session-id "s1"})` returns all entities with `canonical_name` starting with "loom" (case-insensitive), no embedding required |

### U-task criteria

| Task | Criterion |
|---|---|
| U1 | `GET /health` returns `{"status": "ok", "nrepl": true}` when Loom nREPL is reachable |
| U2 | Server startup log confirms `graph`, `db`, `session` aliases are all present; startup fails fast with clear error if L1 is not applied |
| U3 | All four API routes return valid JSON matching the data model in Section 3; 404 for unknown entity IDs |
| U4 | `index.html` loads without JS errors in Chrome and Firefox |
| U5 | Cytoscape canvas renders 200 nodes + 400 edges at ≥ 30 fps |
| U6 | All fetch wrappers handle network errors and surface them to the UI (not silent failures) |
| U7 | Typing 3+ characters in the search bar shows results within 500 ms; selecting a result loads its subgraph |
| U8 | Initial subgraph renders within 1 s for depth-2 traversal from any entity (requires L3, L5) |
| U9 | Clicking an unexpanded node appends its neighbors to the canvas without re-rendering existing nodes |
| U10 | Right-click → Collapse removes all descendant nodes and their edges from the canvas |
| U11 | Inspector panel is visible on node select; hidden when canvas background is clicked |
| U12 | Inspector shows entity name, type, metadata, in-degree, out-degree (requires L6) |
| U13 | "Expand from here" in inspector triggers the same expansion as clicking the node directly |
| U14 | Each entity type renders with a distinct color and shape as specified |
| U15 | Edge labels toggle on/off via a toolbar button without re-fetching data |
| U16 | Session filter correctly shows/hides nodes based on their `session_id` field |
| U17 | Minimap renders in the bottom-right corner and syncs with main canvas pan/zoom |

---

## Section 6: Out of scope

The following are explicitly out of scope for this plan and should not be implemented:

1. **Graph editing via UI** — creating, updating, or deleting entities and relations through the browser interface. All writes go through the existing Loom API (nREPL / Python client).
2. **Real-time collaborative editing** — WebSocket-based multi-user sync, presence indicators, conflict resolution.
3. **Export to external graph formats** — GraphML, GEXF, DOT, or any other graph serialization format.
4. **Authentication and access control** — login, sessions, per-user permissions. The UI is assumed to run on localhost or a trusted internal network.
5. **Graph layout persistence** — saving and restoring node positions across browser sessions.
6. **Undo/redo** — for canvas operations (expand, collapse, filter).
7. **Mobile / touch optimization** — the UI targets desktop browsers only.
8. **Automated graph layout tuning** — the layout algorithm parameters are fixed; no ML-based or user-adjustable layout optimization.
9. **nREPL connection pooling / multiplexing** — the FastAPI bridge uses a single nREPL connection with a threading lock. Concurrent requests are serialized. Multi-user concurrent access is not supported in this milestone.

---

## Section 7: Performance notes

### Severity table

| Issue | Severity | Fix task |
|---|---|---|
| `graph` alias missing | 🔴 blocks everything | L1 |
| No batch entity fetch | 🔴 N+1 hydration | L2 |
| No subgraph API | 🟠 multiple round-trips | L3 |
| SQL filter pushdown | 🟠 full table scan | L4 |
| `load-graph-scope!` on every read | 🟠 parquet reload per call | L5 |
| No neighbor count API | 🟡 UX gap | L6 |
| No name/prefix search | 🟡 UX gap | L7 |

### Notes

- **L1 and L2 are hard blockers.** The UI cannot function at all without L1 (alias fix), and will be unusably slow without L2 (batch hydration). These must be the first two tasks completed.
- **L3 depends on L2** and should be implemented immediately after. The `/api/subgraph` endpoint is the hot path for every user interaction.
- **L4 and L5 are independent** and can be parallelized with L2/L3. They provide the largest latency improvement for large graphs (>10k relations).
- **L6 and L7 are UX gaps** that do not block core functionality but are required for the inspector panel (U12) and search bar (U7) to work well.
- The FastAPI middle tier adds ~1–2 ms per call for nREPL serialization. This is acceptable given that the dominant cost is SQL I/O in Loom. If nREPL latency becomes a bottleneck, consider batching multiple Loom calls into a single `do` form evaluated over nREPL.
- Cytoscape.js with the WebGL renderer (`cytoscape-canvas` or `cytoscape-fcose`) handles 500+ nodes at 60 fps. For graphs exceeding 1000 nodes, enable virtual rendering (only render nodes in the viewport).
