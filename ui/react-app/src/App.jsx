import { useState, useCallback, useRef, useEffect } from 'react'
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
  addEdge,
  MarkerType,
  Panel,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'

import {
  apiSubgraph, apiAllEntities, apiHealth,
  prefetchNode, getCachedAttrs, getCachedCounts,
} from './api.js'
import KindNode from './components/KindNode.jsx'
import Inspector from './components/Inspector.jsx'
import SearchBar from './components/SearchBar.jsx'
import Toolbar from './components/Toolbar.jsx'
import DatePicker from './components/DatePicker.jsx'

// ── node types ────────────────────────────────────────────────────────────────
const nodeTypes = { kind: KindNode }

// ── layout: simple force-like grid placement ──────────────────────────────────
function layoutNodes(nodes, existingPositions = {}) {
  const cols = Math.ceil(Math.sqrt(nodes.length)) || 1
  return nodes.map((n, i) => {
    if (existingPositions[n.id]) return { ...n, position: existingPositions[n.id] }
    return {
      ...n,
      position: {
        x: (i % cols) * 180 + Math.random() * 40,
        y: Math.floor(i / cols) * 140 + Math.random() * 40,
      },
    }
  })
}

// ── map API data → React Flow nodes/edges ─────────────────────────────────────
function apiToFlow(data, existingPositions = {}) {
  const nodes = (data.nodes || []).map(n => ({
    id: n.id,
    type: 'kind',
    data: {
      label: n.canonical_name || n.id,
      kind: n.kind || 'unknown',
      attrs: n.attrs || {},
      source_sessions: n.source_sessions || [],
    },
    position: existingPositions[n.id] || { x: 0, y: 0 },
  }))

  const edges = (data.edges || []).map(e => ({
    id: e.id,
    source: e.subject_id,
    target: e.object_id,
    label: e.predicate || '',
    markerEnd: { type: MarkerType.ArrowClosed, color: '#555e6e' },
    style: { stroke: '#555e6e' },
    labelStyle: { fill: '#aab0bb', fontSize: 9 },
    labelBgStyle: { fill: 'transparent' },
  }))

  return { nodes: layoutNodes(nodes, existingPositions), edges }
}

// ── App ───────────────────────────────────────────────────────────────────────
export default function App() {
  const [nodes, setNodes, onNodesChange] = useNodesState([])
  const [edges, setEdges, onEdgesChange] = useEdgesState([])
  const [selectedNode, setSelectedNode] = useState(null)
  const [health, setHealth] = useState(null)
  const [edgeLabels, setEdgeLabels] = useState(false)
  // Session-stack state — empty array means "use backend default-stack"
  // (current ctx session + GLOBAL_SID). When the user picks anything in the
  // SessionPicker, that exact stack is sent with strict=true (no implicit
  // global tail) so the chip selection is the literal read scope.
  const [sessionStack, setSessionStack] = useState([])
  const strict = true  // always strict; picker assembles the exact stack
  const expandedRef = useRef(new Set())
  const positionsRef = useRef({})

  // Note: we deliberately do NOT seed sessionStack on mount. Empty stack =
  // backend default-stack (ctx session + GLOBAL), which is the friendliest
  // first-paint view. The picker still calls /api/sessions for its own list.

  // track node positions as user drags
  const onNodeDragStop = useCallback((_, node) => {
    positionsRef.current[node.id] = node.position
  }, [])

  // health check
  useEffect(() => {
    const check = () =>
      apiHealth()
        .then(() => setHealth('ok'))
        .catch(() => setHealth('err'))
    check()
    const t = setInterval(check, 30000)
    return () => clearInterval(t)
  }, [])

  // merge new subgraph data into existing nodes/edges
  const mergeSubgraph = useCallback((data) => {
    const { nodes: newNodes, edges: newEdges } = apiToFlow(data, positionsRef.current)

    setNodes(prev => {
      const existingIds = new Set(prev.map(n => n.id))
      const toAdd = newNodes.filter(n => !existingIds.has(n.id))
      // re-layout only the new ones, offset from existing
      const offset = prev.length * 10
      const positioned = toAdd.map((n, i) => ({
        ...n,
        position: positionsRef.current[n.id] || {
          x: (i % 6) * 200 + offset,
          y: Math.floor(i / 6) * 160 + offset,
        },
      }))
      return [...prev, ...positioned]
    })

    setEdges(prev => {
      const existingIds = new Set(prev.map(e => e.id))
      const toAdd = newEdges.filter(e => !existingIds.has(e.id))
      return [...prev, ...toAdd]
    })
  }, [setNodes, setEdges])

  const loadSubgraph = useCallback(async (rootId, depth = 2) => {
    setNodes([])
    setEdges([])
    expandedRef.current.clear()
    positionsRef.current = {}
    setSelectedNode(null)
    try {
      const data = await apiSubgraph(rootId, { sessionStack, strict, depth })
      const { nodes: newNodes, edges: newEdges } = apiToFlow(data)
      // store positions
      newNodes.forEach(n => { positionsRef.current[n.id] = n.position })
      setNodes(newNodes)
      setEdges(newEdges)
      expandedRef.current.add(rootId)
      ;(data.nodes || []).forEach(n => n.id && expandedRef.current.add(n.id))
    } catch (err) {
      console.error('loadSubgraph error:', err)
    }
  }, [setNodes, setEdges, sessionStack, strict])

  const expandNode = useCallback(async (nodeId) => {
    if (expandedRef.current.has(nodeId)) return
    expandedRef.current.add(nodeId)
    try {
      const data = await apiSubgraph(nodeId, { sessionStack, strict, depth: 1 })
      mergeSubgraph(data)
    } catch (err) {
      expandedRef.current.delete(nodeId)
      console.error('expandNode error:', err)
    }
  }, [mergeSubgraph, sessionStack, strict])

  const onNodeClick = useCallback(async (_, node) => {
    expandNode(node.id)
    // Fetch counts+attrs (stack-scoped cache) before mounting Inspector so it
    // renders with real values on the very first paint — no … → number flicker.
    const opts = { sessionStack, strict }
    try {
      await prefetchNode(node.id, opts)
    } catch (err) {
      console.warn('prefetchNode failed for', node.id, err)
    }
    setSelectedNode({
      node,
      counts: getCachedCounts(node.id, opts) || { in: 0, out: 0 },
      attrs:  getCachedAttrs(node.id, opts)  || null,
    })
  }, [expandNode, sessionStack, strict])

  const onPaneClick = useCallback(() => setSelectedNode(null), [])

  // Core loader — accepts stack/strict explicitly so it can be invoked
  // from `handleStackChange` against a NEW scope before React state has flushed.
  const loadAllForScope = useCallback(async (stack, strictMode) => {
    setNodes([])
    setEdges([])
    expandedRef.current.clear()
    positionsRef.current = {}
    setSelectedNode(null)
    try {
      const data = await apiAllEntities({ sessionStack: stack, strict: strictMode })
      const rfNodes = (data.entities || []).map((e, i) => ({
        id: e.id,
        type: 'kind',
        data: { label: e.label || e.canonical_name || e.id, kind: e.kind || 'unknown', attrs: e.attrs || {} },
        position: { x: (i % 8) * 200, y: Math.floor(i / 8) * 160 },
      }))
      setNodes(rfNodes)
    } catch (err) {
      console.error('loadAll error:', err)
    }
  }, [setNodes, setEdges])

  const handleClear = useCallback(() => {
    setNodes([])
    setEdges([])
    expandedRef.current.clear()
    positionsRef.current = {}
    setSelectedNode(null)
  }, [setNodes, setEdges])

  // When the picker emits a new stack, swap state and auto-load every entity
  // visible under the new scope so the canvas reflects the choice immediately.
  // Strict is fixed `true` — the picker always assembles the literal stack.
  const handleStackChange = useCallback((nextStack) => {
    setSessionStack(nextStack)
    loadAllForScope(nextStack, true)
  }, [loadAllForScope])

  const handleFit = useCallback(() => {
    // fitView is called via ref — handled in Toolbar
  }, [])

  // edge label toggle
  const visibleEdges = edges.map(e => ({
    ...e,
    labelStyle: { ...e.labelStyle, opacity: edgeLabels ? 1 : 0 },
  }))

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      {/* Topbar */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 10, padding: '0 14px',
        height: 52, background: 'var(--surface)', borderBottom: '1px solid var(--border)',
        flexShrink: 0, zIndex: 20,
      }}>
        <span style={{ fontWeight: 700, fontSize: 16, color: 'var(--accent)' }}>⬡ Loom KG</span>
        <SearchBar onSelect={id => loadSubgraph(id)} sessionStack={sessionStack} strict={strict} />
        <DatePicker
          sessionStack={sessionStack}
          onChange={handleStackChange}
        />
        <div style={{ flex: 1 }} />
        <span style={{
          width: 8, height: 8, borderRadius: '50%', display: 'inline-block',
          background: health === 'ok' ? 'var(--accent2)' : health === 'err' ? 'var(--danger)' : 'var(--text-dim)',
        }} title="API status" />
        <span style={{ fontSize: 11, color: 'var(--text-dim)' }}>
          {health === 'ok' ? 'Connected' : health === 'err' ? 'Disconnected' : '…'}
        </span>
      </div>

      {/* Toolbar */}
      <Toolbar
        edgeLabels={edgeLabels}
        onToggleEdgeLabels={() => setEdgeLabels(v => !v)}
        onClear={handleClear}
        nodeCount={nodes.length}
        edgeCount={edges.length}
      />

      {/* Main */}
      <div style={{ flex: 1, position: 'relative', overflow: 'hidden' }}>
        {nodes.length === 0 && (
          <div style={{
            position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column',
            alignItems: 'center', justifyContent: 'center', gap: 10,
            pointerEvents: 'none', color: 'var(--text-dim)', zIndex: 5,
          }}>
            <div style={{ fontSize: 48, opacity: 0.3 }}>⬡</div>
            <div style={{ fontSize: 14, opacity: 0.5 }}>Search for an entity to start exploring</div>
          </div>
        )}

        <ReactFlow
          nodes={nodes}
          edges={visibleEdges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onNodeClick={onNodeClick}
          onPaneClick={onPaneClick}
          onNodeDragStop={onNodeDragStop}
          nodeTypes={nodeTypes}
          fitView
          minZoom={0.05}
          maxZoom={5}
          style={{ background: 'var(--bg)' }}
        >
          <Background color="#2e3347" gap={24} />
          <Controls style={{ background: 'var(--surface)', border: '1px solid var(--border)' }} />
          <MiniMap
            nodeColor={n => kindColor(n.data?.kind)}
            style={{ background: 'var(--surface)', border: '1px solid var(--border)' }}
          />
        </ReactFlow>

        {/* Inspector */}
        {selectedNode && (
          <Inspector
            node={selectedNode.node}
            counts={selectedNode.counts}
            attrs={selectedNode.attrs}
            onClose={() => setSelectedNode(null)}
            onExpand={id => { expandedRef.current.delete(id); expandNode(id) }}
          />
        )}
      </div>
    </div>
  )
}

function kindColor(kind) {
  const map = {
    fact: '#5CB85C',
    goal: '#F0AD4E',
    chunk: '#9E9E9E',
    event: '#9B59B6',
    tool: '#4A90D9',
  }
  return map[kind] || '#4A90D9'
}
