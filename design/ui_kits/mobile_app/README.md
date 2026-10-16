# Mobile App: UI Kit

> **Pre-redesign kit (June 2026).** These screens show the app as briefed, before the 2026-06-22 Home redesign and the 2026-10-05 UX pass. Reference only: `DESIGN.md`'s priority table governs, and the feature READMEs under `features/` say what ships. The notes below mark what is stale.

An interactive recreation of the **My Orbit** Android app using the design tokens from the root design system.

## What's here

- `index.html` — entry point. Phone frame with a screen picker; click through all 5 core screens.
- `Primitives.jsx`: `Icon`, `Avatar`, `Button`, `AppBar`, `IconBtn`, `Screen`, the visual building blocks used across every screen. The read-only `Chip` and the `CountBadge` pill were retired by rules.md Design 9 on 2026-10-08 (see "No badges" below).
- `PhoneFrame.jsx` — a warm device bezel. Not a Material 3 Android shell by design — the app should feel quieter than stock Android and the device shouldn't fight the brand palette.
- `HomeScreen.jsx`: list grid with a full-width "Surprise me" tile. Stale: the app has full-width cards naming who is next up, no Surprise me (ADR 0007 superseded) and no counts (HOME-6).
- `CardViewScreen.jsx`: the surfacing card. Drag to swipe; up to ~8° tilt; left = later, right = sooner, tap the primary button = call. Stale: the app labels Later and Sooner, shows the last note on the face and has no next-card peek.
- `BrowseListScreen.jsx`: searchable contact list, with a chevron per row; someone up now has "Up now" leading their second line.
- `ContactDetailScreen.jsx` — hero avatar, primary call CTA, stats grid, call history, notes, destructive pause.
- `SettingsScreen.jsx`: grouped settings rows with an animated switch, including the minimal-mode and biometric toggles. Stale: both were removed on 2026-04-28; the app's sections are Appearance, Permissions, Contacts, Call history, Data, About.

## Notes

**No badges (2026-10-08).** The owner's word, "We should just omit badges in general", is rules.md Design 9: nothing tags a row, card, title, avatar or icon with a small marker of status, category, count or recommendation, and a chip shape is only ever a control. These were removed from the kit that day, and each fact that is still needed is said in the screen's own text, the way the app says it:

- Home's due-count badge on each tile: removed, with no words in its place (Home retired due counts, HOME-6).
- The Lists screen's due-count badge beside a list's name: removed; the row's second line already says its people and its rule.
- Card view: the rotated "Later" and "Sooner" pills that faded in during a drag (the moving card and the buttons say which way), the list chip over the face (the app bar names the list) and the chip under the strip that graded the current hour ("Good time to call", the strip itself outlines that hour).
- Contact detail's list chips: one line, "On Inner orbit, Family".
- Browse's accent due dot: "Up now" opens the row's second line instead.
- The bulk add picker's phone-group pills: the groups lead the row's hint line, "Close Friends, College · Last called 2d ago".

The bulk add picker's `FilterChip`s stay: they are controls.

Components are intentionally **cosmetic-only**. No real Android permissions, no real call log, no actual state persistence beyond the selected screen + dark mode. Swipe commits advance a fake queue. This is a pixel-fidelity reference, not a shipping app.

Dark mode is a first-class citizen — toggle it in the screen picker. The PhoneFrame swaps to a near-black warm bezel to match.
