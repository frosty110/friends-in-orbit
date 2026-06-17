// Card View — the surfacing interaction. Swipeable contact card.
function CardViewScreen({ listName = 'Inner orbit', onBack, onCall, onDone }) {
  const [idx, setIdx] = React.useState(0);
  const [drag, setDrag] = React.useState(0);   // x offset px
  const [animating, setAnimating] = React.useState(false);
  const startX = React.useRef(null);

  // heat: 24 values 0..1 representing pickup rate by hour-of-day.
  // bestWindow: derived human string; pickupRate: overall.
  const queue = [
    {
      name: 'Sarah Chen', lastCalled: '11d ago', avgLen: '14 min', pickupRate: '82%',
      list: 'Inner orbit', bestWindow: '6:42p \u2013 9:15p',
      heat: [0.02,0.01,0.00,0.00,0.00,0.00,0.05,0.18,0.32,0.42,0.35,0.15,0.12,0.22,0.35,0.48,0.58,0.72,0.88,0.94,0.85,0.60,0.30,0.10],
    },
    {
      name: 'Jamie Torres', lastCalled: '3d ago', avgLen: '22 min', pickupRate: '71%',
      list: 'Inner orbit', bestWindow: '9:30p \u2013 11:45p',
      heat: [0.25,0.10,0.02,0.00,0.00,0.00,0.00,0.05,0.08,0.10,0.08,0.05,0.10,0.15,0.12,0.18,0.22,0.30,0.45,0.62,0.85,0.94,0.80,0.55],
    },
    {
      name: 'Marcus Reed', lastCalled: '18d ago', avgLen: '9 min', pickupRate: '64%',
      list: 'Inner orbit', bestWindow: 'Sat \u2013 Sun afternoons',
      heat: [0.05,0.02,0.00,0.00,0.00,0.00,0.08,0.25,0.40,0.45,0.50,0.48,0.55,0.60,0.58,0.52,0.45,0.30,0.22,0.18,0.12,0.10,0.08,0.06],
    },
    {
      name: 'Priya Anand', lastCalled: '6d ago', avgLen: '31 min', pickupRate: '89%',
      list: 'Inner orbit', bestWindow: '7:00a \u2013 9:30a',
      heat: [0.02,0.00,0.00,0.00,0.00,0.15,0.65,0.90,0.92,0.78,0.40,0.22,0.18,0.15,0.12,0.18,0.25,0.30,0.28,0.20,0.15,0.08,0.05,0.02],
    },
  ];

  const contact = queue[idx % queue.length];
  const nextContact = queue[(idx + 1) % queue.length];

  const onPointerDown = (e) => {
    if (animating) return;
    startX.current = e.clientX ?? e.touches?.[0]?.clientX;
  };
  const onPointerMove = (e) => {
    if (startX.current == null) return;
    const x = e.clientX ?? e.touches?.[0]?.clientX;
    setDrag(x - startX.current);
  };
  const commit = (dir) => {
    setAnimating(true);
    setDrag(dir * 500);
    setTimeout(() => {
      setIdx(i => i + 1);
      setDrag(0);
      setAnimating(false);
    }, 300);
  };
  const onPointerUp = () => {
    if (startX.current == null) return;
    startX.current = null;
    if (Math.abs(drag) > 100) commit(drag > 0 ? 1 : -1);
    else setDrag(0);
  };

  const rotate = drag / 18;  // up to ~8deg
  const opacity = 1 - Math.min(Math.abs(drag) / 500, 0.3);

  return (
    <Screen>
      <AppBar
        title={listName}
        leading={<IconBtn name="arrow-left" aria-label="Back" onClick={onBack} />}
        trailing={<IconBtn name="dots-three" aria-label="More" />}
      />

      {/* Progress dots */}
      <div style={{ display: 'flex', justifyContent: 'center', gap: 6, padding: '0 0 8px' }}>
        {queue.map((_, i) => (
          <div key={i} style={{
            width: i === idx % queue.length ? 20 : 6, height: 6, borderRadius: 999,
            background: i === idx % queue.length ? 'var(--accent)' : 'var(--line)',
            transition: 'all 250ms var(--ease-out)',
          }} />
        ))}
      </div>

      {/* Card stage */}
      <div style={{ flex: 1, position: 'relative', padding: '12px 24px 0', overflow: 'hidden' }}>
        {/* Next card peek */}
        <div style={{
          position: 'absolute', inset: '12px 24px 0',
          background: 'var(--surface)', borderRadius: 24,
          boxShadow: 'var(--shadow-card)', transform: 'scale(0.94) translateY(14px)',
          opacity: 0.6, pointerEvents: 'none',
        }}>
          <ContactCardFace contact={nextContact} blurred />
        </div>

        {/* Active card */}
        <div
          onMouseDown={onPointerDown} onMouseMove={onPointerMove} onMouseUp={onPointerUp} onMouseLeave={onPointerUp}
          onTouchStart={onPointerDown} onTouchMove={onPointerMove} onTouchEnd={onPointerUp}
          style={{
            position: 'relative', zIndex: 2, height: '100%',
            background: 'var(--surface)', borderRadius: 24,
            boxShadow: 'var(--shadow-hero)',
            transform: `translateX(${drag}px) rotate(${rotate}deg)`,
            opacity,
            transition: animating ? 'transform 300ms var(--ease-spring), opacity 300ms' : startX.current != null ? 'none' : 'transform 250ms var(--ease-spring)',
            touchAction: 'pan-y',
            cursor: 'grab',
            userSelect: 'none',
          }}
        >
          <ContactCardFace contact={contact} />
          {/* Swipe hints */}
          <div style={{
            position: 'absolute', top: 24, left: 24,
            padding: '6px 14px', borderRadius: 999, background: 'var(--orbit-brick, #A04838)', color: '#fff',
            fontFamily: 'Inter', fontSize: 13, fontWeight: 600, letterSpacing: '0.05em', textTransform: 'uppercase',
            opacity: drag < -30 ? Math.min(-drag / 120, 1) : 0,
            transform: `rotate(-12deg)`,
          }}>Later</div>
          <div style={{
            position: 'absolute', top: 24, right: 24,
            padding: '6px 14px', borderRadius: 999, background: 'var(--orbit-sage, #87A383)', color: '#fff',
            fontFamily: 'Inter', fontSize: 13, fontWeight: 600, letterSpacing: '0.05em', textTransform: 'uppercase',
            opacity: drag > 30 ? Math.min(drag / 120, 1) : 0,
            transform: 'rotate(12deg)',
          }}>Sooner</div>
        </div>
      </div>

      {/* Action bar */}
      <div style={{
        flexShrink: 0, padding: '20px 24px 28px',
        display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12,
      }}>
        <button
          onClick={() => commit(-1)}
          aria-label="Later"
          style={{
            width: 56, height: 56, borderRadius: 999, border: '1px solid var(--line)',
            background: 'var(--surface)', cursor: 'pointer',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            boxShadow: 'var(--shadow-sm)',
          }}
        >
          <Icon name="arrow-left" size={22} color="var(--fg-muted)" />
        </button>

        <Button variant="primary" onClick={onCall} icon="phone-call" style={{ flex: 1, height: 56, fontSize: 17 }}>
          Call {contact.name.split(' ')[0]}
        </Button>

        <button
          onClick={() => commit(1)}
          aria-label="Sooner"
          style={{
            width: 56, height: 56, borderRadius: 999, border: '1px solid var(--line)',
            background: 'var(--surface)', cursor: 'pointer',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            boxShadow: 'var(--shadow-sm)',
          }}
        >
          <Icon name="arrow-right" size={22} color="var(--fg-muted)" />
        </button>
      </div>

      <button onClick={() => commit(0)} style={{
        background: 'transparent', border: 0, color: 'var(--fg-subtle)',
        fontFamily: 'Inter', fontSize: 14, paddingBottom: 16, cursor: 'pointer',
      }}>Skip</button>
    </Screen>
  );
}

// --- Live clock hook: returns current hour as a fractional 0..24 ---
function useNowHour() {
  const [hour, setHour] = React.useState(() => {
    const d = new Date();
    return d.getHours() + d.getMinutes() / 60 + d.getSeconds() / 3600;
  });
  React.useEffect(() => {
    const id = setInterval(() => {
      const d = new Date();
      setHour(d.getHours() + d.getMinutes() / 60 + d.getSeconds() / 3600);
    }, 30000); // tick every 30s
    return () => clearInterval(id);
  }, []);
  return hour;
}

// --- Heat strip: 24 hourly bars, with a live "now" indicator ---
function HeatStrip({ heat, nowHour }) {
  // Ramp from neutral tint to terracotta based on intensity.
  const colorFor = (v) => {
    // low -> #EFE8DC, mid -> #DDC9AE, high -> #B87340, peak -> #9B4A32
    if (v < 0.08) return '#EFE8DC';
    if (v < 0.2)  return '#E8DCCA';
    if (v < 0.35) return '#DDC9AE';
    if (v < 0.5)  return '#D5B992';
    if (v < 0.65) return '#CE9F62';
    if (v < 0.8)  return '#C8854A';
    return '#A76337';
  };

  return (
    <div style={{ position: 'relative', padding: '0 2px' }}>
      {/* Bars */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(24, 1fr)', gap: 2, height: 26 }}>
        {heat.map((v, i) => {
          const isCurrentHourBucket = Math.floor(nowHour) === i;
          return (
            <div key={i} style={{
              background: colorFor(v),
              borderRadius: 3,
              boxShadow: isCurrentHourBucket ? '0 0 0 1.5px var(--fg, #211E1C)' : 'none',
              transition: 'box-shadow 200ms',
            }} />
          );
        })}
      </div>

      {/* NOW line indicator */}
      <div style={{
        position: 'absolute', top: -4, bottom: -4,
        left: `calc(${(nowHour / 24) * 100}% - 1px)`,
        width: 2, background: 'var(--fg, #211E1C)', borderRadius: 2,
        boxShadow: '0 0 0 3px var(--surface)',
        pointerEvents: 'none',
      }} />
      {/* NOW caret */}
      <div style={{
        position: 'absolute', top: -10,
        left: `calc(${(nowHour / 24) * 100}% - 4px)`,
        width: 8, height: 8, background: 'var(--fg, #211E1C)',
        clipPath: 'polygon(50% 100%, 0 0, 100% 0)',
      }} />

      {/* Hour axis */}
      <div style={{ display: 'flex', justifyContent: 'space-between',
        fontFamily: 'Inter', fontSize: 10, color: 'var(--fg-subtle)', marginTop: 6, letterSpacing: '0.02em' }}>
        <span>12a</span><span>6a</span><span>12p</span><span>6p</span><span>12a</span>
      </div>
    </div>
  );
}

// Classify where "now" falls on the heat map
function nowVerdict(heat, nowHour) {
  const v = heat[Math.floor(nowHour) % 24];
  if (v >= 0.6) return { tone: 'sage',       text: 'Good time to call' };
  if (v >= 0.3) return { tone: 'amber',      text: 'Sometimes answers now' };
  return                 { tone: 'stone',      text: 'Rarely answers now' };
}

function ContactCardFace({ contact, blurred = false }) {
  const nowHour = useNowHour();
  const verdict = nowVerdict(contact.heat, nowHour);

  return (
    <div style={{
      height: '100%', padding: '24px 22px', boxSizing: 'border-box',
      display: 'flex', flexDirection: 'column', alignItems: 'center',
      filter: blurred ? 'blur(2px)' : 'none',
    }}>
      <Chip tone="terracotta">{contact.list}</Chip>
      <div style={{ marginTop: 20 }}>
        <Avatar name={contact.name} size={104} />
      </div>
      <div style={{
        marginTop: 16, fontFamily: 'Inter', fontSize: 28, fontWeight: 600,
        letterSpacing: '-0.01em', color: 'var(--fg-strong)', textAlign: 'center',
      }}>
        {contact.name}
      </div>

      {/* Usually-answers block with live NOW */}
      {!blurred && (
        <div style={{
          marginTop: 18, width: '100%',
          background: 'var(--bg-subtle)', borderRadius: 14,
          padding: '14px 14px 12px',
        }}>
          <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 10 }}>
            <div style={{ fontFamily: 'Inter', fontSize: 11, color: 'var(--fg-muted)', letterSpacing: '0.08em', textTransform: 'uppercase', fontWeight: 500 }}>
              Usually answers
            </div>
            <div style={{ fontFamily: 'Inter', fontSize: 13, fontWeight: 600, color: 'var(--fg)' }}>
              {contact.bestWindow}
            </div>
          </div>
          <HeatStrip heat={contact.heat} nowHour={nowHour} />
          <div style={{ marginTop: 10 }}>
            <Chip tone={verdict.tone}>{verdict.text}</Chip>
          </div>
        </div>
      )}

      {/* Stat row */}
      <div style={{
        marginTop: 'auto', width: '100%',
        display: 'grid', gridTemplateColumns: '1fr 1px 1fr 1px 1fr',
        alignItems: 'center', paddingTop: 16, borderTop: '1px solid var(--line-soft)',
      }}>
        <Stat label="Last called" value={contact.lastCalled} />
        <div style={{ height: 28, background: 'var(--line-soft)' }} />
        <Stat label="Avg length" value={contact.avgLen} />
        <div style={{ height: 28, background: 'var(--line-soft)' }} />
        <Stat label="Pickup" value={contact.pickupRate} />
      </div>
    </div>
  );
}

function Stat({ label, value }) {
  return (
    <div style={{ textAlign: 'center' }}>
      <div style={{ fontFamily: 'Inter', fontSize: 10, color: 'var(--fg-muted)', letterSpacing: '0.08em', textTransform: 'uppercase', fontWeight: 500 }}>
        {label}
      </div>
      <div style={{ fontFamily: 'Inter', fontSize: 14, color: 'var(--fg)', marginTop: 2, fontWeight: 600 }}>
        {value}
      </div>
    </div>
  );
}

Object.assign(window, { CardViewScreen, ContactCardFace, Stat, HeatStrip, useNowHour });
