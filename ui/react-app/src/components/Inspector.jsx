import { useState, useCallback } from 'react'
import { apiPromoteEntity } from '../api.js'

export default function Inspector({ node, counts, attrs, sessionStack, onClose, onExpand, onPromoted }) {
  const d = node.data
  const [promoting, setPromoting] = useState(false)
  const [promoteStatus, setPromoteStatus] = useState(null) // null | 'promoting' | 'ok' | {err}

  const handlePromote = useCallback(async () => {
    setPromoting(true)
    setPromoteStatus('promoting')
    try {
      await apiPromoteEntity(node.id, { sessionStack })
      setPromoteStatus('ok')
      if (onPromoted) onPromoted(node.id)
      setTimeout(() => setPromoteStatus(null), 2000)
    } catch (e) {
      setPromoteStatus({ err: e.message })
    } finally {
      setPromoting(false)
    }
  }, [node.id, sessionStack, onPromoted])

  const isGlobal = d.scope === 'global'

  return (
    <div style={{
      position: 'absolute', top: 0, right: 0, bottom: 0, width: 320,
      background: 'var(--surface)', borderLeft: '1px solid var(--border)',
      display: 'flex', flexDirection: 'column', zIndex: 10,
    }}>
      {/* header */}
      <div style={{
        display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between',
        padding: 14, borderBottom: '1px solid var(--border)', gap: 8,
      }}>
        <div style={{ fontSize: 15, fontWeight: 600, wordBreak: 'break-all', flex: 1 }}>
          {d.label || node.id}
        </div>
        <button onClick={onClose} style={{
          background: 'none', border: 'none', color: 'var(--text-dim)',
          fontSize: 18, cursor: 'pointer',
        }}>✕</button>
      </div>

      {/* body */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '12px 14px' }}>
        <SectionTitle>Kind</SectionTitle>
        <KindBadge kind={d.kind} />

        <SectionTitle style={{ marginTop: 12 }}>Connectivity</SectionTitle>
        <div style={{ display: 'flex', gap: 10, marginTop: 4 }}>
          <DegCard label="In"  value={counts?.in  ?? ''} />
          <DegCard label="Out" value={counts?.out ?? ''} />
        </div>

        {attrs && Object.keys(attrs).length > 0 && (
          <>
            <SectionTitle style={{ marginTop: 12 }}>Attributes</SectionTitle>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12, marginTop: 8 }}>
              <tbody>
                {Object.entries(attrs).map(([k, v]) => (
                  <tr key={k}>
                    <td style={{ padding: '5px 6px', borderBottom: '1px solid var(--border)', color: 'var(--text-dim)', whiteSpace: 'nowrap', width: '35%', verticalAlign: 'top' }}>{k}</td>
                    <td style={{ padding: '5px 6px', borderBottom: '1px solid var(--border)', verticalAlign: 'top', wordBreak: 'break-all' }}>
                      {typeof v === 'object' ? JSON.stringify(v) : String(v)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </div>

      {/* footer */}
      <div style={{ padding: '12px 14px', borderTop: '1px solid var(--border)' }}>
        <div style={{ display: 'flex', gap: 8 }}>
          <button
            onClick={() => onExpand(node.id)}
            style={{
              flex: 1, padding: 8, background: 'var(--accent)', border: 'none',
              borderRadius: 'var(--radius)', color: '#fff', fontSize: 13, fontWeight: 600, cursor: 'pointer',
            }}
          >
            ⬡ Expand from here
          </button>
          <button
            onClick={handlePromote}
            disabled={isGlobal || promoting}
            title={isGlobal ? 'Already global' : 'Promote to global scope'}
            style={{
              flex: 1, padding: 8,
              background: isGlobal ? 'var(--surface2)' : 'var(--accent2)',
              border: isGlobal ? '1px solid var(--border)' : 'none',
              borderRadius: 'var(--radius)', color: isGlobal ? 'var(--text-dim)' : '#fff',
              fontSize: 13, fontWeight: 600,
              cursor: isGlobal || promoting ? 'not-allowed' : 'pointer',
              opacity: promoting ? 0.7 : 1,
            }}
          >
            ⬆ Promote
          </button>
        </div>
        {promoteStatus && (
          <span style={{
            display: 'block', marginTop: 6, fontSize: 12,
            color: promoteStatus === 'ok'
              ? 'var(--accent2)'
              : promoteStatus === 'promoting'
                ? 'var(--text-dim)'
                : '#e55',
          }}>
            {promoteStatus === 'promoting' && 'Promoting…'}
            {promoteStatus === 'ok' && 'Promoted ✓'}
            {promoteStatus?.err && `Failed: ${promoteStatus.err}`}
          </span>
        )}
      </div>
    </div>
  )
}

function SectionTitle({ children, style }) {
  return (
    <div style={{
      fontSize: 11, fontWeight: 600, textTransform: 'uppercase',
      letterSpacing: '0.08em', color: 'var(--text-dim)', marginBottom: 4, ...style,
    }}>
      {children}
    </div>
  )
}

function DegCard({ label, value }) {
  return (
    <div style={{
      flex: 1, background: 'var(--surface2)', border: '1px solid var(--border)',
      borderRadius: 'var(--radius)', padding: 8, textAlign: 'center',
    }}>
      <div style={{ fontSize: 22, fontWeight: 700, color: 'var(--accent)', minHeight: 28 }}>{value}</div>
      <div style={{ fontSize: 10, color: 'var(--text-dim)', textTransform: 'uppercase' }}>{label}</div>
    </div>
  )
}

const KIND_COLORS = {
  fact: '#5CB85C', goal: '#F0AD4E', chunk: '#9E9E9E', event: '#9B59B6', tool: '#4A90D9',
}

function KindBadge({ kind }) {
  return (
    <span style={{
      fontSize: 11, padding: '2px 8px', borderRadius: 10,
      background: KIND_COLORS[kind] || 'var(--border)',
      color: '#fff', textTransform: 'uppercase',
    }}>
      {kind || 'unknown'}
    </span>
  )
}
