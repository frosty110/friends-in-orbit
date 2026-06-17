# widgets

**Status:** stub
**Last reviewed:** 2026-04-22
**Ground truth:**
- Code: not yet implemented (planned location: `android/app/src/main/java/app/orbit/widget/`)
- Tests: none yet

---

## Product

### Why it exists

Widgets bring the next suggestion onto the home screen — zero-friction glance. For users who don't want notifications but still want ambient presence, the widget is the primary surface.

### User story

As a user, I add Orbit to my home screen. It shows who's next. I tap to call, or tap the photo to open the app for context.

### Behavior

**2x2 widget.** Next suggested contact — photo, name, tap-to-call button.

**4x2 widget.** Next suggestion plus 2-3 alternatives. Cycle between them horizontally.

**Tap targets.**
- Tap photo / card body → opens card-view in-app for context.
- Tap call button → initiates call (dial mechanism inherited from card-view per `features/card-view/README.md`).

**Update triggers.** Widget refreshes when:
- A call is made.
- A contact is swiped or skipped.
- List membership changes.
- Time-of-day crosses an active-hours boundary.

**Caught-up state.** Quiet message, no CTA. Voice per `features/_foundations/voice.md`.

> Removed 2026-04-28: minimal-mode and biometric-lock interactions. With both features
> out of v1, the widget always renders names. Quick-hide on focus loss does not extend
> to widgets (the widget process is separate from `MainActivity`'s lifecycle).

### Acceptance criteria

- [ ] Both widget sizes render correctly on a 1x1 grid cell (resize test).
- [ ] Updates arrive within 5s of an in-app state change.
- [ ] Tap targets ≥ 48×48dp on the densest widget layout.
- [ ] Widget survives process death — cold-start renders from persisted state, not a blank placeholder.
- [ ] Voice rules: sentence case, no exclamation.

### Not in scope

- Widgets for tablet layouts beyond 4x2. Defer to v1.1 if users ask.
- Direct swipe-to-defer / swipe-to-prioritize gestures inside the widget. Launcher gesture support is too inconsistent. Tap-through only.
- In-widget notes. Open the app for anything richer than "call now."

### Open product questions

- 4x2 cycling: swipe gesture vs tap-to-cycle? Swipe is fragile on many launchers. Leaning tap-to-cycle for reliability.
- If no lists exist, widget routes to onboarding vs shows nothing? Leaning "quiet placeholder — open app to get started" so the user isn't thrown into onboarding from a tap they didn't intend.

---

## Technical

### Architecture

Use Glance (`androidx.glance`) — Compose-like widget API. Lives in `android/app/src/main/java/app/orbit/widget/` per the project package structure.

- `OrbitGlanceAppWidget` — the widget composable entry point.
- `OrbitGlanceWidgetReceiver` — extends `GlanceAppWidgetReceiver`, triggers updates on state-change broadcasts.
- Widget state provided via `GlanceStateDefinition<WidgetState>` with DataStore backing.
- In-app state-change publishes a broadcast (or uses Glance's `updateAll` API); widget receiver consumes, recomputes suggestion, updates state.

### Data model

Widget reads a denormalized `WidgetState` snapshot: `{ currentContact, currentList, alternatives, allCaughtUp }`. Stored via Glance's state mechanism, refreshed by the receiver. (Removed 2026-04-28: `minimalMode`, `biometricLocked` fields.)

### Permissions / integrations

- **Manifest:** `AppWidgetProvider` entries + metadata XML for each widget size.
- **Manifest (conditional):** `android.permission.CALL_PHONE` — only if ADR decides direct-dial (see `features/card-view/README.md` open question). Until decided, widget uses `ACTION_DIAL`.
- Listens to in-app state-change broadcasts (custom action `app.orbit.action.STATE_CHANGED`).

### Known gotchas

- Glance recompositions are expensive — they hit the system UI process. Keep logic in the receiver and state, not inside composable functions.
- Tap-to-call via `PendingIntent` with `ACTION_CALL` requires `CALL_PHONE` permission; widget cannot request permission. Must pre-check and fall back to `ACTION_DIAL`.
- Widget providers must register in `AndroidManifest.xml` before installation — missing registration silently hides the widget from the picker.

### Not in scope (technical)

- Legacy `RemoteViews`-based widget implementation. Glance is the forward path.
- In-widget composable animations. Static UI.

### Open technical questions

- `androidx.glance` is not yet in the catalog — needs a dependency decision (ADR-worthy if there's real tradeoff; likely just adopt).
