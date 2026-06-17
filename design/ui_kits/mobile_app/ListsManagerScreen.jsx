// Lists Manager — create / edit / reorder lists
function ListsManagerScreen({ onBack, onOpenList, onCreate }) {
  const lists = [
    { id: 'inner',    name: 'Inner orbit',  members: 12, rule: 'Round robin · every 2w',    due: 3, tone: 'terracotta' },
    { id: 'outer',    name: 'Outer orbit',  members: 34, rule: 'Spaced repetition',          due: 5, tone: 'sage' },
    { id: 'family',   name: 'Family',       members: 8,  rule: 'Recency · weekly',           due: 1, tone: 'amber' },
    { id: 'latenight',name: 'Late night',   members: 6,  rule: 'Active window 9–11 pm',      due: 2, tone: 'stone' },
    { id: 'mentors',  name: 'Mentors',      members: 4,  rule: 'Monthly',                    due: 0, tone: 'terracotta' },
  ];

  return (
    <Screen>
      <AppBar
        title="Lists"
        leading={<IconBtn name="arrow-left" aria-label="Back" onClick={onBack} />}
        trailing={<IconBtn name="plus" aria-label="New list" onClick={onCreate} />}
      />
      <div style={{ flex: 1, overflowY: 'auto', padding: '4px 16px 32px' }}>
        <div style={{ background: 'var(--surface)', borderRadius: 16, boxShadow: 'var(--shadow-card)', overflow: 'hidden' }}>
          {lists.map((l, i) => (
            <button
              key={l.id}
              onClick={() => onOpenList(l.id, l.name)}
              style={{
                width: '100%', border: 0, background: 'transparent', cursor: 'pointer', textAlign: 'left',
                display: 'flex', alignItems: 'center', gap: 14, padding: '16px 16px',
                borderBottom: i < lists.length - 1 ? '1px solid var(--line-soft)' : 'none',
              }}
            >
              <div style={{ color: 'var(--fg-subtle)', cursor: 'grab' }}>
                <Icon name="list-bullets" size={18} color="var(--fg-subtle)" />
              </div>
              <div style={{ flex: 1 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <div style={{ fontFamily: 'Inter', fontSize: 17, fontWeight: 500, color: 'var(--fg)' }}>{l.name}</div>
                  {l.due > 0 && <CountBadge count={l.due} />}
                </div>
                <div style={{ fontFamily: 'Inter', fontSize: 13, color: 'var(--fg-muted)', marginTop: 2 }}>
                  {l.members} people · {l.rule}
                </div>
              </div>
              <Icon name="caret-right" size={16} color="var(--fg-subtle)" />
            </button>
          ))}
        </div>

        <button
          onClick={onCreate}
          style={{
            marginTop: 16, width: '100%', border: '1px dashed var(--line)',
            background: 'transparent', borderRadius: 16, padding: '16px',
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
            cursor: 'pointer', color: 'var(--fg-muted)',
            fontFamily: 'Inter', fontSize: 15, fontWeight: 500,
          }}
        >
          <Icon name="plus" size={16} color="var(--fg-muted)" />
          New list
        </button>

        <div style={{ fontFamily: 'Inter', fontSize: 13, color: 'var(--fg-subtle)', textAlign: 'center', marginTop: 20, lineHeight: 1.5, padding: '0 20px' }}>
          Drag to reorder. Lists higher up show first on home.
        </div>
      </div>
    </Screen>
  );
}

window.ListsManagerScreen = ListsManagerScreen;
