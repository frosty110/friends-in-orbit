// Contact Detail — photo, name, all lists, history, notes, stats
function ContactDetailScreen({ name = 'Sarah Chen', onBack, onCall }) {
  const history = [
    { dir: 'out', when: '11 days ago', len: '14 min' },
    { dir: 'in',  when: '3 weeks ago', len: '8 min' },
    { dir: 'out', when: '6 weeks ago', len: '22 min' },
    { dir: 'out', when: '2 months ago', len: '11 min' },
  ];
  const notes = [
    { when: '11 days ago', body: 'She\u2019s starting pottery classes on Thursdays. Ask about it.' },
    { when: '6 weeks ago', body: 'Mom is recovering well. Bring it up next time.' },
  ];

  return (
    <Screen>
      <AppBar
        title=""
        leading={<IconBtn name="arrow-left" aria-label="Back" onClick={onBack} />}
        trailing={<IconBtn name="dots-three" aria-label="More" />}
      />
      <div style={{ flex: 1, overflowY: 'auto' }}>
        {/* Hero */}
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '8px 24px 20px' }}>
          <Avatar name={name} size={96} />
          <div style={{ fontFamily: 'Inter', fontSize: 26, fontWeight: 600, color: 'var(--fg)', marginTop: 14, letterSpacing: '-0.01em' }}>
            {name}
          </div>
          <div style={{ fontFamily: 'Inter', fontSize: 14, color: 'var(--fg-muted)', marginTop: 4 }}>
            +1 (415) 555‑0182
          </div>
          <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
            <Chip tone="terracotta">Inner orbit</Chip>
            <Chip tone="amber">Family</Chip>
          </div>
        </div>

        {/* Primary action */}
        <div style={{ padding: '0 20px 20px' }}>
          <Button variant="primary" onClick={onCall} icon="phone-call" style={{ width: '100%', height: 52 }}>
            Call Sarah
          </Button>
        </div>

        {/* Stats */}
        <div style={{ padding: '0 20px 20px' }}>
          <div style={{
            background: 'var(--surface)', borderRadius: 16, padding: 16,
            display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12,
            boxShadow: 'var(--shadow-card)',
          }}>
            <Stat label="Last call" value="11d ago" />
            <Stat label="Total calls" value="47" />
            <Stat label="Avg length" value="14 min" />
          </div>
          <div style={{ fontFamily: 'Inter', fontSize: 13, color: 'var(--fg-muted)', marginTop: 10, textAlign: 'center' }}>
            Usually calls in the evening · longest gap 44 days
          </div>
        </div>

        {/* History */}
        <Section title="Call history">
          {history.map((h, i) => (
            <div key={i} style={{
              display: 'flex', alignItems: 'center', gap: 12, padding: '12px 20px',
              borderTop: i === 0 ? 'none' : '1px solid var(--line-soft)',
            }}>
              <Icon name={h.dir === 'out' ? 'phone-outgoing' : 'phone-incoming'} size={18} color="var(--fg-muted)" />
              <div style={{ flex: 1, fontFamily: 'Inter', fontSize: 15, color: 'var(--fg)' }}>
                {h.dir === 'out' ? 'You called' : 'They called'}
              </div>
              <div style={{ fontFamily: 'Inter', fontSize: 13, color: 'var(--fg-muted)' }}>{h.when} · {h.len}</div>
            </div>
          ))}
        </Section>

        {/* Notes */}
        <Section title="Notes" action="Add note" actionIcon="plus">
          {notes.map((n, i) => (
            <div key={i} style={{ padding: '14px 20px', borderTop: i === 0 ? 'none' : '1px solid var(--line-soft)' }}>
              <div style={{ fontFamily: 'Inter', fontSize: 15, color: 'var(--fg)', lineHeight: 1.5 }}>{n.body}</div>
              <div style={{ fontFamily: 'Inter', fontSize: 12, color: 'var(--fg-subtle)', marginTop: 4 }}>{n.when}</div>
            </div>
          ))}
        </Section>

        <div style={{ padding: '20px 20px 40px' }}>
          <Button variant="destructive" icon="pause-circle" style={{ width: '100%' }}>Pause Sarah</Button>
        </div>
      </div>
    </Screen>
  );
}

function Section({ title, action, actionIcon, children }) {
  return (
    <div style={{ margin: '0 20px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 0 10px' }}>
        <div style={{ fontFamily: 'Inter', fontSize: 12, fontWeight: 500, color: 'var(--fg-muted)', letterSpacing: '0.08em', textTransform: 'uppercase' }}>
          {title}
        </div>
        {action && (
          <button style={{
            background: 'transparent', border: 0, color: 'var(--accent)',
            fontFamily: 'Inter', fontSize: 13, fontWeight: 500, cursor: 'pointer',
            display: 'inline-flex', alignItems: 'center', gap: 4,
          }}>
            {actionIcon && <Icon name={actionIcon} size={14} color="var(--accent)" />}
            {action}
          </button>
        )}
      </div>
      <div style={{ background: 'var(--surface)', borderRadius: 16, boxShadow: 'var(--shadow-card)', overflow: 'hidden' }}>
        {children}
      </div>
    </div>
  );
}

Object.assign(window, { ContactDetailScreen, Section });
