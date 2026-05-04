# UI Promotion Controls

Status: DRAFT

## Overview

Add user-facing controls for entity/relation promotion in the Loom UI. Three
modes (Off / Suggest / Auto) toggleable from the toolbar; per-entity Promote
button in the Inspector; right-click promote on edges; multi-select bulk
promote; left-side "🌟 Candidates" panel listing entities passing
`entity-eligible-for-promotion?`; undo toast after auto-promote runs. In
Suggest mode, `auto-promote!` writes events tagged
`:type "promotion-suggested"` instead of upserting at `:scope :global`.

## Architecture

```
React UI ──HTTP──▶ FastAPI (ui/server.py) ──nREPL──▶ loom.graph
                                                    ├ promote-entity!  (existing)
                                                    ├ promote-relation! (NEW)
                                                    ├ promote-batch!    (NEW)
                                                    ├ auto-promote!     (mode-aware)
                                                    └ list-promotion-candidates (NEW)
                                                  loom.session
                                                    ├ get-promotion-mode
                                                    └ set-promotion-mode!
```

- Mode is process-wide state on `ctx` (atom under `:promotion-mode`,
  default `:off`). Persisted nowhere yet — out of scope.
- Suggest queue = events table; `:type "promotion-suggested"` carries
  `:entity-id`, `:session-id`, `:reason`.
- Undo: backend returns `{:promoted [eid…] :undo-token uuid}`; UI keeps the
  list and offers a 10s toast with "Undo" calling `POST /api/promotion/undo`
  which deletes the global rows for those ids (best-effort; out-of-scope:
  cascaded relations).

## API (Clojure, all `with-provenance`)

- `loom.graph/promote-relation! [ctx relation-id & {:keys [session-id]}]`
  → thin wrapper: load relation under `[sid] :strict? true`, then
  `db/db-upsert-relation! ... {:scope :global}`. Returns global relation id.
- `loom.graph/promote-batch! [ctx ids & {:keys [session-id kinds]}]`
  → dispatches each id to `promote-entity!` or `promote-relation!` based on
  kind hint (UI passes `kinds`). Returns `{:promoted [...] :failed [...]}`.
- `loom.graph/list-promotion-candidates [ctx & {:keys [session-id limit]}]`
  → `(filter entity-eligible-for-promotion? (db/db-list-entities …))`,
  trimmed to `:limit` (default 100).
- `loom.graph/auto-promote!` — extend with `:mode` arg (`:auto|:suggest`).
  In `:suggest`, replace the inner `promote-entity!` call with
  `(events/log! ctx {:type "promotion-suggested" :entity-id (:id e) ...})`.
- `loom.session/get-promotion-mode [ctx]` / `set-promotion-mode! [ctx mode]`
  — atom-backed accessor pair on `ctx`.

## FastAPI endpoints (ui/server.py)

- `POST /api/promote/entity/{id}` body: `{session_id?}` →
  `(graph/promote-entity! ctx id :session-id sid)`
- `POST /api/promote/relation/{id}` body: `{session_id?}` →
  `(graph/promote-relation! ...)`
- `POST /api/promote/batch` body: `{ids:[...], kinds:{id:"entity"|"relation"}}`
  → `(graph/promote-batch! ...)`
- `GET  /api/promotion/candidates?session_id=&limit=` →
  `(graph/list-promotion-candidates ...)`
- `GET  /api/promotion/mode` → `{mode: "off|suggest|auto"}`
- `PUT  /api/promotion/mode` body: `{mode}` → setter
- `POST /api/promotion/undo` body: `{ids:[...]}` → delete global rows

All wrap nREPL via existing `nrepl_eval_locked`; reuse `_unwrap_envelope`
and `_clj_str/_clj_vec` helpers.

## Frontend integration (skeleton diff)

- `api.js`: add `apiPromoteEntity`, `apiPromoteRelation`, `apiPromoteBatch`,
  `apiCandidates`, `apiGetMode`, `apiSetMode`, `apiUndoPromote`.
- `App.jsx`: add `promotionMode` state, fetched on mount; pass to `Toolbar`
  + `Inspector` + new `CandidatesPanel`; track `selectedIds` set wired to
  ReactFlow `onSelectionChange`; mount `BulkActionBar` when
  `selectedIds.size > 1`; mount `UndoToast` listener; right-click on edge
  via `onEdgeContextMenu` opens `EdgeContextMenu`.
- `Toolbar.jsx`: add three-segment mode toggle (Off/Suggest/Auto).
- `Inspector.jsx`: new "⬆ Promote" button in footer (next to Expand);
  disabled if entity already global (kind heuristic: scope === :global).
- `CandidatesPanel.jsx` (NEW): left dock, list rows w/ checkbox + name +
  reason; "Promote selected" CTA.
- `BulkActionBar.jsx` (NEW): floating bottom-center bar; Promote / Cancel.
- `EdgeContextMenu.jsx` (NEW): tiny popover, single "Promote relation" item.
- `UndoToast.jsx` (NEW): bottom-right, 10s timer, "Undo" calls undo endpoint
  and reloads scope.

## Tasks

| id  | phase | file                                                    | summary                                                                                          | depends-on |
|-----|-------|---------------------------------------------------------|--------------------------------------------------------------------------------------------------|------------|
| T00 | P2    | ui/server.py                                            | Add `:scope` field to `/api/entity` and `/api/all-entities` response dicts                       | none       |
| T01 | P1    | src/loom/session.clj                                    | Add `:promotion-mode` atom on ctx + `get-promotion-mode` / `set-promotion-mode!` (validate enum) | none       |
| T02 | P1    | src/loom/graph.clj                                      | Add `promote-relation!` (load under strict session stack, upsert `:scope :global`)               | none       |
| T03 | P1    | src/loom/graph.clj                                      | Add `list-promotion-candidates` (reuse `entity-eligible-for-promotion?`)                         | T02        |
| T04 | P1    | src/loom/graph.clj                                      | Add `promote-batch!` dispatching entity/relation by `:kinds` map                                 | T02        |
| T05 | P1    | src/loom/graph.clj                                      | Extend `auto-promote!` with `:mode` arg; `:suggest` writes `promotion-suggested` event           | T01, T03   |
| T06 | P1    | test/loom/graph_promotion_test.clj                      | Tests: `promote-relation!`, batch, suggest-mode emits events, candidates filter                  | T02, T03, T04, T05 |
| T07 | P2    | ui/server.py                                            | `POST /api/promote/entity/{id}` + `POST /api/promote/relation/{id}`                              | T00, T02   |
| T08 | P2    | ui/server.py                                            | `POST /api/promote/batch`                                                                        | T04, T07   |
| T09 | P2    | ui/server.py                                            | `GET /api/promotion/candidates`                                                                  | T03, T07   |
| T10 | P2    | ui/server.py                                            | `GET` + `PUT /api/promotion/mode`                                                                | T01, T09   |
| T11 | P2    | ui/server.py                                            | `POST /api/promotion/undo` (delete global rows for given ids)                                    | T10        |
| T12 | P3    | ui/react-app/src/api.js                                 | Add 7 client fns (entity, relation, batch, candidates, get/set mode, undo)                       | T07, T09   |
| T13 | P3    | ui/react-app/src/components/Inspector.jsx               | Add "⬆ Promote" button in footer; calls `apiPromoteEntity`; disable when `entity.scope === "global"` | T00, T12 |
| T14 | P3    | ui/react-app/src/components/EdgeContextMenu.jsx (NEW)   | Tiny popover w/ "Promote relation" item                                                          | T12        |
| T15 | P3    | ui/react-app/src/App.jsx                                | Wire `onEdgeContextMenu` → mount `EdgeContextMenu` at cursor                                     | T14        |
| T16 | P4    | ui/react-app/src/App.jsx                                | Track `selectedIds` via `onSelectionChange`; pass to BulkActionBar                               | T13        |
| T17 | P4    | ui/react-app/src/components/BulkActionBar.jsx (NEW)     | Floating bar: Promote selected / Cancel; calls `apiPromoteBatch`                                 | T16, T12   |
| T18 | P5    | ui/react-app/src/components/CandidatesPanel.jsx (NEW)   | Left dock: list candidates w/ checkboxes + per-row promote                                       | T12        |
| T19 | P5    | ui/react-app/src/components/Toolbar.jsx                 | Add Off/Suggest/Auto segmented toggle bound to `apiGetMode`/`apiSetMode`                         | T12        |
| T20 | P5    | ui/react-app/src/App.jsx                                | Mount `CandidatesPanel`; lift `promotionMode` state                                              | T18, T19   |
| T21 | P6    | ui/react-app/src/components/UndoToast.jsx (NEW)         | 10s toast with Undo button; calls `apiUndoPromote`                                               | T12        |
| T22 | P6    | ui/react-app/src/App.jsx                                | Show `UndoToast` after any promote response carrying `promoted` ids                              | T21, T13, T17 |

## Smallest viable PR

Tasks: **T00, T01, T02, T03, T07, T09, T12, T13** — backend mode atom (unused yet) + relation promote + candidates fn, `:scope` exposed on entity payload, two endpoints (entity/relation promote, candidates list), api.js client fns, Inspector button. Ships single-entity promote end-to-end with correct disabled-state for already-global entities.

## Known gaps

- Undo is best-effort entity-only; cascaded relations from
  `promote-entity!` are not rolled back.
- Promotion mode is in-memory on ctx; lost on nREPL restart.
- No optimistic UI — all promote calls are await-then-reload.
- "Already global" detection in Inspector relies on backend hint that
  doesn't yet exist (entity kind/scope marker) — see Open questions.
- `EdgeContextMenu` requires `onEdgeContextMenu` from React Flow; confirm
  v12 supports it (it does as of @xyflow/react ≥12.0).

## Open questions

1. **Already-global detection** — How does the UI know an entity is already
   `:scope :global` so the Promote button can be disabled? Add
   `:scope` to the entity payload returned by `/api/entity` and
   `/api/all-entities`, or expose a separate `is_global` flag?
2. **Suggest queue surface** — Should the Candidates panel also display
   pending `promotion-suggested` events (separate tab), or is that a
   follow-up?
3. **Undo scope** — Just delete the freshly-upserted global entity rows, or
   also remove the cascaded global relations from `promote-entity!`?
4. **Mode persistence** — OK to keep `:promotion-mode` purely in-memory on
   ctx for v1, or persist to config / `.loom/state.edn`?
5. **Permissions** — Any role/auth check before mutating? Current UI is
   single-user local; assuming none.
