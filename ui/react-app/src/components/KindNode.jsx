import { Handle, Position } from '@xyflow/react'

const KIND_STYLES = {
  fact:    { background: '#5CB85C', border: '2px solid #3d8b3d', borderRadius: '4px' },
  goal:    { background: '#F0AD4E', border: '2px solid #c87f0a', borderRadius: '50%' },
  chunk:   { background: '#9E9E9E', border: '2px solid #6e6e6e', borderRadius: '2px' },
  event:   { background: '#9B59B6', border: '2px solid #6c3483', borderRadius: '8px' },
  tool:    { background: '#4A90D9', border: '2px solid #2c6fad', borderRadius: '50%' },
  unknown: { background: '#4A90D9', border: '2px solid #2c6fad', borderRadius: '50%' },
}

export default function KindNode({ data, selected }) {
  const kind = data.kind || 'unknown'
  const style = KIND_STYLES[kind] || KIND_STYLES.unknown
  const label = data.label || '?'
  const short = label.length > 18 ? label.slice(0, 16) + '…' : label

  return (
    <div style={{
      ...style,
      width: 60,
      height: 60,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      cursor: 'pointer',
      outline: selected ? '2px solid #fff' : 'none',
      outlineOffset: 2,
      position: 'relative',
    }}>
      <Handle type="target" position={Position.Top} style={{ opacity: 0 }} />
      <Handle type="source" position={Position.Bottom} style={{ opacity: 0 }} />
      <span style={{
        fontSize: 9,
        color: '#fff',
        textAlign: 'center',
        padding: '0 4px',
        lineHeight: 1.2,
        wordBreak: 'break-all',
        maxWidth: 56,
      }}>
        {short}
      </span>
    </div>
  )
}
