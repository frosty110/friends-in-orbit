// List Configuration — rule template + parameters for one list
function ListConfigScreen({ listName = 'Inner orbit', onBack, onSave }) {
  const [rule, setRule] = React.useState('roundrobin');
  const [interval, setInterval] = React.useState(14);
  const [activeStart, setActiveStart] = React.useState(18);
  const [activeEnd, setActiveEnd] = React.useState(22);
  const [notify, setNotify] = React.useState(true);

  const rules = [
    { id: 'roundrobin', name: 'Round robin',       sub: 'Rotate through everyone evenly',   icon: 'shuffle' },
    { id: 'recency',    name: 'Recency',           sub: 'Longest-silent surfaces first',    icon: 'clock-counter-clockwise' },
    { id: 'spaced',     name: 'Spaced repetition', sub: 'Interval grows if you keep in touch', icon: 'calendar-blank' },
    { id: 'manual',     name: 'Manual',            sub: 'You pick, no suggestions',          icon: 'list-bullets' },
  ];

  const formatHour = h => {
    const p = h >= 12 ? 'pm' : 'am';
    const h12 = h === 0 ? 12 : h > 12 ? h - 12 : h;
    return `${h12}${p}`;
  };

  return (
    <Screen>
      <AppBar
        title={listName}
        leading={<IconBtn name="arrow-left" aria-label="Back" onClick={onBack} />}
        trailing={
          <button
            onClick={onSave}
            style={{
              height: 36, padding: '0 14px', margin: '0 8px', border: 0, borderRadius: 10,
              background: 'var(--accent)', color: '#fff', fontFamily: 'Inter', fontSize: 14, fontWeight: 500, cursor: 'pointer',
            }}
          >Save</button>
        }
      />
      <div style={{ flex: 1, overflowY: 'auto', padding: '4px 16px 32px' }}>
        {/* Rule picker */}
        <SettingGroup title="Cadence">
          {rules.map((r, i) => (
            <button
              key={r.id}
              onClick={() => setRule(r.id)}
              style={{
                width: '100%', border: 0, background: 'transparent', cursor: 'pointer', textAlign: 'left',
                display: 'flex', alignItems: 'center', gap: 12, padding: '14px 16px',
                borderBottom: i < rules.length - 1 ? '1px solid var(--line-soft)' : 'none',
              }}
            >
              <div style={{
                width: 36, height: 36, borderRadius: 10,
                background: rule === r.id ? 'var(--accent-tint)' : 'var(--bg-subtle)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
              }}>
                <Icon name={r.icon} size={18} color={rule === r.id ? 'var(--accent-press)' : 'var(--fg-muted)'} />
              </div>
              <div style={{ flex: 1 }}>
                <div style={{ fontFamily: 'Inter', fontSize: 16, color: 'var(--fg)' }}>{r.name}</div>
                <div style={{ fontFamily: 'Inter', fontSize: 13, color: 'var(--fg-muted)', marginTop: 2 }}>{r.sub}</div>
              </div>
              <div style={{
                width: 20, height: 20, borderRadius: 999, border: '2px solid var(--line)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                background: rule === r.id ? 'var(--accent)' : 'transparent',
                borderColor: rule === r.id ? 'var(--accent)' : 'var(--line)',
              }}>
                {rule === r.id && <div style={{ width: 8, height: 8, borderRadius: 999, background: '#fff' }} />}
              </div>
            </button>
          ))}
        </SettingGroup>

        {/* Interval slider */}
        <SettingGroup title="Interval">
          <div style={{ padding: '18px 16px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 12 }}>
              <div style={{ fontFamily: 'Inter', fontSize: 15, color: 'var(--fg)' }}>Aim for every</div>
              <div style={{ fontFamily: 'Inter', fontSize: 20, fontWeight: 600, color: 'var(--accent-press)', letterSpacing: '-0.01em' }}>
                {interval} {interval === 1 ? 'day' : 'days'}
              </div>
            </div>
            <input
              type="range" min="1" max="60" value={interval}
              onChange={e => setInterval(Number(e.target.value))}
              style={{ width: '100%', accentColor: 'var(--accent)' }}
            />
            <div style={{ display: 'flex', justifyContent: 'space-between', fontFamily: 'Inter', fontSize: 11, color: 'var(--fg-subtle)', marginTop: 4 }}>
              <span>1d</span><span>2w</span><span>1m</span><span>2m</span>
            </div>
          </div>
        </SettingGroup>

        {/* Active hours */}
        <SettingGroup title="Active hours">
          <div style={{ padding: '18px 16px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 12 }}>
              <div style={{ fontFamily: 'Inter', fontSize: 15, color: 'var(--fg)' }}>Only suggest between</div>
              <div style={{ fontFamily: 'Inter', fontSize: 16, fontWeight: 600, color: 'var(--fg)' }}>
                {formatHour(activeStart)} – {formatHour(activeEnd)}
              </div>
            </div>
            {/* Dual-ended visual track */}
            <div style={{ position: 'relative', height: 6, background: 'var(--line-soft)', borderRadius: 999, margin: '16px 8px 8px' }}>
              <div style={{
                position: 'absolute', top: 0, bottom: 0,
                left: `${(activeStart / 24) * 100}%`,
                right: `${100 - (activeEnd / 24) * 100}%`,
                background: 'var(--accent)', borderRadius: 999,
              }} />
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontFamily: 'Inter', fontSize: 11, color: 'var(--fg-subtle)', padding: '0 4px' }}>
              <span>12a</span><span>6a</span><span>12p</span><span>6p</span><span>12a</span>
            </div>
          </div>
        </SettingGroup>

        {/* Notifications */}
        <SettingGroup title="Notifications">
          <ToggleRow label="Prompt me" sub="Send a gentle reminder when someone's due" value={notify} onChange={setNotify} />
        </SettingGroup>

        {/* Destructive */}
        <div style={{ marginTop: 16 }}>
          <button style={{
            width: '100%', height: 48, border: '1px solid var(--line)', background: 'transparent',
            borderRadius: 12, fontFamily: 'Inter', fontSize: 15, fontWeight: 500,
            color: 'var(--danger)', cursor: 'pointer',
          }}>
            Delete list
          </button>
          <div style={{ fontFamily: 'Inter', fontSize: 12, color: 'var(--fg-subtle)', textAlign: 'center', marginTop: 10, padding: '0 20px', lineHeight: 1.5 }}>
            People stay in your contacts. Only the list and its cadence are removed.
          </div>
        </div>
      </div>
    </Screen>
  );
}

window.ListConfigScreen = ListConfigScreen;
