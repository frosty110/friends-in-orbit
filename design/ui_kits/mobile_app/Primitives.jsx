// Shared icon helper — references SVGs from /assets/icons/
// Uses CSS mask to recolor Phosphor outlines via `color` prop.
// Inline SVG cache so currentColor resolves against the parent color.
const __iconCache = {};
function Icon({ name, size = 24, color = 'currentColor', style = {}, ...rest }) {
  const [svg, setSvg] = React.useState(__iconCache[name] || null);
  React.useEffect(() => {
    if (__iconCache[name]) return;
    fetch(`../../assets/icons/${name}.svg`).then(r => r.text()).then(t => {
      __iconCache[name] = t;
      setSvg(t);
    });
  }, [name]);
  return (
    <span
      {...rest}
      aria-hidden="true"
      style={{
        display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
        width: size, height: size, flexShrink: 0,
        color,
        ...style,
      }}
      dangerouslySetInnerHTML={svg ? { __html: svg.replace('<svg', `<svg width="${size}" height="${size}"`) } : undefined}
    />
  );
}

// Avatar — deterministic warm color from name
function Avatar({ name, size = 44, photo = null }) {
  const palettes = [
    ['#EDD6CE', '#9B4A32'],
    ['#D8E3D6', '#4E6A4B'],
    ['#F2E2BF', '#8B6821'],
    ['#F5E0DC', '#A04838'],
    ['#E8DDD1', '#6B6560'],
  ];
  let hash = 0;
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) | 0;
  const [bg, fg] = palettes[Math.abs(hash) % palettes.length];
  const initials = name.split(' ').filter(Boolean).slice(0, 2).map(s => s[0]).join('').toUpperCase();
  return (
    <div
      aria-hidden="true"
      style={{
        width: size, height: size, borderRadius: 999,
        background: photo ? `url(${photo}) center/cover` : bg,
        color: fg,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontFamily: 'Inter, sans-serif', fontWeight: 600,
        fontSize: Math.round(size * 0.36), flexShrink: 0,
        letterSpacing: '-0.01em',
      }}
    >
      {!photo && initials}
    </div>
  );
}

// Due-count badge pill
function CountBadge({ count }) {
  if (!count) return null;
  return (
    <div style={{
      minWidth: 26, height: 26, padding: '0 8px',
      borderRadius: 999, background: 'var(--accent)',
      color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center',
      fontFamily: 'Inter', fontSize: 13, fontWeight: 600,
    }}>{count}</div>
  );
}

// Chip — small colored pill, used for list badges and status
function Chip({ tone = 'terracotta', children }) {
  const tones = {
    terracotta: { bg: '#EDD6CE', fg: '#9B4A32', dot: '#C8654A' },
    sage:       { bg: '#D8E3D6', fg: '#4E6A4B', dot: '#87A383' },
    amber:      { bg: '#F2E2BF', fg: '#8B6821', dot: '#D4A144' },
    brick:      { bg: '#F5E0DC', fg: '#A04838', dot: '#A04838' },
    stone:      { bg: '#F2ECE2', fg: '#6B6560', dot: '#9A928B' },
  };
  const t = tones[tone] || tones.terracotta;
  return (
    <div style={{
      display: 'inline-flex', alignItems: 'center', gap: 8,
      padding: '4px 10px', borderRadius: 999, background: t.bg,
      color: t.fg, fontFamily: 'Inter', fontSize: 12, fontWeight: 500,
      letterSpacing: '0.02em',
    }}>
      <span style={{ width: 6, height: 6, borderRadius: 999, background: t.dot }} />
      {children}
    </div>
  );
}

// Primary/secondary/ghost button
function Button({ variant = 'primary', icon, children, onClick, style = {} }) {
  const base = {
    height: 48, padding: '0 20px', border: 0, borderRadius: 12,
    fontFamily: 'Inter', fontSize: 16, fontWeight: 500,
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 8,
    cursor: 'pointer', transition: 'background 150ms',
  };
  const variants = {
    primary:   { background: 'var(--accent)',    color: '#fff' },
    secondary: { background: 'var(--bg-subtle)', color: 'var(--fg)' },
    ghost:     { background: 'transparent',       color: 'var(--fg-muted)' },
    destructive: { background: 'transparent', color: 'var(--danger)', border: '1px solid var(--line)' },
  };
  return (
    <button onClick={onClick} style={{ ...base, ...variants[variant], ...style }}>
      {icon && <Icon name={icon} size={18} color={variants[variant].color} />}
      {children}
    </button>
  );
}

// Screen shell — the phone frame already wraps this
function Screen({ children, scrollable = true, style = {} }) {
  return (
    <div style={{
      height: '100%', display: 'flex', flexDirection: 'column',
      background: 'var(--bg)', color: 'var(--fg)',
      overflow: scrollable ? 'hidden' : 'visible',
      ...style,
    }}>
      {children}
    </div>
  );
}

// Top app bar
function AppBar({ title, leading, trailing, subtle = false }) {
  return (
    <div style={{
      height: 56, padding: '0 8px 0 16px', flexShrink: 0,
      display: 'flex', alignItems: 'center', gap: 4,
      background: subtle ? 'transparent' : 'var(--bg)',
      borderBottom: subtle ? 'none' : '1px solid transparent',
    }}>
      {leading}
      <div style={{
        flex: 1, fontFamily: 'Inter', fontSize: 18, fontWeight: 600,
        color: 'var(--fg)', paddingLeft: leading ? 4 : 8,
      }}>{title}</div>
      {trailing}
    </div>
  );
}

// Icon button (48x48 tap target)
function IconBtn({ name, onClick, color = 'var(--fg)', 'aria-label': label }) {
  return (
    <button
      onClick={onClick}
      aria-label={label}
      style={{
        width: 48, height: 48, border: 0, background: 'transparent',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        borderRadius: 12, cursor: 'pointer',
      }}
    >
      <Icon name={name} size={22} color={color} />
    </button>
  );
}

Object.assign(window, { Icon, Avatar, CountBadge, Chip, Button, Screen, AppBar, IconBtn });
