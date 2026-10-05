# Orbit — Design System Map

> One page. This file is the **map**, not the source. It answers "where does each design decision live?" and "how do user-selectable themes work?" — then points you at the authoritative file. When two sources disagree, the priority order below decides.

Orbit is warm, quiet, unhurried. The design language: a calm neutral surface (warm cream / warm charcoal) with **one** warm accent doing the emotional work. Users can now pick a personality theme and fine-tune the accent — but the calm surface and the accessibility floor never move. See `design/README.md` §"Visual foundations" for the full rationale and voice.

---

## Sources of truth (priority order)

| What | Lives in | Authority |
|---|---|---|
| **Token values** (color/type/shape/spacing/elevation/motion) | `design/colors_and_type.css` | Canonical design definitions |
| **What the app renders** | `android/app/src/main/java/app/orbit/ui/theme/*.kt` | Ground truth at runtime |
| **Numbered design rules** (tap targets, accent budget, motion) | [`features/_foundations/rules.md`](features/_foundations/rules.md) §Design | The rules source comments cite |
| **Rationale / voice / layout rules** | `design/README.md` §"Visual foundations" | Why the system is the way it is |
| **Handoff prototypes** (HTML/CSS from Claude Design) | `design/HANDOFF_README.md` + `design/preview/` | Reference only — not production |

When Kotlin and CSS disagree: either the CSS was updated and Kotlin must catch up, or the CSS is stale. Resolve in a single commit; never leave them divergent.

---

## The theme layer (the Kotlin files)

Every screen reads color through `OrbitTheme.colors.X` and tonal families through `OrbitTheme.tones.X` — **562 call sites, none of which know which theme is active**. The palette is resolved at exactly one point, so themes are swapped centrally:

```
ThemeSettings (themeId, darkMode, accentHue)   + wallpaper hue (Wallpaper theme only)
        │  OrbitThemes.resolve(settings, isDark, deviceHue)
        ▼
ResolvedTheme(colors, tones) ──provided──▶ LocalOrbitColors / LocalOrbitTones ──▶ every screen
```

| File | Responsibility |
|---|---|
| `Color.kt` | `OrbitPrimitives` (raw hex) + `OrbitColors` semantic slots + base `LightColors`/`DarkColors` |
| `Tones.kt` | `OrbitTones` (chip / avatar / rhythm / heat ramp / Home A-B) + `deriveOrbitTones` |
| `ThemeRegistry.kt` | `OrbitThemeId`, `OrbitDarkMode`, `ThemeSettings`, the 5 curated themes + Wallpaper, `OrbitThemes.resolve` |
| `DeviceAccent.kt` | `deviceAccentHue(context)`: the wallpaper's accent hue for the Wallpaper theme |
| `ColorMath.kt` | HSL ⇆ RGB, WCAG contrast, contrast-safe `accentForHue` (the accent dial) |
| `Theme.kt` | `OrbitTheme { }` composable: resolves + provides the CompositionLocals, maps every Material 3 colour, type and shape slot to Orbit tokens, installs the press indication and `LocalReducedMotion` |
| `PressIndication.kt` | `OrbitPressIndication`: the quiet press / hover / focus overlay every clickable gets instead of ripple |
| `Type.kt` `Shape.kt` `Spacing.kt` `Motion.kt` `Elevation.kt` | Non-color tokens (theme-independent) |
| `WidgetColors.kt` `WidgetTheme.kt` | Glance widget theming — `orbitWidgetColorProviders(settings)` |
| `ThemeSettingsSnapshot.kt` | The appearance choice read once, for surfaces drawn outside the app's composition (widgets, a nudge's face) |

## How theming works (2026-06-22)

- **Curated packs, Wallpaper, and an accent dial.** Five curated themes: **Warm** (terracotta, the original identity), **Cool**, **Forest**, **Plum** (generated from a hue via the contrast-safe generator), and **Mono** (authored neutral). A sixth, **Wallpaper** (stored as `device`, added 2026-10-05), takes only the *hue* of the wallpaper's Material You accent and runs it through the same generator, so it feels native on Android 12+ and still cannot fail contrast; a grey wallpaper falls back to Warm's hue.
- **Neutrals are shared.** Themes differ only in their **accent family** + **five tonal "personality hues"**; the cream/charcoal surfaces stay constant. This is deliberate — it keeps the app calm and on-brand and makes a new theme ~23 colors to author (or one hue to generate).
- **Tones derive.** Avatar palettes, rhythm bars, the heat ramp, and the Home A/B cards all derive from a theme's accent + personality hues (`deriveOrbitTones`), so they track the chosen accent instead of being locked to terracotta.
- **The accent dial is unbreakable.** `accentForHue` moves lightness until the accent clears 4.5:1 both as a button fill under its label and as text on the page: darker with a white label in light mode, lighter with a warm-ink label in dark mode. A user can never select an inaccessible primary. When the dial is set, the accent-derived tonal surfaces regenerate to track it.
- **Persistence + live apply.** `AppPrefs` stores `color_theme` / `dark_mode` / `accent_hue` (raw primitives — no UI dependency in the data layer). `AppViewModel` maps them to a `ThemeSettings` StateFlow; the splash holds until it loads (no flash) and the whole app retints live on change. `MainActivity` passes it to `OrbitTheme`.
- **Widgets follow.** Both home-screen widgets build their Glance colors from the chosen theme; Settings changes trigger `WidgetUpdateScheduler`. Every widget colour is a day and night pair, so a widget follows the phone into dark mode without a redraw, and the widgets and a nudge's large icon draw the app's own avatar (`avatarInitials`, `OrbitTones.avatarPalette`; `features/widgets/README.md`, WIDGET-11).
- **Accessibility is a gate, not a hope.** `ThemeContrastTest` fails the build if any text token misses 4.5:1 on a surface it sits on (button labels and subtle text included), any UI part misses 3:1, a snackbar's action misses 4.5:1 on its inverse bar, or the dial or Wallpaper theme can generate an inaccessible accent for any hue. See `rules.md` §Design 4.
- **Material parts look like Orbit.** `OrbitTheme` maps every Material 3 colour slot (menus, snackbars, dialogs, sheets, the time picker, checkboxes, chips, text fields), sets Material's type scale in Inter and its shape scale to Orbit's radii. Before 2026-10-05 only ten colour slots were mapped and the rest showed default lavender.
- **Press, not ripple.** `OrbitPressIndication` is the default indication: a quiet overlay in the foreground colour (10% pressed, 4% hovered) plus a 2dp outline under keyboard focus.
- **Motion.** Screen changes slide a short way in the direction of travel over 250 to 350ms (`nav/NavMotion.kt`); predictive back is on. With the system's animations turned off, `LocalReducedMotion` makes every transition instant.
- **System bars follow the in-app choice.** `MainActivity` sets status and navigation bar icon colours from the resolved light or dark mode, and tells the system (`UiModeManager.setApplicationNightMode`) so the splash screen matches next launch.

### Adding a theme

1. Add an `OrbitThemeId` entry.
2. Either call `generatedDef(id, hue)` (easiest) or author an `OrbitThemeDef` (accent family + 5 tonal triples, light + dark).
3. Add it to `OrbitThemes.all`.
4. Run `ThemeContrastTest` — it must pass before the theme ships.

### Why Wallpaper takes only the hue

Material You's dynamic schemes repaint every surface. Orbit's calm comes from its constant cream and charcoal, and its accessibility promise from the generator, so the Wallpaper theme borrows the one thing that makes a phone feel like *yours* (the hue) and keeps everything else.

---

## Shared components that carry the rules (2026-10-05)

Reach for these before building a one-off; each exists because one-offs drifted.

| Component | Use it for | The rule it carries |
|---|---|---|
| `Avatar(name, size, photoUri)` | Every person, everywhere | Photo with initials fallback, always a circle, hidden from TalkBack (the name is beside it), initials sized in dp so they fit at 200% |
| `OrbitButton` / `OrbitIconButton` | Actions | 48dp, `Role.Button`, Primary is the screen's one accent, press overlay on every variant |
| `OrbitSwitch` | On/off rows (`onCheckedChange = null` inside a `toggleable` row) | Announced as a switch; ink when on, so toggles never spend the accent |
| `OrbitSlider` | Any range | Ink track, round thumb, and a `valueDescription` TalkBack reads in words ("Every 14 days") |
| `OrbitSearchField` | Search boxes (Browse, Search, the picker) | The whole 48dp pill is the field; the placeholder is its TalkBack label while empty; clear control has its own 48dp target |
| `OrbitAppBar` | Every screen's top bar | The title is a heading and the screen's pane title, so TalkBack announces each new screen; grows for two-line titles at 200% |
| `OrbitSnackbarHost` / `OrbitSnackbar` | Every snackbar | Material's snackbar, themed, with its action (Undo) held to 48dp; the default was 40dp |
| `SectionLabel` | The small label over a group | A heading for TalkBack navigation |
| `InfoTip` | "What does this mean?" | Tappable (not long-press only), 48dp, Phosphor "info" |
| `OrbitMark` | Brand moments | Drawn from tokens, settles once, static with animations off |
| `OrbitScreen` | Every full screen | Insets (bars, cutouts, keyboard) and the 640dp content cap for wide windows |
| `LocalPrivacyCurtain`, `CurtainMask` | Any name of a person or list: text, fields, titles, TalkBack labels | Read the curtain and show "Contact" or "List"; a text field draws the mask over its buffer (`CurtainMask`) and never saves it. The gallery's curtain mode checks every preview (PRIV-03) |
| `UiText` (`ui/util`) | Text a ViewModel or worker produces | Copy lives in resources; only user data is plain |
| `formatSpan` / `formatRelative` / `formatClockTime` (`ui/util`) | Any duration or time | One wording for "time since", the phone's 12/24-hour setting |

## Known gaps

- ~~**Inter is not bundled.**~~ Resolved 2026-10-05: Inter Regular, Medium, SemiBold and Bold ship as subset `.ttf` files in `res/font/` (SIL OFL 1.1, licence in `design/fonts/OFL.txt` and the in-app licences dialog). Numbers that change or line up (stats, badges, the timeline axis) use tabular figures.
- **Shadows** are a 2-scale Compose approximation of the CSS's 5-scale set (`orbitCardShadow` / `orbitHeroShadow`) — intentional simplification.
