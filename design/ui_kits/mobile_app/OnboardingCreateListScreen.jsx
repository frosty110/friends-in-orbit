// Onboarding 2 — Create first list
// Template picker with a blank option.
function OnboardingCreateListScreen({ onBack, onContinue }) {
  const templates = [
    { id: 'inner',   name: 'Inner orbit',  sub: 'Closest friends. Every 1–2 weeks.',         icon: 'heart',   tone: 'terracotta' },
    { id: 'family',  name: 'Family',       sub: 'Parents, siblings. Weekly-ish.',             icon: 'users',   tone: 'amber' },
    { id: 'mentors', name: 'Mentors',      sub: 'People you look up to. Once a month.',       icon: 'star',    tone: 'sage' },
    { id: 'drift',   name: 'Drifted',      sub: 'Friends you miss but haven\u2019t called.',  icon: 'clock-counter-clockwise', tone: 'stone' },
  ];

  const [selected, setSelected] = React.useState('inner');

  return (
    <Screen>
      <AppBar
        title=""
        subtle
        leading={<IconBtn name="arrow-left" aria-label="Back" onClick={onBack} />}
        trailing={
          <div style={{ padding: '0 12px', fontFamily: 'Inter', fontSize: 13, color: 'var(--fg-subtle)', letterSpacing: '0.04em' }}>
            2 of 3
          </div>
        }
      />
      <div style={{ flex: 1, overflowY: 'auto', padding: '8px 20px 20px' }}>
        <div style={{ fontFamily: 'Inter', fontSize: 28, fontWeight: 600, color: 'var(--fg)', letterSpacing: '-0.01em', lineHeight: 1.2 }}>
          Start with one list
        </div>
        <div style={{ fontFamily: 'Inter', fontSize: 16, color: 'var(--fg-muted)', marginTop: 10, lineHeight: 1.5, textWrap: 'pretty' }}>
          Pick a starting point. You can rename, retune, or add more later.
        </div>

        <div style={{ marginTop: 24, display: 'flex', flexDirection: 'column', gap: 10 }}>
          {templates.map(t => {
            const active = selected === t.id;
            const tones = {
              terracotta: { bg: '#EDD6CE', fg: '#9B4A32' },
              sage:       { bg: '#D8E3D6', fg: '#4E6A4B' },
              amber:      { bg: '#EEE3B8', fg: '#756321' },
              stone:      { bg: '#E3DFDB', fg: '#524B45' },
            };
            const tone = tones[t.tone];
            return (
              <button
                key={t.id}
                onClick={() => setSelected(t.id)}
                style={{
                  width: '100%', border: active ? '2px solid var(--accent)' : '2px solid transparent',
                  background: 'var(--surface)', cursor: 'pointer', textAlign: 'left',
                  display: 'flex', gap: 14, alignItems: 'center', padding: '14px 14px',
                  borderRadius: 16, boxShadow: 'var(--shadow-card)',
                  transition: 'border-color 120ms',
                }}
              >
                <div style={{
                  width: 44, height: 44, borderRadius: 12, flexShrink: 0,
                  background: tone.bg,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                }}>
                  <Icon name={t.icon} size={20} color={tone.fg} />
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontFamily: 'Inter', fontSize: 16, fontWeight: 600, color: 'var(--fg)' }}>{t.name}</div>
                  <div style={{ fontFamily: 'Inter', fontSize: 13, color: 'var(--fg-muted)', marginTop: 2, textWrap: 'pretty' }}>{t.sub}</div>
                </div>
                <div style={{
                  width: 22, height: 22, borderRadius: 999,
                  border: active ? 0 : '2px solid var(--line)',
                  background: active ? 'var(--accent)' : 'transparent',
                  display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
                }}>
                  {active && <Icon name="check" size={14} color="#fff" />}
                </div>
              </button>
            );
          })}

          <button
            onClick={() => setSelected('blank')}
            style={{
              width: '100%', border: selected === 'blank' ? '2px solid var(--accent)' : '1px dashed var(--line)',
              background: 'transparent', cursor: 'pointer', textAlign: 'center',
              padding: '16px', borderRadius: 16,
              fontFamily: 'Inter', fontSize: 15, fontWeight: 500, color: 'var(--fg-muted)',
              display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
            }}
          >
            <Icon name="plus" size={16} color="var(--fg-muted)" />
            Start from blank
          </button>
        </div>
      </div>

      <div style={{ padding: '12px 20px 28px', background: 'var(--bg)', borderTop: '1px solid var(--line-soft)' }}>
        <Button variant="primary" onClick={onContinue} style={{ width: '100%', height: 52 }}>
          Continue
        </Button>
      </div>
    </Screen>
  );
}

window.OnboardingCreateListScreen = OnboardingCreateListScreen;
