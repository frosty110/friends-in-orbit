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
- **Accessibility is a gate, not a hope.** `ThemeContrastTest` fails the build if any text token misses 4.5:1 on a surface it sits on (button labels, subtle text and the Home card's tinted band and wash included), any UI part misses 3:1, a snackbar's action misses 4.5:1 on its inverse bar, or the dial or Wallpaper theme can generate an inaccessible accent for any hue. The card's band is derived from the accent tint so it can carry text for any hue (`accentListTone` in `Tones.kt`); the raw tint could not. See `rules.md` §Design 4.
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

## Shared components that carry the rules (2026-10-05, completed 2026-10-06)

Reach for these before building a one-off; each exists because one-offs drifted. The table is the inventory: a component in `ui/components` that is not here is not yet a rule-bearer, and a screen-local layout that does the job of one of these is the drift this table exists to stop.

| Component | Use it for | The rule it carries |
|---|---|---|
| `Avatar(name, size, photoUri)` | Every person, everywhere | Photo with initials fallback, always a circle, hidden from TalkBack (the name is beside it), initials sized in dp so they fit at 200% |
| `OrbitButton` / `OrbitIconButton` | Actions | 48dp, `Role.Button`, Primary is the screen's one accent, press overlay on every variant |
| `OrbitSwitch` | On/off rows (`onCheckedChange = null` inside a `toggleable` row) | Announced as a switch; ink when on, so toggles never spend the accent |
| `OrbitSlider` | Any range | Ink track, round thumb, and a `valueDescription` TalkBack reads in words ("Every 14 days") |
| `OrbitSearchField` | Search boxes (Browse, Search, the picker) | The whole 48dp pill is the field; the placeholder is its TalkBack label while empty; clear control has its own 48dp target |
| `OrbitAppBar` | Every screen's top bar | The title is a heading and the screen's pane title, so TalkBack announces each new screen; grows for two-line titles at 200% |
| `OrbitSnackbarHost` / `OrbitSnackbar` | Every snackbar | Material's snackbar, themed, with its action (Undo) held to 48dp; the default was 40dp |
| `OrbitScreenMessage` | The one message a screen shows in place of its content: nothing here yet, nothing matches, no permission, couldn't load | Title, body and one action, Secondary unless it is the only thing to do on the screen (it never spends a second accent); an optional Ghost secondary action for a state with two honest ways forward; every error state offers Try again, every empty state the next step (rubric D6); scrolls rather than clips at 200% |
| `OrbitListSkeleton` | A list of people while it loads | A static placeholder shaped like the rows (no shimmer, Design 8) with one TalkBack "Loading" node; a screen never shows a false empty state while it waits |
| `OrbitInlineNotice` | A persistent strip above a list with one fix ("Orbit can't see your calls" / "Open settings") | `bgSubtle` row, meta text and a 48dp text action with `Role.Button`; dismiss-free, so the honest state stays until it is fixed |
| `OrbitDropdownMenu` / `OrbitMenuAction` | Every options and overflow menu | Everyday actions first in the caller's order, destructive last behind a divider in `danger`; leading icons are all-or-none within a menu; the menu dismisses itself before the action runs; a checked option for menus of choices |
| `OrbitChip` / `OrbitFilterChip` | A read-only label (a list name, "Smart list"); every filter, single choice and chip-shaped menu trigger | Selected fills `accentTint` and shows a check, never `accent` (Design 5); a 48dp touch target around the pill; Checkbox, RadioButton or Button semantics by role; the label never breaks mid-word at 200%, so chips live in a scrolling or flow row |
| `OrbitCheckbox` | The mark on a multi-select row | Display only: the row is the control and carries the checked state for TalkBack, so the mark has one owner (Code 7); ink with a cream tick, an outline at 3:1 |
| `PauseDurationSheet` | "Pause for how long?" wherever a pause starts (Contact detail, a Browse row, Pause all) | One sheet, not a sheet and a dialog: 1 week, 1 month and "Until you unpause", the same words as the status line and the snackbar; the title is a heading, the options one group of radio rows |
| `BrowseRow` | A person in Browse and Search | `onTap = null` when the caller owns the gesture (a clickable here consumed the parent's press, which is how rows once ignored taps); a muted, labelled dial per row (Design 6); the dot for someone worth a call now is the screen's one accent; "Call {name}" and "Open details" as TalkBack actions |
| `ListContextChip` | A list's name as a chip | Reads the curtain and says "List" in the neutral Stone tone |
| `CountBadge` | A count on a tab or a row | Tabular 13sp in `accentFg` on the accent; a minimum size that grows at 200%, never fixed; nothing drawn at zero |
| `PostCallBanner` | Right after a call: "You just called Sam", with "Add a note" | Stateless, the screen owns its visibility; under the curtain the heading is the generic one |
| `ContactStatsPanel` | A person's stats on Contact detail | The glossary's labels only ("Last call", "Total calls", "Average length", "Longest gap", "Usually"); a stat with nothing to say says so in words ("Not enough calls yet", "Never called"), never a dash |
| `PhIcon` | Every icon | Phosphor Regular only, from the generated VectorDrawables in `res/drawable/ph_*.xml`; decorative, the control that owns it carries the label (Design 7); an unknown name fails loudly instead of drawing a blank |
| `OrbitAppBarTextAction` | "Done", "Skip" in the app bar's trailing slot | A 48dp `Role.Button` in the accent; Done exits a screen that has already saved on every change, it never saves |
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
