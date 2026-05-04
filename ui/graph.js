/**
 * graph.js — Cytoscape instance setup for Loom KG Traversal UI
 */

const NODE_STYLES = [
  {
    // tool: circle (ellipse with equal w/h = circle in Cytoscape), blue
    selector: 'node[type = "tool"]',
    style: {
      shape: 'ellipse',
      'background-color': '#4A90D9',
      'border-color': '#2c6fad',
      'border-width': 2,
    },
  },
  {
    selector: 'node[type = "fact"]',
    style: {
      shape: 'diamond',
      'background-color': '#5CB85C',
      'border-color': '#3d8b3d',
      'border-width': 2,
    },
  },
  {
    selector: 'node[type = "goal"]',
    style: {
      shape: 'star',
      'background-color': '#F0AD4E',
      'border-color': '#c87f0a',
      'border-width': 2,
    },
  },
  {
    selector: 'node[type = "chunk"]',
    style: {
      shape: 'rectangle',
      'background-color': '#9E9E9E',
      'border-color': '#6e6e6e',
      'border-width': 2,
    },
  },
  {
    selector: 'node[type = "event"]',
    style: {
      shape: 'hexagon',
      'background-color': '#9B59B6',
      'border-color': '#6c3483',
      'border-width': 2,
    },
  },
];

const BASE_STYLES = [
  {
    selector: 'node',
    style: {
      shape: 'ellipse',
      'background-color': '#9E9E9E',
      'border-color': '#6e6e6e',
      'border-width': 2,
      label: 'data(label)',
      color: '#f0f0f0',
      'font-size': '11px',
      'text-valign': 'center',
      'text-halign': 'center',
      'text-wrap': 'ellipsis',
      'text-max-width': '80px',
      width: 50,
      height: 50,
      'overlay-padding': '4px',
    },
  },
  {
    selector: 'node:selected',
    style: {
      'border-color': '#ffffff',
      'border-width': 3,
      'overlay-color': '#ffffff',
      'overlay-opacity': 0.1,
    },
  },
  {
    selector: 'edge',
    style: {
      width: 2,
      'line-color': '#555e6e',
      'target-arrow-color': '#555e6e',
      'target-arrow-shape': 'triangle',
      'curve-style': 'bezier',
      label: 'data(label)',
      color: '#aab0bb',
      'font-size': '9px',
      'text-rotation': 'autorotate',
      'text-margin-y': -8,
      'text-opacity': 0,
    },
  },
  {
    selector: 'edge:selected',
    style: {
      'line-color': '#a0b4cc',
      'target-arrow-color': '#a0b4cc',
    },
  },
  {
    selector: '.hidden',
    style: {
      display: 'none',
    },
  },
  ...NODE_STYLES,
];

/**
 * Creates and returns a Cytoscape instance mounted on the given container element.
 * @param {HTMLElement} container
 * @returns {cytoscape.Core}
 */
export function initCytoscape(container) {
  const cy = cytoscape({
    container,
    elements: [],
    style: BASE_STYLES,
    layout: { name: 'cose' },
    wheelSensitivity: 0.3,
    minZoom: 0.1,
    maxZoom: 5,
  });
  return cy;
}

/**
 * Merges nodes and edges from a raw API subgraph response into cy without duplicating existing ones.
 *
 * API response shape:
 *   nodes: [{ id, canonical_name, kind, session_id, attrs }]
 *   edges: [{ id, subject_id, object_id, predicate }]
 *
 * @param {cytoscape.Core} cy
 * @param {{ nodes: Array, edges: Array, root_id?: string }} subgraphData
 * @returns {cytoscape.Collection} the newly added elements (may be empty)
 */
export function addSubgraph(cy, subgraphData) {
  const { nodes = [], edges = [] } = subgraphData;

  const newElements = [];

  for (const node of nodes) {
    // Support both raw API format and already-mapped Cytoscape format
    const id = node.id ?? node.data?.id;
    if (!id) continue;
    if (cy.getElementById(id).length) continue;

    newElements.push({
      group: 'nodes',
      data: {
        id,
        label: node.canonical_name ?? node.data?.label ?? id,
        type:  node.kind        ?? node.data?.type  ?? 'unknown',
        session_id: node.session_id ?? node.data?.session_id ?? null,
        metadata:   node.attrs      ?? node.data?.metadata   ?? {},
      },
    });
  }

  for (const edge of edges) {
    // Support both raw API format and already-mapped Cytoscape format
    const id     = edge.id          ?? edge.data?.id;
    const source = edge.subject_id  ?? edge.data?.source;
    const target = edge.object_id   ?? edge.data?.target;
    const label  = edge.predicate   ?? edge.data?.label ?? '';
    if (!id || !source || !target) continue;
    if (cy.getElementById(id).length) continue;

    newElements.push({
      group: 'edges',
      data: { id, source, target, label },
    });
  }

  if (newElements.length > 0) {
    return cy.add(newElements);
  }
  return cy.collection();
}

/**
 * Removes all descendants of a node (for collapse).
 * Uses BFS over both incoming and outgoing edges so that the full
 * reachable neighbourhood is pruned, not just the directed subtree.
 *
 * @param {cytoscape.Core} cy
 * @param {string} nodeId
 * @returns {Set<string>} IDs of the nodes that were removed
 */
export function removeDescendants(cy, nodeId) {
  const root = cy.getElementById(nodeId);
  if (!root.length) return new Set();

  // BFS to collect all nodes reachable from nodeId (directed outgoing)
  const visited = new Set();
  const queue = [nodeId];

  while (queue.length > 0) {
    const current = queue.shift();
    if (visited.has(current)) continue;
    visited.add(current);

    cy.getElementById(current).outgoers('node').forEach((n) => {
      if (!visited.has(n.id())) {
        queue.push(n.id());
      }
    });
  }

  // Remove all visited except the root itself
  visited.delete(nodeId);
  const toRemove = cy.nodes().filter((n) => visited.has(n.id()));
  cy.remove(toRemove);

  return visited; // set of removed node IDs
}

let _edgeLabelsVisible = false;

/**
 * Toggles edge label visibility.
 * @param {cytoscape.Core} cy
 * @returns {boolean} new visibility state
 */
export function toggleEdgeLabels(cy) {
  _edgeLabelsVisible = !_edgeLabelsVisible;
  cy.edges().style('text-opacity', _edgeLabelsVisible ? 1 : 0);
  return _edgeLabelsVisible;
}

/**
 * Filters nodes by session mode.
 * @param {cytoscape.Core} cy
 * @param {'all'|'session'|'global'} mode
 * @param {string} [currentSession] — required when mode === 'session'
 */
export function filterBySession(cy, mode, currentSession = '') {
  cy.nodes().forEach((node) => {
    const sid = node.data('session_id') || 'global';
    let visible = true;

    if (mode === 'session') {
      visible = sid === currentSession;
    } else if (mode === 'global') {
      visible = sid === 'global';
    }
    // mode === 'all' → always visible

    if (visible) {
      node.removeClass('hidden');
    } else {
      node.addClass('hidden');
    }
  });

  // Hide edges whose source or target is hidden
  cy.edges().forEach((edge) => {
    const srcHidden = edge.source().hasClass('hidden');
    const tgtHidden = edge.target().hasClass('hidden');
    if (srcHidden || tgtHidden) {
      edge.addClass('hidden');
    } else {
      edge.removeClass('hidden');
    }
  });
}

/**
 * Fits the graph to the viewport.
 * @param {cytoscape.Core} cy
 */
export function fitGraph(cy) {
  cy.fit(undefined, 40);
}

/**
 * Initialises the cytoscape-navigator (minimap) extension.
 * Must be called after the cytoscape-navigator script has been loaded.
 * @param {cytoscape.Core} cy
 */
export function initNavigator(cy) {
  if (typeof cy.navigator !== 'function') {
    console.warn('cytoscape-navigator extension not loaded — minimap skipped.');
    return;
  }
  cy.navigator({
    container: '#minimap',
    viewLiveFramerate: 0,
    thumbnailEventFramerate: 30,
    thumbnailLiveFramerate: false,
    dblClickDelay: 200,
    removeCustomContainer: false,
    rerenderDelay: 100,
  });
}
