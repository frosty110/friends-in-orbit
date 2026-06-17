// Home / Mood Picker — grid of list tiles
function HomeScreen({ onOpenList, onOpenSettings }) {
  const lists = [
    { id: 'surprise', name: 'Surprise me', subtitle: 'Pick for me', due: null, surprise: true },
    { id: 'inner',    name: 'Inner orbit', subtitle: '12 people · round robin',       due: 3, tone: 'terracotta' },
    { id: 'outer',    name: 'Outer orbit', subtitle: '34 people · spaced repetition', due: 5, tone: 'sage' },
    { id: 'family',   name: 'Family',      subtitle: '8 people · recency',            due: 1, tone: 'amber' },
    { id: 'latenight',name: 'Late night',  subtitle: '6 people · active 9p\u201311p',  due: 2, tone: 'stone' },
    { id: 'quiet',    name: 'Mentors',     subtitle: '4 people · monthly',            due: 0, tone: 'terracotta' },
  ];

  return (
    <Screen>
      <AppBar
        title="My Orbit"
        trailing={<IconBtn name="gear" aria-label="Settings" onClick={onOpenSettings} />}
      />
      <div style={{ padding: '0 20px 8px' }}>
        <div className="eyebrow" style={{ margin: 0, fontSize: 12, color: 'var(--fg-muted)', letterSpacing: '0.08em', textTransform: 'uppercase', fontWeight: 500 }}>
          Today
        </div>
        <div style={{ fontFamily: 'Inter', fontSize: 26, fontWeight: 600, color: 'var(--fg)', letterSpacing: '-0.01em', marginTop: 4 }}>
          11 people ready
        </div>
      </div>

      <div style={{
        flex: 1, overflowY: 'auto', padding: '12px 16px 24px',
        display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12,
      }}>
        {lists.map(l => (
          <button
            key={l.id}
            onClick={() => onOpenList(l.id, l.name)}
            style={{
              border: 0, textAlign: 'left', cursor: 'pointer',
              background: l.surprise ? 'var(--accent)' : 'var(--surface)',
              color: l.surprise ? '#fff' : 'var(--fg)',
              borderRadius: 16, padding: 18, minHeight: 128,
              display: 'flex', flexDirection: 'column', justifyContent: 'space-between',
              boxShadow: 'var(--shadow-card)',
              gridColumn: l.surprise ? '1 / -1' : 'auto',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <div style={{
                width: 36, height: 36, borderRadius: 10,
                background: l.surprise ? 'rgba(255,255,255,.18)' : 'var(--accent-tint)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
              }}>
                <Icon name={l.surprise ? 'shuffle' : 'users'} size={18} color={l.surprise ? '#fff' : 'var(--accent-press)'} />
              </div>
              {l.due != null && l.due > 0 && <CountBadge count={l.due} />}
            </div>
            <div>
              <div style={{ fontFamily: 'Inter', fontSize: 17, fontWeight: 600, letterSpacing: '-0.005em' }}>
                {l.name}
              </div>
              <div style={{
                fontFamily: 'Inter', fontSize: 13, marginTop: 4,
                color: l.surprise ? 'rgba(255,255,255,.8)' : 'var(--fg-muted)',
              }}>
                {l.subtitle}
              </div>
            </div>
          </button>
        ))}
      </div>
    </Screen>
  );
}

window.HomeScreen = HomeScreen;
