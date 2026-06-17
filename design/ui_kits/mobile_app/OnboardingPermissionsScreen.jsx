// Onboarding 1 — Permissions explainer
// Plain-language case for call log + contacts access.
function OnboardingPermissionsScreen({ onBack, onContinue }) {
  const [logGranted, setLogGranted] = React.useState(false);
  const [contactsGranted, setContactsGranted] = React.useState(false);

  const canContinue = logGranted && contactsGranted;

  return (
    <Screen>
      <AppBar
        title=""
        subtle
        leading={<IconBtn name="arrow-left" aria-label="Back" onClick={onBack} />}
        trailing={
          <div style={{ padding: '0 12px', fontFamily: 'Inter', fontSize: 13, color: 'var(--fg-subtle)', letterSpacing: '0.04em' }}>
            1 of 3
          </div>
        }
      />
      <div style={{ flex: 1, overflowY: 'auto', padding: '8px 20px 20px' }}>
        <div style={{ fontFamily: 'Inter', fontSize: 28, fontWeight: 600, color: 'var(--fg)', letterSpacing: '-0.01em', lineHeight: 1.2 }}>
          Two quiet permissions
        </div>
        <div style={{ fontFamily: 'Inter', fontSize: 16, color: 'var(--fg-muted)', marginTop: 10, lineHeight: 1.5, textWrap: 'pretty' }}>
          My Orbit runs entirely on your device. Nothing leaves. Here's what each one does.
        </div>

        <div style={{ marginTop: 24, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <PermissionCard
            icon="phone-call"
            title="Call log"
            granted={logGranted}
            onGrant={() => setLogGranted(true)}
            body="So we notice when you reach out and adjust without nagging."
            note="Read-only. Never shared."
          />
          <PermissionCard
            icon="users"
            title="Contacts"
            granted={contactsGranted}
            onGrant={() => setContactsGranted(true)}
            body="So you can add the right people to lists by name, not phone number."
            note="Stays on device. We don't upload your address book."
          />
        </div>

        <div style={{
          marginTop: 20, padding: '14px 16px',
          background: 'var(--bg-subtle)', borderRadius: 14,
          display: 'flex', gap: 10, alignItems: 'flex-start',
        }}>
          <Icon name="shield-check" size={18} color="var(--accent-press)" style={{ marginTop: 2 }} />
          <div style={{ fontFamily: 'Inter', fontSize: 13, color: 'var(--fg-muted)', lineHeight: 1.5, textWrap: 'pretty' }}>
            No account. No cloud. No ads. Your orbit is just for you — if you delete the app, every note and history goes with it.
          </div>
        </div>
      </div>

      <div style={{ padding: '12px 20px 28px', background: 'var(--bg)', borderTop: '1px solid var(--line-soft)' }}>
        <Button
          variant="primary"
          onClick={canContinue ? onContinue : undefined}
          style={{
            width: '100%', height: 52,
            opacity: canContinue ? 1 : 0.4,
            cursor: canContinue ? 'pointer' : 'not-allowed',
          }}
        >
          Continue
        </Button>
      </div>
    </Screen>
  );
}

function PermissionCard({ icon, title, body, note, granted, onGrant }) {
  return (
    <div style={{
      background: 'var(--surface)', borderRadius: 16, padding: 16,
      boxShadow: 'var(--shadow-card)',
      display: 'flex', gap: 14, alignItems: 'flex-start',
    }}>
      <div style={{
        width: 40, height: 40, borderRadius: 12, flexShrink: 0,
        background: granted ? 'var(--sage-tint, #D8E3D6)' : 'var(--accent-tint)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}>
        <Icon name={granted ? 'check' : icon} size={18} color={granted ? '#4E6A4B' : 'var(--accent-press)'} />
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontFamily: 'Inter', fontSize: 16, fontWeight: 600, color: 'var(--fg)' }}>{title}</div>
        <div style={{ fontFamily: 'Inter', fontSize: 14, color: 'var(--fg-muted)', marginTop: 4, lineHeight: 1.5, textWrap: 'pretty' }}>{body}</div>
        <div style={{ fontFamily: 'Inter', fontSize: 12, color: 'var(--fg-subtle)', marginTop: 6 }}>{note}</div>
        {!granted && (
          <button
            onClick={onGrant}
            style={{
              marginTop: 12, height: 36, padding: '0 14px', border: 0, borderRadius: 10,
              background: 'var(--bg-subtle)', color: 'var(--fg)',
              fontFamily: 'Inter', fontSize: 14, fontWeight: 500, cursor: 'pointer',
            }}
          >
            Allow
          </button>
        )}
        {granted && (
          <div style={{
            marginTop: 12, height: 28, padding: '0 10px', display: 'inline-flex',
            alignItems: 'center', gap: 6, borderRadius: 8,
            background: '#D8E3D6', color: '#4E6A4B',
            fontFamily: 'Inter', fontSize: 13, fontWeight: 500,
          }}>
            <Icon name="check" size={14} color="#4E6A4B" />
            Allowed
          </div>
        )}
      </div>
    </div>
  );
}

Object.assign(window, { OnboardingPermissionsScreen, PermissionCard });
