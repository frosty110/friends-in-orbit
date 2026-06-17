// Settings screen
function SettingsScreen({ onBack }) {
  const [minimal, setMinimal] = React.useState(false);
  const [biometric, setBiometric] = React.useState(true);
  const [digest, setDigest] = React.useState(true);

  return (
    <Screen>
      <AppBar
        title="Settings"
        leading={<IconBtn name="arrow-left" aria-label="Back" onClick={onBack} />}
      />
      <div style={{ flex: 1, overflowY: 'auto', padding: '4px 16px 32px' }}>
        <SettingGroup title="Privacy">
          <ToggleRow label="Minimal mode" sub="Hide list names when the app loses focus" value={minimal} onChange={setMinimal} />
          <ToggleRow label="Biometric lock" sub="Use fingerprint or face on open" value={biometric} onChange={setBiometric} />
        </SettingGroup>

        <SettingGroup title="Notifications">
          <ToggleRow label="Daily digest" sub="One summary at 9:00 am" value={digest} onChange={setDigest} />
          <NavRow label="Per-list notifications" sub="4 of 5 lists on" />
          <NavRow label="Incoming call follow-up" sub="Ask me to call back" />
        </SettingGroup>

        <SettingGroup title="Data">
          <NavRow label="Import call log" sub="Last 90 days · last synced 2h ago" />
          <NavRow label="Export my data" sub="Encrypted JSON" />
        </SettingGroup>

        <SettingGroup title="About">
          <NavRow label="My Orbit" sub="Version 0.9.4" />
          <NavRow label="Help" />
        </SettingGroup>
      </div>
    </Screen>
  );
}

function SettingGroup({ title, children }) {
  return (
    <div style={{ marginBottom: 20 }}>
      <div style={{ fontFamily: 'Inter', fontSize: 12, fontWeight: 500, color: 'var(--fg-muted)', letterSpacing: '0.08em', textTransform: 'uppercase', padding: '16px 8px 10px' }}>
        {title}
      </div>
      <div style={{ background: 'var(--surface)', borderRadius: 16, boxShadow: 'var(--shadow-card)', overflow: 'hidden' }}>
        {children}
      </div>
    </div>
  );
}

function ToggleRow({ label, sub, value, onChange }) {
  return (
    <button
      onClick={() => onChange(!value)}
      style={{
        width: '100%', border: 0, background: 'transparent', cursor: 'pointer',
        display: 'flex', alignItems: 'center', gap: 12, padding: '14px 16px', textAlign: 'left',
        borderBottom: '1px solid var(--line-soft)',
      }}
    >
      <div style={{ flex: 1 }}>
        <div style={{ fontFamily: 'Inter', fontSize: 16, color: 'var(--fg)' }}>{label}</div>
        {sub && <div style={{ fontFamily: 'Inter', fontSize: 13, color: 'var(--fg-muted)', marginTop: 2 }}>{sub}</div>}
      </div>
      <div style={{
        width: 44, height: 26, borderRadius: 999,
        background: value ? 'var(--accent)' : 'var(--line)',
        position: 'relative', transition: 'background 200ms',
      }}>
        <div style={{
          position: 'absolute', top: 3, left: value ? 21 : 3,
          width: 20, height: 20, borderRadius: 999, background: '#fff',
          boxShadow: '0 1px 3px rgba(0,0,0,.15)', transition: 'left 200ms var(--ease-out)',
        }} />
      </div>
    </button>
  );
}

function NavRow({ label, sub }) {
  return (
    <button style={{
      width: '100%', border: 0, background: 'transparent', cursor: 'pointer',
      display: 'flex', alignItems: 'center', gap: 12, padding: '14px 16px', textAlign: 'left',
      borderBottom: '1px solid var(--line-soft)',
    }}>
      <div style={{ flex: 1 }}>
        <div style={{ fontFamily: 'Inter', fontSize: 16, color: 'var(--fg)' }}>{label}</div>
        {sub && <div style={{ fontFamily: 'Inter', fontSize: 13, color: 'var(--fg-muted)', marginTop: 2 }}>{sub}</div>}
      </div>
      <Icon name="caret-right" size={16} color="var(--fg-subtle)" />
    </button>
  );
}

Object.assign(window, { SettingsScreen, SettingGroup, ToggleRow, NavRow });
