# My Orbit — Design System

> An app for calling the people you keep meaning to call.

**My Orbit** is a mobile app that helps people stay in touch with the people they care about. The core metaphor is orbits: inner orbit, outer orbit, different lists for different rhythms. One labelled Call button dials; Later and Sooner, as a swipe or a button, move someone. Notes, history, and gentle nudges layered on top.

The design is warm, quiet, and unhurried. It should feel like a well-worn notebook, not a productivity tool. The app is used in emotionally loaded moments — after a missed call, before bed, in early recovery — so every surface is tuned to reduce cognitive load rather than optimize for engagement.

---

## Product context

> **The original brief (2026), kept as history.** This block is the product brief the design system was built from. What ships is specified in [`../features/INDEX.md`](../features/INDEX.md) (one README per feature) and [`../features/PAGE_VIEWS.md`](../features/PAGE_VIEWS.md) (one page per screen); where this block and those disagree, they win. Items retired since are struck through with a pointer to the decision (annotated 2026-10-05).

**Platform:** Android-first (uses CALL_LOG permission, Jetpack Compose). iOS and web surfaces may follow.

**Core screens**
- **Home**: ~~grid of list tiles with due-count badges; "Surprise me" at top.~~ Ships as one full-width card per list naming who is next up, with a Call button and a 7-day rhythm strip (HOME-5 to HOME-9 in `../features/home/README.md`). "Surprise me" was cut (ADR 0007 superseded; `../vision/00-home/00-home.md` HOME-1) and counts and "due" language were retired (HOME-6).
- **Card View (Surfacing)**: one contact at a time. Photo, name, context. ~~Tap to call;~~ only the labelled Call button dials, and a tap on the card opens details (CARD-01); swipe left or Later to defer, swipe right or Sooner to surface sooner. Card tilts as you drag.
- **Browse List**: sortable/filterable full list view.
- **Contact Detail**: all data for one person: photo, number, all lists they're on, full call history, notes, stats.
- **Lists Manager**: create, rename, reorder, archive. Per-list rhythms (Keep in touch, Late night, Energize; `../features/rule-engine/README.md`) with tunable params.
- **Settings**: ~~biometric lock, minimal mode, digest time,~~ appearance, permissions, sync, export and import, about (`../features/settings/README.md`). Biometric lock and minimal mode were removed on 2026-04-28 (`../features/privacy-and-lock/README.md`); the digest was retired the same day (NOTIF-08).
- **Onboarding**: permissions, first list, bulk add.
- **In-App Call Log**: chronological calls to tracked contacts.

**Key features**
- Rule engine (preset templates + per-list/per-contact tuning)
- Swipe mechanics (right = sooner, left = later, ~~tap = call~~ a labelled Call button dials, CARD-01)
- Cross-list state propagation (contact on 4 lists stays in sync)
- Android CALL_LOG auto-detection (90-day default import)
- Contact pause (1 week / 1 month / until you unpause)
- Bulk add from phone contacts
- Post-call note journal
- Quick-hide: ships as the privacy curtain (PRIV-03). When the app loses focus, names read "Contact" and list names "List"; there is no separate minimal mode
- ~~Biometric lock (optional)~~ Cut from v1 on 2026-04-28 (ADR 0003; `../features/privacy-and-lock/README.md`)
- Manual encrypted export

**Widgets** (`../features/widgets/README.md`)
- 2x2 home screen: next suggestion, ~~tap-to-call~~ a labelled Call button; a tap on the person opens Orbit (WIDGET-08)
- 4x2 home screen: next + 2 alternatives, ~~swipeable~~ a static list, since Glance has no horizontal scroll (WIDGET-02)

**Notifications** (`../features/notifications/README.md`)
- ~~Daily digest ("N people due today")~~ Retired 2026-04-28 (NOTIF-08)
- Per-list time-of-day prompts: ship as the per-list nudge, the only notification Orbit sends
- ~~Incoming call follow-up ("X called you, want to call back?")~~ Removed: notifications are pull, never push (ADR 0009); a missed call surfaces the person in the deck instead

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
- `design/ui_kits/mobile_app/` : the June 2026 interactive recreation of the app, pre-redesign (its own README says what is stale)
- `design/HANDOFF_README.md` : what the exported prototypes are and which source governs when they disagree with the app
- `design/SKILL.md` — agent skill manifest
- `../features/INDEX.md` — canonical per-feature product + technical spec (supersedes the old flat PRD)

---

## Content fundamentals

> [`features/_foundations/voice.md`](../features/_foundations/voice.md) is canonical for voice and wording; when this section and voice.md disagree, voice.md wins (its first line says so). Its glossary decides the words: one word for one idea.

**Voice.** Warm, direct, short. Second person. "Your people," never "the contact." No cute, no clinical, no gamification.

**Never say.** Streaks. Achievements. Levels. Unlocked. Great job. Keep it going. Crushed it. Goals. Progress bars of social contact.

**Do say.** Patterns. Rhythms. Gaps. Next up. Paused. Quiet. All quiet for now. (Not "caught up" and not "due": the queue is continuous, so nothing should read as a cleared backlog or a deadline; HOME-6, voice.md Never say.)

**Casing.** Sentence case everywhere. Button labels, headers, list titles. The only exception is proper nouns (contact names, "My Orbit").

**Length.** Short. One idea per sentence. Empty states fit in one line where possible.

**Safe and unpressured tone.** No pressure, no urgency theater, no "don't break the chain." Reaching out should always feel like the user's choice, never an obligation.

**Empty states feel like a friend.**
- ✅ "All quiet for now. Kai comes up tomorrow."
- ✅ "No one needs a call right now."
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

**Primary.** Terracotta (`#B85338`, deepened on 2026-10-05 from `#C8654A` so a white label clears 4.5:1) is spent once per screen, on the one accent call action (rules.md Design 5 and Design 6). It should feel like the one warm thing on the page, not a default.

**Type.** Inter, all sizes. Body is 16px minimum (non-negotiable). Contact names are 28px SemiBold on the card face and 32px on Contact detail and the Welcome wordmark, with slight negative tracking. Metadata is 14px Stone. No serifs. No display faces. The typography carries no novelty: the warmth comes from color, spacing, and photography.

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

**Menus.** Every options / overflow menu is ordered the same way: the everyday actions first, roughly by how often they're reached for, then the destructive ones last, behind a hairline divider, in `--danger`. Archive, delete, ignore, "convert" — anything that removes something or takes it out of rotation — never sits at the top of a menu where a mis-tap can find it, and never renders in the same colour as "Rename". Implemented once in `OrbitDropdownMenu` (`ui/components/OrbitMenu.kt`): callers hand it a list of actions and mark the destructive ones; ordering, the divider, and the danger tint are the component's job, not each screen's.

**Text input and the keyboard.** A field you are typing into is always visible above the keyboard: the text can never sit behind the IME, and you type where you tapped (ADR 0012). `OrbitScreen` pads by the system-bar **and** IME insets (the app is edge-to-edge, so `adjustResize` does nothing on its own); a Material bottom sheet pads its own content the same way; and any surface hosting a text field (screen body, bottom sheet, dialog) keeps a scrollable container so the focused field can move up into the visible area. Every boxed field is `OrbitTextField` (search and the post-call note page's writing area, which is the page itself, are the two that are not; ADR 0012), which asks for that once the keyboard has stopped moving, for the whole field and 16dp to spare, because Compose's own relocation skips the resize while another scroll is animating. Labels sit above their field, so the label stays readable while the field is focused. This matters most for multi-line notes and descriptions, which live at the bottom of their surface where the keyboard lands. Whole numbers are never typed: they are chosen on the number wheel (ADR 0011).

**Finishing a form.** A screen that saves as you go still offers a way to say "done" — an app-bar text action, and a Done button at the foot of a long form so a user who has scrolled to the end doesn't travel back up. Done closes and returns; it never becomes the thing that saves.

**Iconography.** Phosphor Regular (outlined, rounded). Consistent 1.5px stroke at 24px. One accent call action per screen; a list of people may carry a quiet, muted dial per row (rules.md Design 6). See `ICONOGRAPHY` below.

**Photography.** Contact photos only (user-provided). No stock. No illustrated people. Avatars are masked to a full circle, 1px warm outline only if against a same-color background. Empty-state illustrations, if used, are abstract and warm — a chair, a window, a cup of tea — never cartoon figures.

**Imagery treatment.** If any marketing imagery appears, it's warm-toned, natural light, grain-okay, slightly underexposed. No cool-toned product shots. No corporate stock.

**Layout rules.**
- One decision per screen. The card view shows one person. Home grid shows lists, not contacts.
- Context below the fold. Name + photo dominate the upper 60%; stats and metadata are present but secondary.
- Bottom-heavy action zones. Tap-to-call and swipe happen in the thumb zone. Never put the primary action at the top.
- Dark mode is first-class. Many users open the app after dark — don't treat dark as a tint of light.

**Accessibility baseline.**
- Minimum tap target: 48×48dp.
- 4.5:1 for every text token (button labels and subtle text included), 3:1 for UI parts, 7:1 for primary text; `ThemeContrastTest` gates it in every theme (rules.md Design 4).
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
- One accent call action per screen; a list of people may carry a quiet, muted dial per row (rules.md Design 6). Nowhere else repeats the phone icon.
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
