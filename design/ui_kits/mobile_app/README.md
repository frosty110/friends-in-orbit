# Mobile App — UI Kit

An interactive recreation of the **My Orbit** Android app using the design tokens from the root design system.

## What's here

- `index.html` — entry point. Phone frame with a screen picker; click through all 5 core screens.
- `Primitives.jsx` — `Icon`, `Avatar`, `Button`, `Chip`, `CountBadge`, `AppBar`, `IconBtn`, `Screen` — the visual building blocks used across every screen.
- `PhoneFrame.jsx` — a warm device bezel. Not a Material 3 Android shell by design — the app should feel quieter than stock Android and the device shouldn't fight the brand palette.
- `HomeScreen.jsx` — list grid with a full-width "Surprise me" tile and due-count badges.
- `CardViewScreen.jsx` — the surfacing card. Drag to swipe; up to ~8° tilt; left = later, right = sooner, tap the primary button = call. Next-card peek behind the active card.
- `BrowseListScreen.jsx` — searchable contact list, with a due dot and chevron per row.
- `ContactDetailScreen.jsx` — hero avatar, primary call CTA, stats grid, call history, notes, destructive pause.
- `SettingsScreen.jsx` — grouped settings rows with an animated switch, including the minimal-mode and biometric toggles.

## Notes

Components are intentionally **cosmetic-only**. No real Android permissions, no real call log, no actual state persistence beyond the selected screen + dark mode. Swipe commits advance a fake queue. This is a pixel-fidelity reference, not a shipping app.

Dark mode is a first-class citizen — toggle it in the screen picker. The PhoneFrame swaps to a near-black warm bezel to match.
