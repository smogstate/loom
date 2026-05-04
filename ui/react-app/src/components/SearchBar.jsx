import { useState, useRef, useEffect } from 'react'
import { apiSearch } from '../api.js'

export default function SearchBar({ onSelect, sessionStack, strict }) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState([])
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const timerRef = useRef(null)
  const wrapRef = useRef(null)

  useEffect(() => {
    const handler = (e) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target)) setOpen(false)
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

  const handleInput = (e) => {
    const q = e.target.value
    setQuery(q)
    clearTimeout(timerRef.current)
    if (q.trim().length < 2) { setOpen(false); return }
    setLoading(true)
    setOpen(true)
    timerRef.current = setTimeout(async () => {
      try {
        const data = await apiSearch(q.trim(), { sessionStack, strict })
        setResults(data.entities || [])
      } catch {
        setResults([])
      } finally {
        setLoading(false)
      }
    }, 300)
  }

  const handleSelect = (entity) => {
    setQuery(entity.label || entity.canonical_name || entity.id)
    setOpen(false)
    onSelect(entity.id)
  }

  return (
    <div ref={wrapRef} style={{ position: 'relative', flex: 1, maxWidth: 480 }}>
      <input
        value={query}
        onChange={handleInput}
        placeholder="Search entities… (min 2 chars)"
        autoComplete="off"
        style={{
          width: '100%', padding: '7px 12px', background: 'var(--surface2)',
          border: '1px solid var(--border)', borderRadius: 'var(--radius)',
          color: 'var(--text)', fontSize: 13, outline: 'none',
        }}
        onFocus={() => query.trim().length >= 2 && setOpen(true)}
      />
      {open && (
        <div style={{
          position: 'absolute', top: 'calc(100% + 4px)', left: 0, right: 0,
          background: 'var(--surface2)', border: '1px solid var(--border)',
          borderRadius: 'var(--radius)', maxHeight: 260, overflowY: 'auto',
          zIndex: 100, boxShadow: '0 8px 24px rgba(0,0,0,0.5)',
        }}>
          {loading && <div style={{ padding: '10px 12px', color: 'var(--text-dim)', fontSize: 12 }}>Searching…</div>}
          {!loading && results.length === 0 && (
            <div style={{ padding: '10px 12px', color: 'var(--text-dim)', fontSize: 12 }}>No results.</div>
          )}
          {!loading && results.map(e => (
            <div
              key={e.id}
              onClick={() => handleSelect(e)}
              style={{
                display: 'flex', alignItems: 'center', gap: 8, padding: '8px 12px',
                cursor: 'pointer',
              }}
              onMouseEnter={ev => ev.currentTarget.style.background = 'var(--surface)'}
              onMouseLeave={ev => ev.currentTarget.style.background = ''}
            >
              <span style={{ flex: 1, fontSize: 13 }}>{e.label || e.canonical_name || e.id}</span>
              <span style={{
                fontSize: 10, padding: '2px 6px', borderRadius: 10,
                background: 'var(--border)', color: 'var(--text-dim)', textTransform: 'uppercase',
              }}>{e.kind || ''}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
