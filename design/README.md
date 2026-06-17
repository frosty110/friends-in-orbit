# My Orbit — Design System

> An app for calling the people you keep meaning to call.

**My Orbit** is a mobile app that helps people stay in touch with the people they care about. The core metaphor is orbits: inner orbit, outer orbit — different lists for different cadences. Tap to call. Swipe to defer. Notes, history, and gentle nudges layered on top.

The design is warm, quiet, and unhurried. It should feel like a well-worn notebook, not a productivity tool. The app is used in emotionally loaded moments — after a missed call, before bed, in early recovery — so every surface is tuned to reduce cognitive load rather than optimize for engagement.

---

## Product context

**Platform:** Android-first (uses CALL_LOG permission, Jetpack Compose). iOS and web surfaces may follow.

**Core screens**
- **Home / Mood Picker** — grid of list tiles with due-count badges; "Surprise me" at top.
- **Card View (Surfacing)** — one contact at a time. Photo, name, context. Tap to call; swipe left to defer, swipe right to surface sooner. Card tilts as you drag.
- **Browse List** — sortable/filterable full list view.
- **Contact Detail** — all data for one person: photo, number, all lists they're on, full call history, notes, stats.
- **Lists Manager** — create, rename, reorder, archive. Per-list rule templates (spaced repetition, round robin, recency queue) with tunable params.
- **Settings** — biometric lock, minimal mode, digest time, sync, export, about.
- **Onboarding** — permissions, first list, bulk add.
- **In-App Call Log** — chronological calls to tracked contacts.

**Key features**
- Rule engine (preset templates + per-list/per-contact tuning)
- Swipe mechanics (right = sooner, left = later, tap = call)
- Cross-list state propagation (contact on 4 lists stays in sync)
- Android CALL_LOG auto-detection (90-day default import)
- Contact pause (1 week / 1 month / indefinite)
- Bulk add from phone contacts
- Post-call note journal
- Quick-hide (minimal mode) — list names swap to "Contact" when app loses focus
- Biometric lock (optional)
- Manual encrypted export

**Widgets**
- 2x2 home screen: next suggestion, tap-to-call
- 4x2 home screen: next + 2–3 alternatives, swipeable

**Notifications**
- Daily digest ("N people due today")
- Per-list time-of-day prompts
- Incoming call follow-up ("X called you, want to call back?")

---

## Sources

This design system was built from the written product brief provided in-chat (product structure, voice/tone, color palette, shape language, motion, accessibility). No external codebase, Figma file, or screenshots were provided. Where judgment calls were needed, they followed the brief's principles; flagged below under **Caveats**.

---

## Index

- `README.md` — this file
- `design/colors_and_type.css` — design tokens (CSS custom properties) — colors, type, shape, spacing, elevation, motion
- `design/fonts/` — self-hosted Inter (Regular, Medium, SemiBold, Bold)
- `design/assets/icons/` — Phosphor Regular SVGs (curated subset)
- `design/assets/logo/` — My Orbit wordmark + glyph
- `design/preview/` — Design System tab cards (colors, type, spacing, components)
- `design/ui_kits/mobile_app/` — interactive high-fidelity recreation of the My Orbit Android app
- `design/SKILL.md` — agent skill manifest
- `../features/INDEX.md` — canonical per-feature product + technical spec (supersedes the old flat PRD)

---

## Content fundamentals

**Voice.** Warm, direct, short. Second person. "Your people," never "the contact." No cute, no clinical, no gamification.

**Never say.** Streaks. Achievements. Levels. Unlocked. Great job. Keep it going. Crushed it. Goals. Progress bars of social contact.

**Do say.** Patterns. Rhythms. Gaps. Caught up. Due. Ready. Paused. Quiet.

**Casing.** Sentence case everywhere. Button labels, headers, list titles. The only exception is proper nouns (contact names, "My Orbit").

**Length.** Short. One idea per sentence. Empty states fit in one line where possible.

**Safe and unpressured tone.** No pressure, no urgency theater, no "don't break the chain." Reaching out should always feel like the user's choice, never an obligation.

**Empty states feel like a friend.**
- ✅ "You're all caught up. Want to browse anyway?"
- ✅ "No one's due today. Quiet is okay."
- ✅ "Nothing here yet. Add someone you've been meaning to call."
- ❌ "Great job! You've contacted 7/7 people this week! 🎉"
- ❌ "Time to reach out — don't lose momentum!"

**Metadata copy.**
- "Last called 3 days ago" — not "3d"
- "Usually calls in the evening" — pattern, not prediction
- "Paused until May 2" — specific
- "12 min average" — simple

**Error / warning copy.** Explain what happened in plain language. No jargon. No "oops."
- ✅ "Couldn't sync. Check your connection and try again."
- ❌ "Error 403: Permission denied"

**Emoji.** None in product copy. Contact-provided data (names, notes the user types) is untouched.

**Punctuation.** Periods in full sentences. No periods on single-word labels or buttons ("Call", not "Call."). Oxford comma. No exclamation marks.

---

## Visual foundations

**Palette philosophy.** Warm, earthy, low-saturation. Terracotta + sage + cream. Everything is pulled a few steps off pure — even "white" is cream (`#FAF6F0`), even "black" is warm ink (`#211E1C`). Nothing reads as tech-product-default-blue. Nothing is fully saturated.

**Primary.** Terracotta (`#C8654A`) is used sparingly — tap-to-call button, active nav state, primary badge, progress. It should feel like the one warm thing on the page, not a default.

**Type.** Inter, all sizes. Body is 16px minimum (non-negotiable). Contact names are 32px SemiBold with slight negative tracking. Metadata is 14px Stone. No serifs. No display faces. The typography carries no novelty — the warmth comes from color, spacing, and photography.

**Shape language.** Soft but structural.
- 16px radius for cards (primary container)
- 12px for buttons
- 8px for small elements (badges, chips)
- 999px (full) only for avatars and count pills

Never fully pill-shaped buttons; never sharp corners. Rounded corners are consistent, not varied for decoration.

**Spacing.** 4px base. 20–24px inside cards. 16px between elements. Generous — the app is paced emotionally, not informationally. Touch targets are 48px minimum.

**Backgrounds.** Flat cream (`#FAF6F0` light) or warm charcoal (`#1F1C1A` dark). No gradients on page backgrounds. No patterns. No textures. The surface is quiet so content carries the warmth.

**Cards.** `--surface` fill, 16px radius, subtle 1-layer shadow (`--shadow-card`). Light-mode cards are white `#FFFFFF`. Dark-mode cards are warm graphite `#2B2724`. No colored borders. No left-accent borders. No heavy outlines — on dark mode, a soft `--line-dark` hairline may appear for separation.

**Shadows.** Soft and warm, never black. Shadows use `rgba(33, 30, 28, …)` in light mode so they tint warm. Two scales: `--shadow-card` (default card lift) and `--shadow-hero` (the contact card at center stage). No inner shadows. No neumorphism.

**Borders.** Hairlines only (`--line`, `#E5DDD1`). Used for list dividers and occasionally to define a surface in dark mode. Never colored. Never more than 1px.

**Transparency and blur.** Used once: quick-hide overlay (minimal mode kicks in when app loses focus — list names are replaced with "Contact" labels, no blur needed). Otherwise surfaces are opaque. No glassmorphism.

**Hover / press (where applicable).** This is a touch-first app, so focus is on press feedback.
- Press on buttons: fill darkens by ~8–10% (use `--accent-press` for primary, `--bg-subtle` for secondary).
- Press on cards: a light wash `--bg-subtle` fills briefly.
- No scale-shrink on press. No ripples (default Material ripple is suppressed in favor of the fill change).
- No hover states designed for web; web surfaces (widgets marketing page if needed) inherit light opacity-based hover.

**Motion.** Slow and warm.
- Transitions 250–350ms. `--dur-base` is 250ms, `--dur-slow` is 350ms.
- `--ease-out` for entrances, `--ease-in-out` for layout shifts, `--ease-spring` for swipe commit (gentle, not bouncy).
- **Swipe feedback is physical.** Card tilts up to ~8°, translates with the drag, scales very slightly (0.98). Feels like handling a card in your hand.
- **Haptics** on swipe commit (subtle buzz). No haptics on scrolling or idle taps.
- No confetti. No springs with overshoot > 5%. No infinite animations. No attention-grabbing motion on idle surfaces.

**Iconography.** Phosphor Regular (outlined, rounded). Consistent 1.5px stroke at 24px. One phone icon per screen max. See `ICONOGRAPHY` below.

**Photography.** Contact photos only (user-provided). No stock. No illustrated people. Avatars are masked to a full circle, 1px warm outline only if against a same-color background. Empty-state illustrations, if used, are abstract and warm — a chair, a window, a cup of tea — never cartoon figures.

**Imagery treatment.** If any marketing imagery appears, it's warm-toned, natural light, grain-okay, slightly underexposed. No cool-toned product shots. No corporate stock.

**Layout rules.**
- One decision per screen. The card view shows one person. Home grid shows lists, not contacts.
- Context below the fold. Name + photo dominate the upper 60%; stats and metadata are present but secondary.
- Bottom-heavy action zones. Tap-to-call and swipe happen in the thumb zone. Never put the primary action at the top.
- Dark mode is first-class. Many users open the app after dark — don't treat dark as a tint of light.

**Accessibility baseline.**
- Minimum tap target: 48×48dp.
- WCAG AA for body text (4.5:1). Terracotta on cream clears 4.8:1.
- Every interactive element has a content description.
- Respects system text scaling up to 200%.
- All primary actions reachable with one thumb on a 6.5" phone.

---

## Iconography

**Primary set: Phosphor Regular.** Outlined, rounded, 1.5px stroke. Matches the warm/soft aesthetic exactly — the rounded joins echo the 16px card radius and the stroke weight is consistent with Inter's stem weight at body sizes.

Files are SVGs copied into `design/assets/icons/`. Copy the file, style via `currentColor`.

```html
<img src="design/assets/icons/phone-call.svg" width="24" height="24" alt="" aria-hidden="true" />
```

For React (stroke recoloring), inline the SVG or wrap:

```jsx
<Icon name="phone-call" size={24} color="var(--accent)" />
```

**Size scale.** 20px (inline meta), **24px default**, 32px (card headers), 48px (splash / empty state).

**Color.** Icons inherit from `--fg` by default. Use `--fg-muted` for secondary icons (list row chevrons), `--accent` for interactive emphasis, `--danger` for destructive.

**Rules.**
- One phone icon per screen max. The tap-to-call button is the phone — don't compete with it.
- Never fill Phosphor icons. The app does not use Phosphor's Fill weight.
- Never use two different icon libraries in the same screen.
- No emoji. No unicode glyphs as icons. No custom SVG drawn from scratch unless it is a specific brand mark.

**Substitution flagged.** The brief allows Phosphor OR Lucide; we chose Phosphor because its 1.5px default stroke reads slightly softer than Lucide's 2px. If the engineering team has already standardized on Lucide, swap the set — both are free and the sizing scale above still applies.

**Curated icon set included** (in `design/assets/icons/`):
phone · phone-call · phone-outgoing · phone-incoming · phone-slash · phone-pause · user · user-circle · users · user-plus · heart · star · bookmark-simple · list · list-bullets · squares-four · magnifying-glass · sliders-horizontal · funnel · arrow-left · arrow-right · caret-right · caret-left · caret-down · x · check · check-circle · clock · clock-counter-clockwise · calendar-blank · bell · bell-slash · moon · sun · gear · fingerprint · shield-check · pencil-simple · trash · plus · dots-three · note-pencil · chat-circle · pause-circle · play · shuffle · shuffle-angular · house · house-simple · circle-notch · eye · eye-slash · download-simple · upload-simple · info · warning · warning-circle · link

Add more as needed from [phosphoricons.com](https://phosphoricons.com) (Regular weight only).

---

## Caveats & open questions

- **No codebase or Figma provided.** All components are inferences from the written brief. Once the Jetpack Compose source exists, rebuild UI kit components from real token values and spacing.
- **Font choice.** Brief permits Inter or Geist Sans. Inter chosen for broader weight coverage and stronger tabular figures (important for call-log timestamps). Flag if Geist is preferred.
- **Icon set.** Phosphor chosen over Lucide for softer default stroke. Swap wholesale if team has a preference.
- **Logo / wordmark.** A placeholder wordmark is included in `design/assets/logo/`. Please replace with the final mark when available.
- **Illustration.** No empty-state illustrations included. Brief describes abstract warm line drawings (chair, window, cup of tea) — these should be commissioned or drawn by a human illustrator.
- **Widget visuals.** Widgets are described but not mocked — Android widget rendering is platform-constrained and worth a separate pass.
