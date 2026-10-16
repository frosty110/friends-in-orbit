// Browse List — full list view, searchable
function BrowseListScreen({ listName = 'Inner orbit', onBack, onOpenContact }) {
  const [q, setQ] = React.useState('');
  const contacts = [
    { name: 'Sarah Chen',   last: '11 days ago', upNow: true },
    { name: 'Jamie Torres', last: '3 days ago',  upNow: false },
    { name: 'Marcus Reed',  last: '18 days ago', upNow: true },
    { name: 'Priya Anand',  last: '6 days ago',  upNow: false },
    { name: 'Alex Kim',     last: '2 days ago',  upNow: false },
    { name: 'Dana Walsh',   last: '24 days ago', upNow: true },
    { name: 'Owen Brooks',  last: '8 days ago',  upNow: false },
    { name: 'Nia Ferreira', last: '1 day ago',   upNow: false },
    { name: 'Theo Park',    last: '14 days ago', upNow: true },
    { name: 'Luma Reyes',   last: '5 days ago',  upNow: false },
  ];
  // Who is up now is said in words at the start of the second line ("Up now · ..."), as
  // the app's Browse rows say it. The accent dot that marked them at the row's end went
  // with rules.md Design 9 on 2026-10-08, and voice.md never says "due".
  const filtered = contacts.filter(c => c.name.toLowerCase().includes(q.toLowerCase()));

  return (
    <Screen>
      <AppBar
        title={listName}
        leading={<IconBtn name="arrow-left" aria-label="Back" onClick={onBack} />}
        trailing={<IconBtn name="sliders-horizontal" aria-label="Filter" />}
      />

      <div style={{ padding: '0 16px 12px' }}>
        <div style={{ position: 'relative' }}>
          <Icon name="magnifying-glass" size={18} color="var(--fg-subtle)"
            style={{ position: 'absolute', left: 16, top: '50%', transform: 'translateY(-50%)' }} />
          <input
            value={q} onChange={e => setQ(e.target.value)}
            placeholder="Search your people"
            style={{
              width: '100%', boxSizing: 'border-box',
              height: 44, padding: '0 14px 0 44px',
              background: 'var(--bg-subtle)', border: 0, borderRadius: 12,
              fontFamily: 'Inter', fontSize: 15, color: 'var(--fg)', outline: 'none',
            }}
          />
        </div>
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: '0 12px 24px' }}>
        {filtered.map((c, i) => (
          <button
            key={c.name}
            onClick={() => onOpenContact(c.name)}
            style={{
              width: '100%', border: 0, background: 'transparent', cursor: 'pointer',
              display: 'flex', alignItems: 'center', gap: 14,
              padding: '12px 12px',
              borderBottom: i === filtered.length - 1 ? 'none' : '1px solid var(--line-soft)',
              textAlign: 'left',
            }}
          >
            <Avatar name={c.name} size={44} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontFamily: 'Inter', fontSize: 16, fontWeight: 500, color: 'var(--fg)' }}>{c.name}</div>
              <div style={{ fontFamily: 'Inter', fontSize: 13, color: 'var(--fg-muted)', marginTop: 2 }}>
                {c.upNow ? 'Up now · ' : ''}Last called {c.last}
              </div>
            </div>
            <Icon name="caret-right" size={16} color="var(--fg-subtle)" />
          </button>
        ))}
        {filtered.length === 0 && (
          <div style={{ textAlign: 'center', padding: 40, color: 'var(--fg-muted)', fontFamily: 'Inter', fontSize: 15 }}>
            No one by that name.
          </div>
        )}
      </div>
    </Screen>
  );
}

window.BrowseListScreen = BrowseListScreen;
