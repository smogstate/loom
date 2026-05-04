import { useEffect, useMemo, useState, useRef } from 'react'
import { apiSessions } from '../api.js'

/**
 * DatePicker — pick a calendar day; the picker translates that day into
 * a session stack (all session ids whose mtime falls on that day) and
 * emits it via `onChange` so the rest of the app keeps working with the
 * same session-stack plumbing.
 *
 * Props:
 *   sessionStack : string[]                      // currently selected ids
 *   onChange     : (stack: string[]) => void
 *
 * UX:
 *   - Calendar grid for one month at a time, with ‹ / › month nav.
 *   - Days that have ≥1 session get a tinted background + count.
 *   - Today is outlined; the day matching the current selection is solid.
 *   - Click an active day  → emit stack of that day's sessions
 *     (preserving the current "global" toggle).
 *   - Click an inactive day → no-op (visually disabled).
 *   - "global" checkbox below the calendar toggles GLOBAL_SID in/out of
 *     the emitted stack without touching the day selection.
 *   - Outside-click closes the popover.
 */
export default function DatePicker({ sessionStack, onChange }) {
  const [meta, setMeta]   = useState(null)
  const [error, setError] = useState(null)
  const [open, setOpen]   = useState(false)
  const [month, setMonth] = useState(() => startOfMonth(new Date()))
  const wrapRef = useRef(null)

  useEffect(() => {
    let alive = true
    apiSessions()
      .then(m => { if (alive) setMeta(m) })
      .catch(e => { if (alive) setError(String(e)) })
    return () => { alive = false }
  }, [])

  useEffect(() => {
    if (!open) return
    const handler = e => {
      if (wrapRef.current && !wrapRef.current.contains(e.target)) setOpen(false)
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [open])

  // Group sessions by local YYYY-MM-DD
  const dayMap = useMemo(() => {
    const m = new Map()
    if (!meta) return m
    for (const s of meta.sessions || []) {
      if (!s.mtime) continue
      const key = ymd(new Date(s.mtime * 1000))
      if (!m.has(key)) m.set(key, [])
      m.get(key).push(s.id)
    }
    return m
  }, [meta])

  if (error) return <span style={{ color: 'var(--danger)', fontSize: 11 }}>sessions: {error}</span>
  if (!meta)  return <span style={{ color: 'var(--text-dim)', fontSize: 11 }}>loading sessions…</span>

  const { global_sid } = meta
  const globalOn = sessionStack.includes(global_sid)

  // Determine which day (if any) the current stack corresponds to.
  // A day is "selected" if every non-global id in the stack belongs to it
  // and the stack covers all sessions for that day.
  const nonGlobalStack = sessionStack.filter(s => s !== global_sid).sort()
  let selectedDay = null
  for (const [day, ids] of dayMap.entries()) {
    const sorted = [...ids].sort()
    if (sorted.length === nonGlobalStack.length &&
        sorted.every((v, i) => v === nonGlobalStack[i])) {
      selectedDay = day
      break
    }
  }

  const emit = (day, withGlobal) => {
    const ids = day ? (dayMap.get(day) || []) : []
    const next = withGlobal ? [...ids, global_sid] : [...ids]
    onChange(next)
  }

  const onDayClick = day => {
    const has = dayMap.has(day)
    if (!has) return
    if (day === selectedDay) emit(null, globalOn)   // toggle off
    else                     emit(day, globalOn)
  }

  const toggleGlobal = () => emit(selectedDay, !globalOn)

  // Button label
  const label = (() => {
    const parts = []
    if (selectedDay) parts.push(prettyDay(selectedDay))
    if (globalOn)    parts.push('global')
    if (parts.length === 0) return 'default scope'
    return parts.join(' + ')
  })()

  return (
    <div ref={wrapRef} style={{ position: 'relative', display: 'inline-block' }}>
      <span style={{ fontSize: 11, color: 'var(--text-dim)', marginRight: 6 }}>day:</span>
      <button
        onClick={() => setOpen(v => !v)}
        title="Pick a day to view its sessions"
        style={{
          fontSize: 12, padding: '5px 10px',
          background: 'var(--surface2)', color: 'var(--text)',
          border: '1px solid var(--border)', borderRadius: 'var(--radius)',
          cursor: 'pointer', minWidth: 200, textAlign: 'left',
          display: 'inline-flex', alignItems: 'center', gap: 6,
        }}
      >
        <span style={{ flex: 1 }}>{label}</span>
        <span style={{ fontSize: 9, opacity: 0.6 }}>{open ? '▲' : '▼'}</span>
      </button>

      {open && (
        <div style={{
          position: 'absolute', top: 'calc(100% + 4px)', left: 32, zIndex: 200,
          background: 'var(--surface2)', border: '1px solid var(--border)',
          borderRadius: 'var(--radius)', padding: 10,
          boxShadow: '0 8px 24px rgba(0,0,0,0.5)', width: 260,
        }}>
          <CalendarHeader
            month={month}
            onPrev={() => setMonth(addMonths(month, -1))}
            onNext={() => setMonth(addMonths(month, +1))}
            onToday={() => setMonth(startOfMonth(new Date()))}
          />
          <CalendarGrid
            month={month}
            dayMap={dayMap}
            selectedDay={selectedDay}
            onDayClick={onDayClick}
          />

          {/* Legend */}
          <div style={{
            display: 'flex', alignItems: 'center', gap: 10,
            marginTop: 8, fontSize: 10, color: 'var(--text-dim)',
            justifyContent: 'center',
          }}>
            <LegendDot fill="rgba(123, 200, 255, 0.18)" label="has data" />
            <LegendDot border="var(--accent)" label="today" />
            <LegendDot fill="var(--accent)" label="selected" />
          </div>

          {/* Selected-day session count badge */}
          {selectedDay && (
            <div style={{
              marginTop: 6, padding: '6px 8px',
              fontSize: 11, color: 'var(--text)',
              background: 'var(--surface)', borderRadius: 4,
              display: 'flex', alignItems: 'center', gap: 6,
            }}>
              <span style={{ color: 'var(--accent)' }}>●</span>
              <span style={{ flex: 1 }}>{prettyDay(selectedDay)}</span>
              <span style={{ color: 'var(--text-dim)' }}>
                {(dayMap.get(selectedDay) || []).length} session{(dayMap.get(selectedDay) || []).length > 1 ? 's' : ''}
              </span>
            </div>
          )}

          <div style={{ height: 1, background: 'var(--border)', margin: '8px -10px 6px' }} />

          <Row
            checked={globalOn}
            onClick={toggleGlobal}
            primary="global"
            secondary="seed + promoted facts"
          />

          {(selectedDay || globalOn) && (
            <div
              onClick={() => onChange([])}
              style={{
                padding: '6px 8px', fontSize: 11, color: 'var(--text-dim)',
                cursor: 'pointer', textAlign: 'center', marginTop: 4,
                borderTop: '1px solid var(--border)',
              }}
              onMouseEnter={e => e.currentTarget.style.background = 'var(--surface)'}
              onMouseLeave={e => e.currentTarget.style.background = ''}
            >
              clear (use default scope)
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Calendar helpers
// ---------------------------------------------------------------------------

function CalendarHeader({ month, onPrev, onNext, onToday }) {
  const months = ['January','February','March','April','May','June',
                  'July','August','September','October','November','December']
  return (
    <div style={{ display: 'flex', alignItems: 'center', marginBottom: 6 }}>
      <NavBtn onClick={onPrev}>‹</NavBtn>
      <div
        onClick={onToday}
        title="Jump to today"
        style={{
          flex: 1, textAlign: 'center', fontSize: 12, fontWeight: 600,
          color: 'var(--text)', cursor: 'pointer',
        }}
      >
        {months[month.getMonth()]} {month.getFullYear()}
      </div>
      <NavBtn onClick={onNext}>›</NavBtn>
    </div>
  )
}

function NavBtn({ children, onClick }) {
  return (
    <button
      onClick={onClick}
      style={{
        background: 'transparent', border: 'none', color: 'var(--text)',
        cursor: 'pointer', fontSize: 16, padding: '0 8px', lineHeight: 1,
      }}
    >{children}</button>
  )
}

function CalendarGrid({ month, dayMap, selectedDay, onDayClick }) {
  const todayKey = ymd(new Date())
  const year = month.getFullYear()
  const m    = month.getMonth()
  const first = new Date(year, m, 1)
  const startWeekday = (first.getDay() + 6) % 7  // Mon=0
  const daysInMonth  = new Date(year, m + 1, 0).getDate()
  const cells = []
  for (let i = 0; i < startWeekday; i++) cells.push(null)
  for (let d = 1; d <= daysInMonth; d++) cells.push(new Date(year, m, d))
  while (cells.length % 7) cells.push(null)

  const dows = ['M','T','W','T','F','S','S']

  return (
    <div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 2, marginBottom: 4 }}>
        {dows.map((d, i) => (
          <div key={i} style={{
            fontSize: 10, color: 'var(--text-dim)', textAlign: 'center',
            padding: '2px 0',
          }}>{d}</div>
        ))}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 2 }}>
        {cells.map((d, i) => {
          if (!d) return <div key={i} />
          const key      = ymd(d)
          const ids      = dayMap.get(key)
          const active   = !!ids
          const selected = key === selectedDay
          const today    = key === todayKey
          return (
            <div
              key={i}
              onClick={() => onDayClick(key)}
              title={active ? `${ids.length} session${ids.length > 1 ? 's' : ''}` : ''}
              style={{
                position: 'relative',
                fontSize: 11, textAlign: 'center', padding: '6px 0',
                borderRadius: 4,
                cursor: active ? 'pointer' : 'default',
                color: selected ? 'var(--bg)'
                     : active   ? 'var(--text)'
                     :            'var(--text-dim)',
                background: selected ? 'var(--accent)'
                          : active   ? 'rgba(123, 200, 255, 0.18)'
                          :            'transparent',
                border: today && !selected ? '1px solid var(--accent)' : '1px solid transparent',
                fontWeight: active ? 600 : 400,
                opacity: active ? 1 : 0.45,
              }}
              onMouseEnter={e => { if (active && !selected) e.currentTarget.style.background = 'rgba(123, 200, 255, 0.32)' }}
              onMouseLeave={e => { if (active && !selected) e.currentTarget.style.background = 'rgba(123, 200, 255, 0.18)' }}
            >
              {d.getDate()}
              {active && !selected && (
                <div style={{
                  position: 'absolute', bottom: 1, left: '50%', transform: 'translateX(-50%)',
                  width: 3, height: 3, borderRadius: '50%', background: 'var(--accent)',
                }} />
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}

function Row({ checked, onClick, primary, secondary }) {
  return (
    <div
      onClick={onClick}
      style={{
        display: 'flex', alignItems: 'center', gap: 8,
        padding: '6px 8px', cursor: 'pointer', fontSize: 12, borderRadius: 4,
      }}
      onMouseEnter={e => e.currentTarget.style.background = 'var(--surface)'}
      onMouseLeave={e => e.currentTarget.style.background = ''}
    >
      <input type="checkbox" checked={checked} readOnly style={{ margin: 0, pointerEvents: 'none' }} />
      <span style={{ flex: 1, color: 'var(--text)' }}>{primary}</span>
      <span style={{ fontSize: 10, color: 'var(--text-dim)' }}>{secondary}</span>
    </div>
  )
}

function LegendDot({ fill, border, label }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
      <span style={{
        display: 'inline-block', width: 10, height: 10, borderRadius: 2,
        background: fill || 'transparent',
        border: border ? `1px solid ${border}` : '1px solid transparent',
      }} />
      {label}
    </span>
  )
}

// ---------------------------------------------------------------------------
// Date utils (local timezone)
// ---------------------------------------------------------------------------

function ymd(d) {
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function startOfMonth(d) {
  return new Date(d.getFullYear(), d.getMonth(), 1)
}

function addMonths(d, n) {
  return new Date(d.getFullYear(), d.getMonth() + n, 1)
}

function prettyDay(key) {
  const [y, m, d] = key.split('-').map(Number)
  const today = new Date()
  if (today.getFullYear() === y && today.getMonth() + 1 === m && today.getDate() === d) return 'today'
  const yest = new Date(); yest.setDate(yest.getDate() - 1)
  if (yest.getFullYear() === y && yest.getMonth() + 1 === m && yest.getDate() === d) return 'yesterday'
  const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec']
  return `${months[m - 1]} ${d}`
}
