# Orbit — Design System Map

> One page. This file is the **map**, not the source. It answers "where does each design decision live?" and "how do user-selectable themes work?" — then points you at the authoritative file. When two sources disagree, the priority order below decides.

Orbit is warm, quiet, unhurried. The design language: a calm neutral surface (warm cream / warm charcoal) with **one** warm accent doing the emotional work. Users can now pick a personality theme and fine-tune the accent — but the calm surface and the accessibility floor never move. See `design/README.md` §"Visual foundations" for the full rationale and voice.

---

## Sources of truth (priority order)

| What | Lives in | Authority |
|---|---|---|
| **Token values** (color/type/shape/spacing/elevation/motion) | `design/colors_and_type.css` | Canonical design definitions |
| **What the app renders** | `android/app/src/main/java/app/orbit/ui/theme/*.kt` | Ground truth at runtime |
| **Token governance + checkpoints** | `.claude/knowledge/cross-cutting/design-tokens.md` | How to change tokens without drift |
| **Rationale / voice / layout rules** | `design/README.md` §"Visual foundations" | Why the system is the way it is |
| **Handoff prototypes** (HTML/CSS from Claude Design) | `design/HANDOFF_README.md` + `design/preview/` | Reference only — not production |

When Kotlin and CSS disagree: either the CSS was updated and Kotlin must catch up, or the CSS is stale. Resolve in a single commit; never leave them divergent.

---

## The theme layer (the Kotlin files)

Every screen reads color through `OrbitTheme.colors.X` and tonal families through `OrbitTheme.tones.X` — **562 call sites, none of which know which theme is active**. The palette is resolved at exactly one point, so themes are swapped centrally:

```
ThemeSettings (themeId, darkMode, accentHue)
        │  OrbitThemes.resolve(settings, isDark)
        ▼
ResolvedTheme(colors, tones) ──provided──▶ LocalOrbitColors / LocalOrbitTones ──▶ every screen
```

| File | Responsibility |
|---|---|
| `Color.kt` | `OrbitPrimitives` (raw hex) + `OrbitColors` semantic slots + base `LightColors`/`DarkColors` |
| `Tones.kt` | `OrbitTones` (chip / avatar / rhythm / heat ramp / Home A-B) + `deriveOrbitTones` |
| `ThemeRegistry.kt` | `OrbitThemeId`, `OrbitDarkMode`, `ThemeSettings`, the 5 curated themes, `OrbitThemes.resolve` |
| `ColorMath.kt` | HSL ⇆ RGB, WCAG contrast, contrast-safe `accentForHue` (the accent dial) |
| `Theme.kt` | `OrbitTheme { }` composable — resolves + provides the CompositionLocals |
| `Type.kt` `Shape.kt` `Spacing.kt` `Motion.kt` `Elevation.kt` | Non-color tokens (theme-independent) |
| `WidgetColors.kt` `WidgetTheme.kt` | Glance widget theming — `orbitWidgetColorProviders(settings)` |

## How theming works (2026-06-22)

- **Curated packs + accent dial** (Material You deferred). Five themes: **Warm** (terracotta — the original identity, authored to exact legacy values), **Cool**, **Forest**, **Plum** (generated from a hue via the contrast-safe generator), and **Mono** (authored neutral).
- **Neutrals are shared.** Themes differ only in their **accent family** + **five tonal "personality hues"**; the cream/charcoal surfaces stay constant. This is deliberate — it keeps the app calm and on-brand and makes a new theme ~23 colors to author (or one hue to generate).
- **Tones derive.** Avatar palettes, rhythm bars, the heat ramp, and the Home A/B cards all derive from a theme's accent + personality hues (`deriveOrbitTones`), so they track the chosen accent instead of being locked to terracotta.
- **The accent dial is unbreakable.** `accentForHue` lowers lightness until white-on-accent clears WCAG AA, so a user can never select an inaccessible primary. When the dial is set, the accent-derived tonal surfaces regenerate to track it.
- **Persistence + live apply.** `AppPrefs` stores `color_theme` / `dark_mode` / `accent_hue` (raw primitives — no UI dependency in the data layer). `AppViewModel` maps them to a `ThemeSettings` StateFlow; the splash holds until it loads (no flash) and the whole app retints live on change. `MainActivity` passes it to `OrbitTheme`.
- **Widgets follow.** Both home-screen widgets build their Glance colors from the chosen theme; Settings changes trigger `WidgetUpdateScheduler`.
- **Accessibility is a gate, not a hope.** `ThemeContrastTest` fails the build if any theme's text/accent/chip pairs miss WCAG AA in either mode, or if the dial generates an inaccessible accent for any hue.

### Adding a theme

1. Add an `OrbitThemeId` entry.
2. Either call `generatedDef(id, hue)` (easiest) or author an `OrbitThemeDef` (accent family + 5 tonal triples, light + dark).
3. Add it to `OrbitThemes.all`.
4. Run `ThemeContrastTest` — it must pass before the theme ships.

### Adding Material You later

The registry leaves room: add an entry whose `OrbitColors` are built from `dynamicLightColorScheme()` / `dynamicDarkColorScheme()` (min SDK 31 supports it) and `resolve` picks it up unchanged.

---

## Known gaps

- **Inter is not bundled.** The spec typeface ships as `.woff2` in `design/fonts/`, which Android's `res/font` does not accept. Until someone converts them to `.ttf` and drops them in `res/font/`, the app renders in SansSerif (Roboto). This is the single highest-leverage visual upgrade — see the `MANUAL STEP` block in `Type.kt`. (Conversion needs `woff2_decompress` or `fontTools`+`brotli`, which weren't available in the build environment.)
- **Shadows** are a 2-scale Compose approximation of the CSS's 5-scale set (`orbitCardShadow` / `orbitHeroShadow`) — intentional simplification.
