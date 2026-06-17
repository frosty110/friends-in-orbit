// Browse List — full list view, searchable
function BrowseListScreen({ listName = 'Inner orbit', onBack, onOpenContact }) {
  const [q, setQ] = React.useState('');
  const contacts = [
    { name: 'Sarah Chen',   last: '11 days ago', due: true },
    { name: 'Jamie Torres', last: '3 days ago',  due: false },
    { name: 'Marcus Reed',  last: '18 days ago', due: true },
    { name: 'Priya Anand',  last: '6 days ago',  due: false },
    { name: 'Alex Kim',     last: '2 days ago',  due: false },
    { name: 'Dana Walsh',   last: '24 days ago', due: true },
    { name: 'Owen Brooks',  last: '8 days ago',  due: false },
    { name: 'Nia Ferreira', last: '1 day ago',   due: false },
    { name: 'Theo Park',    last: '14 days ago', due: true },
    { name: 'Luma Reyes',   last: '5 days ago',  due: false },
  ];
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
                Last called {c.last}
              </div>
            </div>
            {c.due && <div style={{ width: 8, height: 8, borderRadius: 999, background: 'var(--accent)' }} />}
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
