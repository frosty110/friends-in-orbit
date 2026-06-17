// Simple warm phone frame — replaces the Material 3 Android starter
// We render our own bezel to preserve the warm palette (the app is Android,
// but the device shell should not fight the brand).

function PhoneFrame({ children, width = 392, height = 820, dark = false }) {
  return (
    <div style={{
      width, height, borderRadius: 44, padding: 6, boxSizing: 'border-box',
      background: dark ? '#0C0B0A' : '#3A3531',
      boxShadow: dark
        ? '0 40px 80px rgba(0,0,0,0.5), inset 0 0 0 1px rgba(255,255,255,0.04)'
        : '0 40px 80px rgba(33,30,28,0.25), inset 0 0 0 1px rgba(255,255,255,0.06)',
    }}>
      <div style={{
        width: '100%', height: '100%', borderRadius: 38, overflow: 'hidden',
        background: dark ? '#1F1C1A' : '#FAF6F0', position: 'relative',
        display: 'flex', flexDirection: 'column',
      }}>
        {/* Status bar */}
        <div style={{
          height: 34, flexShrink: 0, display: 'flex', alignItems: 'center',
          justifyContent: 'space-between', padding: '0 24px',
          fontFamily: 'Inter, system-ui', fontSize: 14, fontWeight: 600,
          color: dark ? '#F0EBE4' : '#211E1C',
          position: 'relative', zIndex: 3,
        }}>
          <span>9:41</span>
          {/* camera dot */}
          <div style={{
            position: 'absolute', left: '50%', top: 10, transform: 'translateX(-50%)',
            width: 10, height: 10, borderRadius: 999, background: '#000',
          }} />
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <svg width="16" height="11" viewBox="0 0 16 11"><path d="M8 10.5 .5 3.5a10.4 10.4 0 0 1 15 0L8 10.5Z" fill="currentColor"/></svg>
            <svg width="14" height="10" viewBox="0 0 14 10"><rect x="0" y="2" width="10" height="6" rx="1.2" fill="none" stroke="currentColor" strokeWidth="1"/><rect x="1.5" y="3.5" width="7" height="3" fill="currentColor"/><rect x="11" y="3.5" width="1.5" height="3" rx="0.5" fill="currentColor"/></svg>
          </div>
        </div>
        <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          {children}
        </div>
        {/* Home indicator */}
        <div style={{
          height: 20, flexShrink: 0, display: 'flex',
          alignItems: 'center', justifyContent: 'center',
          background: dark ? '#1F1C1A' : '#FAF6F0',
        }}>
          <div style={{
            width: 124, height: 4, borderRadius: 999,
            background: dark ? '#F0EBE4' : '#211E1C', opacity: 0.85,
          }} />
        </div>
      </div>
    </div>
  );
}

window.PhoneFrame = PhoneFrame;
