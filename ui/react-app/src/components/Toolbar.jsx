export default function Toolbar({ edgeLabels, onToggleEdgeLabels, onClear, nodeCount, edgeCount }) {
  const btn = (label, onClick, active = false, danger = false) => (
    <button
      onClick={onClick}
      style={{
        padding: '5px 12px',
        background: active ? 'var(--accent)' : 'var(--surface2)',
        border: `1px solid ${active ? 'var(--accent)' : 'var(--border)'}`,
        borderRadius: 'var(--radius)',
        color: danger ? 'var(--danger)' : active ? '#fff' : 'var(--text)',
        fontSize: 12,
        cursor: 'pointer',
      }}
    >
      {label}
    </button>
  )

  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 8, padding: '0 14px',
      height: 40, background: 'var(--surface)', borderBottom: '1px solid var(--border)',
      flexShrink: 0,
    }}>
      {btn('Edge Labels', onToggleEdgeLabels, edgeLabels)}
      {btn('Clear', onClear, false, true)}
      <span style={{ marginLeft: 'auto', fontSize: 11, color: 'var(--text-dim)' }}>
        {nodeCount} nodes · {edgeCount} edges
      </span>
    </div>
  )
}
