// Widget gallery — all five widget states on a neutral wallpaper.
// Platform-agnostic; rendered as standalone rectangles, not inside the phone frame.

// --- Shared helpers ---------------------------------------------------------

// Widget shell. Sizes are the "virtual" canvas; the gallery scales visually.
function WidgetShell({ w = 180, h = 180, children, style = {} }) {
  return (
    <div style={{
      width: w, height: h, borderRadius: 28,
      background: 'var(--surface)',
      boxShadow: '0 20px 40px rgba(33,30,28,.18), 0 4px 12px rgba(33,30,28,.08)',
      overflow: 'hidden', position: 'relative',
      fontFamily: 'Inter, sans-serif', color: 'var(--fg)',
      ...style,
    }}>
      {children}
    </div>
  );
}

function WidgetLabel({ caption, subcaption }) {
  return (
    <div style={{ marginTop: 12, textAlign: 'center' }}>
      <div style={{ fontFamily: 'Inter', fontSize: 14, fontWeight: 500, color: '#F0EBE4', letterSpacing: '-0.005em' }}>
        {caption}
      </div>
      {subcaption && (
        <div style={{ fontFamily: 'Inter', fontSize: 11, color: '#A8A098', marginTop: 2, letterSpacing: '0.02em' }}>
          {subcaption}
        </div>
      )}
    </div>
  );
}

// --- The five widget states ------------------------------------------------

function Widget2x2Active() {
  return (
    <WidgetShell w={180} h={180}>
      <div style={{ padding: 16, height: '100%', boxSizing: 'border-box', display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Avatar name="Sarah Chen" size={32} />
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--fg)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>Sarah</div>
            <div style={{ fontSize: 10, color: 'var(--fg-muted)', letterSpacing: '0.04em', textTransform: 'uppercase', fontWeight: 500 }}>Inner orbit</div>
          </div>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--fg-muted)', letterSpacing: '0.06em', textTransform: 'uppercase', fontWeight: 500 }}>Last called</div>
          <div style={{ fontSize: 18, fontWeight: 600, color: 'var(--fg)', marginTop: 2 }}>11d ago</div>
        </div>
        <button style={{
          width: '100%', height: 36, border: 0, borderRadius: 12,
          background: 'var(--accent)', color: '#fff',
          fontFamily: 'Inter', fontSize: 13, fontWeight: 500, cursor: 'pointer',
          display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
        }}>
          <Icon name="phone-call" size={14} color="#fff" />
          Call
        </button>
      </div>
    </WidgetShell>
  );
}

function Widget2x2Minimal() {
  return (
    <WidgetShell w={180} h={180}>
      <div style={{ padding: 16, height: '100%', boxSizing: 'border-box', display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{
            width: 32, height: 32, borderRadius: 999, background: 'var(--bg-subtle)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <Icon name="user" size={16} color="var(--fg-muted)" />
          </div>
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--fg)' }}>Contact</div>
            <div style={{ fontSize: 10, color: 'var(--fg-muted)', letterSpacing: '0.04em', textTransform: 'uppercase', fontWeight: 500 }}>Private</div>
          </div>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--fg-muted)', letterSpacing: '0.06em', textTransform: 'uppercase', fontWeight: 500 }}>Due</div>
          <div style={{ fontSize: 18, fontWeight: 600, color: 'var(--fg)', marginTop: 2 }}>Today</div>
        </div>
        <button style={{
          width: '100%', height: 36, border: '1px solid var(--line)', borderRadius: 12,
          background: 'transparent', color: 'var(--fg)',
          fontFamily: 'Inter', fontSize: 13, fontWeight: 500, cursor: 'pointer',
          display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
        }}>
          <Icon name="eye" size={14} color="var(--fg)" />
          Open
        </button>
      </div>
    </WidgetShell>
  );
}

function Widget2x2CaughtUp() {
  return (
    <WidgetShell w={180} h={180} style={{
      background: 'linear-gradient(160deg, #D8E3D6 0%, #C1D1BE 100%)',
    }}>
      <div style={{ padding: 18, height: '100%', boxSizing: 'border-box', display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
        <div style={{
          width: 40, height: 40, borderRadius: 999,
          background: 'rgba(255,255,255,0.5)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <Icon name="check" size={20} color="#3A5539" />
        </div>
        <div>
          <div style={{ fontSize: 17, fontWeight: 600, color: '#2F4530', letterSpacing: '-0.01em', lineHeight: 1.25, textWrap: 'pretty' }}>
            You're caught up.
          </div>
          <div style={{ fontSize: 12, color: '#4E6A4B', marginTop: 6, lineHeight: 1.4 }}>
            Rest. We'll resurface someone when it matters.
          </div>
        </div>
      </div>
    </WidgetShell>
  );
}

function Widget4x2Active() {
  return (
    <WidgetShell w={380} h={180}>
      <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 1px 1fr', height: '100%' }}>
        {/* Primary */}
        <div style={{ padding: 16, display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <Avatar name="Sarah Chen" size={40} />
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--fg)' }}>Sarah Chen</div>
              <div style={{ fontSize: 11, color: 'var(--fg-muted)', letterSpacing: '0.04em', textTransform: 'uppercase', fontWeight: 500, marginTop: 1 }}>
                Inner orbit · 11d ago
              </div>
            </div>
          </div>
          {/* Mini heat strip for answer-rate-right-now signal */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(24, 1fr)', gap: 1.5, height: 14, flex: 1 }}>
              {[0.02,0.01,0,0,0,0,0.05,0.18,0.32,0.42,0.35,0.15,0.12,0.22,0.35,0.48,0.58,0.72,0.88,0.94,0.85,0.60,0.30,0.10].map((v, i) => (
                <div key={i} style={{
                  background: v < 0.1 ? '#EFE8DC' : v < 0.3 ? '#DDC9AE' : v < 0.55 ? '#CE9F62' : v < 0.8 ? '#C8854A' : '#A76337',
                  borderRadius: 2,
                }} />
              ))}
            </div>
            <div style={{ fontSize: 10, color: 'var(--fg-muted)', fontWeight: 500, whiteSpace: 'nowrap' }}>82%</div>
          </div>
          <button style={{
            height: 40, border: 0, borderRadius: 12, background: 'var(--accent)', color: '#fff',
            fontFamily: 'Inter', fontSize: 14, fontWeight: 500, cursor: 'pointer',
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
          }}>
            <Icon name="phone-call" size={15} color="#fff" />
            Call Sarah
          </button>
        </div>
        <div style={{ background: 'var(--line-soft)' }} />
        {/* Alternatives */}
        <div style={{ padding: '14px 14px', display: 'flex', flexDirection: 'column', gap: 10 }}>
          <div style={{ fontSize: 10, color: 'var(--fg-muted)', letterSpacing: '0.08em', textTransform: 'uppercase', fontWeight: 500 }}>Also due</div>
          {[
            { name: 'Jamie Torres', sub: '3d · Inner' },
            { name: 'Priya Anand',  sub: '6d · Inner' },
            { name: 'Mom',          sub: '4d · Family' },
          ].map(p => (
            <div key={p.name} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <Avatar name={p.name} size={28} />
              <div style={{ minWidth: 0, flex: 1 }}>
                <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--fg)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{p.name}</div>
                <div style={{ fontSize: 10, color: 'var(--fg-muted)' }}>{p.sub}</div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </WidgetShell>
  );
}

function Widget4x2Swiping() {
  // "Swipe-between" state — captured as a transition: current card sliding off-right,
  // next card coming in from left, with carousel dots.
  return (
    <WidgetShell w={380} h={180} style={{ position: 'relative' }}>
      {/* Outgoing (right edge) */}
      <div style={{
        position: 'absolute', inset: 0, padding: 16,
        transform: 'translateX(74%) rotate(2deg)',
        opacity: 0.35,
        display: 'flex', flexDirection: 'column', justifyContent: 'space-between',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <Avatar name="Sarah Chen" size={40} />
          <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--fg)' }}>Sarah Chen</div>
        </div>
      </div>
      {/* Incoming (visible) */}
      <div style={{
        position: 'absolute', inset: 0, padding: 16,
        display: 'grid', gridTemplateColumns: '1.2fr 1px 1fr',
      }}>
        <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'space-between', paddingRight: 12 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <Avatar name="Jamie Torres" size={40} />
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--fg)' }}>Jamie Torres</div>
              <div style={{ fontSize: 11, color: 'var(--fg-muted)', letterSpacing: '0.04em', textTransform: 'uppercase', fontWeight: 500, marginTop: 1 }}>
                Inner orbit · 3d ago
              </div>
            </div>
          </div>
          <div style={{ fontSize: 12, color: 'var(--fg-muted)', lineHeight: 1.4 }}>
            Late-night caller. Peak answer rate around 10pm.
          </div>
          <button style={{
            height: 40, border: 0, borderRadius: 12, background: 'var(--accent)', color: '#fff',
            fontFamily: 'Inter', fontSize: 14, fontWeight: 500, cursor: 'pointer',
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
          }}>
            <Icon name="phone-call" size={15} color="#fff" />
            Call Jamie
          </button>
        </div>
        <div style={{ background: 'var(--line-soft)' }} />
        <div style={{ padding: '0 0 0 14px', display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 8 }}>
          <div style={{ fontSize: 10, color: 'var(--fg-muted)', letterSpacing: '0.08em', textTransform: 'uppercase', fontWeight: 500 }}>Swipe for more</div>
          <Icon name="caret-right" size={16} color="var(--accent-press)" />
        </div>
      </div>
      {/* Carousel dots */}
      <div style={{
        position: 'absolute', bottom: 10, left: 0, right: 0,
        display: 'flex', justifyContent: 'center', gap: 5,
      }}>
        {[0,1,2,3].map(i => (
          <div key={i} style={{
            width: i === 1 ? 14 : 5, height: 5, borderRadius: 999,
            background: i === 1 ? 'var(--accent)' : 'var(--line)',
          }} />
        ))}
      </div>
    </WidgetShell>
  );
}

// --- The gallery screen (not inside the phone frame) -----------------------

function WidgetGallery() {
  return (
    <div style={{
      width: 'min(1040px, calc(100vw - 80px))',
      padding: 48,
      borderRadius: 28,
      background: 'linear-gradient(160deg, #3D3631 0%, #2B2724 60%, #1F1C1A 100%)',
      boxShadow: '0 24px 60px rgba(0,0,0,.35)',
      display: 'flex', flexDirection: 'column', gap: 40,
    }}>
      <div>
        <div style={{ fontFamily: 'Inter', fontSize: 11, color: '#A8A098', letterSpacing: '0.12em', textTransform: 'uppercase', fontWeight: 500 }}>
          Home screen widgets
        </div>
        <div style={{ fontFamily: 'Inter', fontSize: 28, fontWeight: 600, color: '#F5F1EA', letterSpacing: '-0.01em', marginTop: 6 }}>
          Quiet reminders, glanceable.
        </div>
      </div>

      {/* 2x2 row */}
      <div>
        <div style={{ fontFamily: 'Inter', fontSize: 12, color: '#8F887F', letterSpacing: '0.08em', textTransform: 'uppercase', fontWeight: 500, marginBottom: 20 }}>
          Small · 2&times;2
        </div>
        <div style={{ display: 'flex', gap: 48, flexWrap: 'wrap' }}>
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
            <Widget2x2Active />
            <WidgetLabel caption="Active" subcaption="Primary suggestion" />
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
            <Widget2x2Minimal />
            <WidgetLabel caption="Minimal mode" subcaption="Privacy-first, no names" />
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
            <Widget2x2CaughtUp />
            <WidgetLabel caption="Caught up" subcaption="Nothing due — rest" />
          </div>
        </div>
      </div>

      {/* 4x2 row */}
      <div>
        <div style={{ fontFamily: 'Inter', fontSize: 12, color: '#8F887F', letterSpacing: '0.08em', textTransform: 'uppercase', fontWeight: 500, marginBottom: 20 }}>
          Medium · 4&times;2
        </div>
        <div style={{ display: 'flex', gap: 48, flexWrap: 'wrap' }}>
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
            <Widget4x2Active />
            <WidgetLabel caption="Active" subcaption="Primary + 3 alternatives" />
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
            <Widget4x2Swiping />
            <WidgetLabel caption="Mid-swipe" subcaption="Between suggestions" />
          </div>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, {
  WidgetGallery,
  Widget2x2Active, Widget2x2Minimal, Widget2x2CaughtUp,
  Widget4x2Active, Widget4x2Swiping,
});
