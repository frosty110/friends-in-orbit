// Onboarding 3 — Bulk add contacts
// Multi-select picker with label filtering (phone labels + Orbit lists).
function OnboardingBulkAddScreen({ onBack, onContinue, listName = 'Inner orbit' }) {
  // Each contact has `labels` (from OS contact groups) and `onLists` (Orbit lists they're already on).
  const allContacts = [
    { name: 'Sarah Chen',      hint: 'Last called 11d ago', labels: ['Close Friends'],                onLists: [] },
    { name: 'Marcus Rivera',   hint: 'Last called 3w ago',  labels: ['College'],                      onLists: ['Outer orbit'] },
    { name: 'Priya Raman',     hint: 'Last called 2d ago',  labels: ['Close Friends', 'College'],     onLists: [] },
    { name: 'Danny Okafor',    hint: 'Last called 7w ago',  labels: ['Work'],                         onLists: ['Outer orbit'] },
    { name: 'Hannah Liu',      hint: 'Last called 5d ago',  labels: ['Close Friends'],                onLists: [] },
    { name: 'Ben Silverman',   hint: 'Last called 4mo ago', labels: ['College'],                      onLists: [] },
    { name: 'Ingrid S\u00f8rensen', hint: 'Last called 12d ago', labels: ['Neighbors'],               onLists: [] },
    { name: 'Toby Whelan',     hint: 'Last called 6w ago',  labels: ['Work'],                         onLists: [] },
    { name: 'Ayesha Malik',    hint: 'Last called 9d ago',  labels: ['Family'],                       onLists: ['Family'] },
    { name: 'River Takeda',    hint: 'Last called 2mo ago', labels: ['Neighbors', 'Close Friends'],   onLists: [] },
    { name: 'Mom',             hint: 'Last called 4d ago',  labels: ['Family'],                       onLists: ['Family'] },
    { name: 'Dad',             hint: 'Last called 2w ago',  labels: ['Family'],                       onLists: ['Family'] },
  ];

  // Build filter chip catalog with counts. Phone labels first, Orbit lists second.
  const phoneLabels = ['Close Friends', 'Family', 'Work', 'College', 'Neighbors'];
  const orbitLists  = ['Inner orbit', 'Outer orbit', 'Family'];

  const count = (kind, value) => allContacts.filter(c => {
    return kind === 'label' ? c.labels.includes(value) : c.onLists.includes(value);
  }).length;

  const [query, setQuery] = React.useState('');
  const [activeFilters, setActiveFilters] = React.useState(new Set()); // items like "label:Family" or "list:Inner orbit"
  const [selected, setSelected] = React.useState(new Set(['Sarah Chen', 'Priya Raman', 'Hannah Liu']));

  const toggleFilter = (key) => {
    setActiveFilters(prev => {
      const next = new Set(prev);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });
  };
  const toggleContact = (name) => {
    setSelected(prev => {
      const next = new Set(prev);
      next.has(name) ? next.delete(name) : next.add(name);
      return next;
    });
  };

  // Suggested label chips when typing (matches label name). Surface at top of chip row.
  const q = query.trim().toLowerCase();
  const suggestedLabels = q
    ? phoneLabels.filter(l => l.toLowerCase().includes(q) && !activeFilters.has(`label:${l}`))
    : [];

  // Filter logic: union across filters, intersect with freetext (name matches).
  const filtered = allContacts.filter(c => {
    if (q && !c.name.toLowerCase().includes(q)) return false;
    if (activeFilters.size === 0) return true;
    for (const f of activeFilters) {
      const [kind, ...rest] = f.split(':');
      const value = rest.join(':');
      if (kind === 'label' && c.labels.includes(value)) return true;
      if (kind === 'list'  && c.onLists.includes(value)) return true;
    }
    return false;
  });

  // "Select all shown" affordance when a filter is active
  const showBulkSelectShown = activeFilters.size > 0 && filtered.length > 0;
  const allShownSelected = filtered.every(c => selected.has(c.name));
  const selectAllShown = () => {
    setSelected(prev => {
      const next = new Set(prev);
      if (allShownSelected) {
        filtered.forEach(c => next.delete(c.name));
      } else {
        filtered.forEach(c => next.add(c.name));
      }
      return next;
    });
  };

  return (
    <Screen>
      <AppBar
        title=""
        subtle
        leading={<IconBtn name="arrow-left" aria-label="Back" onClick={onBack} />}
        trailing={
          <div style={{ padding: '0 12px', fontFamily: 'Inter', fontSize: 13, color: 'var(--fg-subtle)', letterSpacing: '0.04em' }}>
            3 of 3
          </div>
        }
      />
      <div style={{ padding: '0 20px 12px', flexShrink: 0 }}>
        <div style={{ fontFamily: 'Inter', fontSize: 26, fontWeight: 600, color: 'var(--fg)', letterSpacing: '-0.01em', lineHeight: 1.2 }}>
          Who's in {listName}?
        </div>
        <div style={{ fontFamily: 'Inter', fontSize: 15, color: 'var(--fg-muted)', marginTop: 8, lineHeight: 1.5, textWrap: 'pretty' }}>
          Add a few now — or filter by label to grab a whole group.
        </div>

        {/* Search */}
        <div style={{
          marginTop: 16, display: 'flex', alignItems: 'center', gap: 10,
          background: 'var(--bg-subtle)', borderRadius: 12, padding: '10px 14px',
        }}>
          <Icon name="magnifying-glass" size={16} color="var(--fg-subtle)" />
          <input
            type="text"
            placeholder="Search name or label"
            value={query}
            onChange={e => setQuery(e.target.value)}
            style={{
              flex: 1, border: 0, background: 'transparent', outline: 'none',
              fontFamily: 'Inter', fontSize: 15, color: 'var(--fg)',
            }}
          />
          {query && (
            <button onClick={() => setQuery('')} aria-label="Clear" style={{
              border: 0, background: 'transparent', padding: 0, cursor: 'pointer',
              display: 'flex', alignItems: 'center',
            }}>
              <Icon name="x" size={16} color="var(--fg-subtle)" />
            </button>
          )}
        </div>
      </div>

      {/* Filter chips — scrolls horizontally */}
      <div style={{
        padding: '0 0 10px',
        flexShrink: 0,
      }}>
        <div style={{
          display: 'flex', gap: 8, overflowX: 'auto', padding: '0 20px 2px',
          scrollbarWidth: 'none',
        }}>
          {/* Suggested label chips from search */}
          {suggestedLabels.map(l => (
            <FilterChip key={`sugg:${l}`} suggested
              label={l} count={count('label', l)}
              active={false}
              onClick={() => { toggleFilter(`label:${l}`); setQuery(''); }}
            />
          ))}

          {/* Phone labels */}
          {phoneLabels.map(l => (
            <FilterChip key={`label:${l}`}
              icon="user-circle"
              label={l} count={count('label', l)}
              active={activeFilters.has(`label:${l}`)}
              onClick={() => toggleFilter(`label:${l}`)}
            />
          ))}

          {/* Divider */}
          <div style={{ width: 1, alignSelf: 'stretch', background: 'var(--line)', margin: '6px 4px', flexShrink: 0 }} />

          {/* Orbit lists */}
          {orbitLists.map(l => (
            <FilterChip key={`list:${l}`}
              icon="shuffle"
              label={l} count={count('list', l)}
              active={activeFilters.has(`list:${l}`)}
              onClick={() => toggleFilter(`list:${l}`)}
              tone="sage"
            />
          ))}
        </div>
      </div>

      {/* Active filter summary + select-all affordance */}
      {showBulkSelectShown && (
        <div style={{
          flexShrink: 0, padding: '6px 20px 10px',
          display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12,
        }}>
          <div style={{ fontFamily: 'Inter', fontSize: 13, color: 'var(--fg-muted)' }}>
            {filtered.length} {filtered.length === 1 ? 'person' : 'people'}
            {activeFilters.size > 0 && (
              <span> in {[...activeFilters].map(f => f.split(':').slice(1).join(':')).join(' + ')}</span>
            )}
          </div>
          <button onClick={selectAllShown} style={{
            border: 0, background: 'transparent', cursor: 'pointer',
            fontFamily: 'Inter', fontSize: 13, fontWeight: 500, color: 'var(--accent-press)',
            padding: '4px 6px',
          }}>
            {allShownSelected ? 'Unselect all' : 'Select all'}
          </button>
        </div>
      )}

      {/* Scrollable list */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 16px 8px' }}>
        {filtered.length === 0 ? (
          <div style={{
            padding: '40px 20px', textAlign: 'center',
            fontFamily: 'Inter', fontSize: 14, color: 'var(--fg-muted)',
          }}>
            No matches. Try a different label or clear search.
          </div>
        ) : (
          <div style={{ background: 'var(--surface)', borderRadius: 16, boxShadow: 'var(--shadow-card)', overflow: 'hidden' }}>
            {filtered.map((c, i) => {
              const active = selected.has(c.name);
              return (
                <button
                  key={c.name}
                  onClick={() => toggleContact(c.name)}
                  style={{
                    width: '100%', border: 0, background: 'transparent', cursor: 'pointer', textAlign: 'left',
                    display: 'flex', alignItems: 'center', gap: 12, padding: '12px 14px',
                    borderBottom: i < filtered.length - 1 ? '1px solid var(--line-soft)' : 'none',
                  }}
                >
                  <Avatar name={c.name} size={40} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontFamily: 'Inter', fontSize: 16, color: 'var(--fg)' }}>{c.name}</div>
                    <div style={{ fontFamily: 'Inter', fontSize: 13, color: 'var(--fg-muted)', marginTop: 2, display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                      {c.labels.map(l => (
                        <span key={l} style={{
                          fontSize: 11, padding: '2px 7px', borderRadius: 999,
                          background: 'var(--bg-subtle)', color: 'var(--fg-muted)', fontWeight: 500,
                        }}>{l}</span>
                      ))}
                      <span>{c.hint}</span>
                    </div>
                  </div>
                  <div style={{
                    width: 24, height: 24, borderRadius: 999,
                    border: active ? 0 : '2px solid var(--line)',
                    background: active ? 'var(--accent)' : 'transparent',
                    display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
                  }}>
                    {active && <Icon name="check" size={14} color="#fff" />}
                  </div>
                </button>
              );
            })}
          </div>
        )}
      </div>

      {/* Footer CTA with count */}
      <div style={{ padding: '12px 20px 28px', background: 'var(--bg)', borderTop: '1px solid var(--line-soft)' }}>
        <Button
          variant="primary"
          onClick={onContinue}
          style={{ width: '100%', height: 52 }}
        >
          {selected.size > 0
            ? `Add ${selected.size} ${selected.size === 1 ? 'person' : 'people'}`
            : 'Skip for now'}
        </Button>
      </div>
    </Screen>
  );
}

// Filter chip: pill with optional icon, count, and active state.
function FilterChip({ icon, label, count, active, onClick, tone = 'terracotta', suggested = false }) {
  const accentBg  = tone === 'sage' ? '#4E6A4B' : 'var(--accent)';
  const accentTint = tone === 'sage' ? '#D8E3D6' : 'var(--accent-tint)';
  return (
    <button
      onClick={onClick}
      style={{
        flexShrink: 0, display: 'inline-flex', alignItems: 'center', gap: 6,
        padding: '7px 12px', borderRadius: 999,
        border: suggested ? '1px dashed var(--accent)' : active ? 0 : '1px solid var(--line)',
        background: active ? accentBg : suggested ? 'transparent' : 'var(--surface)',
        color: active ? '#fff' : suggested ? 'var(--accent-press)' : 'var(--fg)',
        fontFamily: 'Inter', fontSize: 13, fontWeight: 500,
        cursor: 'pointer', whiteSpace: 'nowrap',
        transition: 'background 120ms',
      }}
    >
      {suggested && <Icon name="plus" size={12} color="var(--accent-press)" />}
      {icon && !suggested && (
        <Icon name={icon} size={13} color={active ? '#fff' : 'var(--fg-muted)'} />
      )}
      {label}
      <span style={{
        fontSize: 11, fontWeight: 500,
        color: active ? 'rgba(255,255,255,0.75)' : 'var(--fg-subtle)',
        padding: '0 4px', borderRadius: 6,
        background: active ? 'rgba(255,255,255,0.15)' : 'var(--bg-subtle)',
      }}>{count}</span>
    </button>
  );
}

Object.assign(window, { OnboardingBulkAddScreen, FilterChip });
