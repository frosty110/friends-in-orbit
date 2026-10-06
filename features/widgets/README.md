# widgets

**Status:** shipped
**Last reviewed:** 2026-10-06 (refreshes follow Sooner, pause and list changes too; the widget tests pin the production path)
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/widget/`. `OrbitWidget2x2` ("Next call") and `OrbitWidget4x2` ("Call suggestions") with their receivers; `WidgetLayouts.kt` (breakpoints and arrangements), `WidgetPeople.kt` (each person's face, colours and taps, resolved before composition), `WidgetContent.kt` (the Glance composables), `WidgetUpdateScheduler` / `WidgetUpdateWorker` (when they refresh). Data: `domain/usecase/WidgetSurfaceUseCase.kt`. Theme: `ui/theme/WidgetColors.kt`, `ui/theme/WidgetTheme.kt`. The avatar: `ui/components/AvatarBitmaps.kt`, `ui/components/AvatarFace.kt`. Resources: `res/xml/widget_info_*.xml`, `res/layout/widget_preview_*.xml`, `res/layout/widget_loading.xml`, `res/drawable-nodpi/widget_preview_*.png`.
- Launcher: `android/app/src/main/java/app/orbit/launcher/LauncherShortcuts.kt`, `nav/AppLinks.kt`, `MainActivity.routeFrom`; `res/mipmap-anydpi-v26/ic_launcher*.xml` (themed icon).
- Tests: `OrbitWidgetLayoutTest`, `WidgetPreviewResourcesTest`, `WidgetPeopleTest` (what the widgets draw and what a tap does: masking, the face, `max`, the dial and open intents; Robolectric), `WidgetUpdateSchedulerTest`, `UpdateTriggersTest`, `WidgetSurfaceUseCaseTest`, `WidgetColorsTest`, `AvatarFaceTest`, `AppLinksTest`, `LauncherShortcutsTest`. Renders of both widgets at every breakpoint and typical phone sizes, the empty state and the picker previews, light and dark: `PlatformSurfacesGalleryTest` (`-Pscreenshots`, writes `build/screenshots/platform/`).

---

## Product

### Why it exists

Widgets bring the next suggestion onto the home screen: zero-friction glance. For users who don't want notifications but still want ambient presence, the widget is the primary surface.

### User story

As a user, I add Orbit to my home screen. It shows who's next, with their face. I tap Call to ring them, or tap them to open Orbit on their card for context. I can make it as small as one row or as large as a card, and it still looks like Orbit, in light and in dark.

### Behavior

As built 2026-10-05 (UX rubric plan item 3.2). Before: a square first initial on a grey tile, a bare phone glyph, a 12sp list of alternatives, square corners, one fixed size each, blank previews in the picker, and a tap anywhere on a person dialled them.

**Two widgets.** "Next call" (placed at 2×2) shows one person. "Call suggestions" (placed at 4×2) shows the lead person and up to two more. Both pick people across every active list the way Card view would (`WidgetSurfaceUseCase`: each list's head, ranked across lists).

**Every size has an arrangement** (WIDGET-07). Both resize from one row up to a large card:

| Size | Arrangement |
|---|---|
| One row | Face and a round Call button; the name between them once the row is wide enough |
| Small square | Face and round Call side by side, name below |
| Roomy square (a typical 2×2) | Face, name, then a "Call" button, centred; a larger face and two-line names when there is room |
| Wide, one person | Face, name on up to two lines, round Call |
| Call suggestions at 4×2 | The lead on the left; one or two more on the right, by height |
| Call suggestions at 4×3 and up | The lead in a row on top; two more below, each with a quiet call button; centred so a tall widget has no empty band |

**Taps** (WIDGET-08). Only Call dials, as on Card view (CARD-01): the lead person's accent Call button opens the dialer with the number filled in. A tap anywhere else on a person opens that person's list's deck in Orbit, with them on top. On a 4×3 or larger "Call suggestions", the others carry a quiet, muted phone button (rules.md Design 6 allows one on each row of a people list); at 4×2 there is no room for it beside the name, so those rows open the deck, one tap from Call. A tap carries the list's route, so in the seconds between archiving a list and the refresh that follows (WIDGET-05), a tap on its person still opens that list's deck: archiving leaves memberships in place, and Card view opens a list by id whether or not it is archived. A deleted list's deck opens empty, because deleting a list removes its memberships.

**The app's avatar** (WIDGET-11, UX rubric decision 5). The address-book photo where there is one, otherwise the same two-letter monogram, in Inter, on the same palette colour the app gives that name. Always a circle.

**Looks like Orbit** (WIDGET-11). The widget is a card in the theme's surface colour with Android's own widget corner radius, so it sits with every other widget on the home screen. It follows the user's theme, accent and light or dark choice; with the app set to follow the system, the widget switches between light and dark with the phone by itself. One accent element: the lead's Call. Every tap target is at least 48dp and no text is under 16sp.

**Empty state** (WIDGET-10). "All quiet for now." under the Orbit glyph, in the muted tone; a tap opens Orbit. Not "You're caught up" or "No one due": Orbit always recommends someone (HOME-6), so an empty widget is a lull, not a finish line, and counts and deadlines are the vocabulary the app retired. The widget shows it only when no list can surface anyone (no lists yet, or everyone paused). It does not add "who comes up next": its source deliberately reads only `SurfaceNextUseCase`, and a second query for upcoming people would be a second copy of those filters.

**Previews** (WIDGET-09). The widget picker shows what each widget looks like, with a sample person, in the picker's light or dark mode.

**Names on the home screen.** The widget shows person names and faces: the user chose to put it on the home screen, which is behind the phone's own lock. It never shows a list name. A widget-only masking flag exists (WIDGET-04) but nothing in the app turns it on today. See `features/privacy-and-lock/README.md`, "Surfaces outside the app".

**Update triggers.** The widgets refresh after a call, a Later or Sooner, a pause or unpause, a membership change, a list being archived, unarchived or deleted, and a theme change (debounced 30 seconds), and once an hour for active-hours boundaries (WIDGET-05, WIDGET-06). Until 2026-10-06 a Sooner and a pause did not refresh them, so a widget kept offering someone the user had just paused, with a live Call button, until an unrelated write or the hourly sweep.

### Launcher shortcuts and the themed icon

As built 2026-10-05 (UX rubric plan item 3.3).

**Long-press shortcuts** (LAUNCH-01). A long press on Orbit's icon offers "Call next" and "Search". "Call next" opens the person the widgets show first, on their list's deck, where Call is one tap away; it never dials by itself. "Search" opens search. Both wait for onboarding to finish; before that, they simply open Orbit.

*Why only these, with fixed words.* A long-press menu, and any shortcut pinned from it, sits outside the app where the privacy curtain cannot reach; anyone holding the unlocked phone can read it, and launchers may show shortcuts in search and suggestions. List names are the most relationship-revealing words Orbit holds, so a shortcut per list ("Call someone from Recovery support") would publish what the curtain hides; a shortcut per person would do the same for names. So the labels never change and never name anyone, and "next" is worked out inside Orbit at the moment of the tap.

**Themed icon** (LAUNCH-02). On Android 13 and later, with themed icons on, Orbit's icon is drawn in the home screen's palette like the system's own: the launcher icon has a monochrome layer, the same two rings and dot. The shortcut icons have one too. The nudge's status-bar icon is the same glyph.

### Requirements

Defined 2026-10-05; WIDGET-01 to WIDGET-06 record what the code already cites, the rest are new.

- **WIDGET-01: "Next call".** A home-screen widget showing the one person Orbit would suggest next across all lists, placed at 2×2.
- **WIDGET-02: "Call suggestions".** A home-screen widget showing the lead person and up to two more, placed at 4×2. Glance has no horizontal scroll, so the others are a static list, not a carousel.
- **WIDGET-03: Widgets read through one narrow door.** `WidgetEntryPoint` exposes only the cross-list surface use case and preferences to the widget process; no DAO, repository or key provider.
- **WIDGET-04: A widget-only masking flag.** When `AppPrefs.minimalModeEnabled` is on, the widgets write "Contact" for every name and draw a silhouette for every face, in text and in TalkBack labels. Nothing in the app sets the flag today.
- **WIDGET-05: Refreshes are debounced.** Every in-app change asks for one refresh 30 seconds later (`ExistingWorkPolicy.KEEP`), so bulk edits do not flood the launcher; `WidgetUpdateScheduler` and `WidgetUpdateWorker` are the only code that updates widgets.
- **WIDGET-06: Refreshes follow the data.** Every change to who can be surfaced asks for a refresh: a call, Later, Sooner, pause and unpause (single and in bulk, and their Undo), membership changes, a list archived, unarchived or deleted (and the Undo of each), and theme changes. The domain use cases fire `WidgetRefreshTrigger` beside their write; the ViewModel-level writes (unpause, list archive and delete) call it beside theirs. An hourly sweep catches active-hours boundaries no write announces; a data reset cancels pending refreshes and runs one more so a wiped name never lingers. Pinned by `UpdateTriggersTest`.
- **WIDGET-07: Every size has an arrangement.** Both widgets resize from one row to a large card (`SizeMode.Responsive`, `WidgetBreakpoints`), each breakpoint has a deliberate arrangement (`nextCallLayout`, `suggestionsLayout`), and every breakpoint holds a full 48dp Call button.
- **WIDGET-08: Only Call dials.** The lead person's labelled accent Call button opens the dialer (`ACTION_DIAL`, no `CALL_PHONE`); a tap on a person opens their list's deck with them on top. No surface of the widget dials on a stray tap.
- **WIDGET-09: Real previews.** Android 12+ pickers draw `previewLayout` (live, light or dark); `previewImage` is a rendering of the same layout for launchers that only show images. Preview colours copy the Warm theme's tokens and are held to them by `WidgetPreviewResourcesTest`.
- **WIDGET-10: "All quiet for now."** The empty state, with the Orbit glyph, opening Orbit on tap.
- **WIDGET-11: It looks like Orbit.** Android's widget corner radius, the user's theme in light and dark, and the app's avatar (photo, else the monogram on its palette colour, a circle), from the same rules as the in-app `Avatar` (`avatarInitials`, `OrbitTones.avatarPalette`). The first frame, before Orbit has drawn, is the card with the Orbit glyph, not a spinner.
- **LAUNCH-01: Shortcuts that name no one.** "Call next" and "Search" as dynamic shortcuts with fixed labels and an action, never a route or list id; `MainActivity` resolves them when tapped, after onboarding. No per-list or per-person shortcuts.
- **LAUNCH-02: Themed icon.** The adaptive launcher icon and the shortcut icons carry a monochrome layer for Android 13+ themed icons.

### Acceptance criteria

- [ ] Both widgets render at every size from one row to a large card (resize test on a device).
- [ ] Updates arrive within ~30s of an in-app state change (WIDGET-05 debounce).
- [x] Tap targets ≥ 48×48dp at every breakpoint (`OrbitWidgetLayoutTest`).
- [ ] Widget survives process death — cold-start renders from persisted state, not a blank placeholder.
- [ ] Voice rules: sentence case, no exclamation.
- [ ] Rounded corners match the other widgets on the home screen (device check; the JVM render cannot clip to outlines).
- [ ] The picker shows the preview, in light and dark (device check).
- [ ] With themed icons on (Android 13+), Orbit's icon is tinted like the rest (device check).
- [ ] Long-pressing the icon shows "Call next" and "Search"; "Call next" opens the deck of the person the widget shows (device check).

### Not in scope

- Widgets for tablet layouts beyond the sizes above. Defer to v1.1 if users ask.
- Direct swipe-to-defer / swipe-to-prioritize gestures inside the widget. Launcher gesture support is too inconsistent. Tap-through only.
- Later and Sooner buttons on the widget (`vision/12-widgets` WIDGET-1). They change the deck, and a widget has nowhere to offer Undo.
- In-widget notes. Open the app for anything richer than "call now."

### Open product questions

- 4×2 cycling: swipe gesture vs tap-to-cycle? Moot for now: the others are listed, not cycled.
- Should the empty widget say who comes up next and when, as Card view's empty state does? It would need the widget's source to read upcoming people, which today it deliberately does not (see Empty state).
- Should the widget follow the privacy curtain? Today it shows names whenever it is on the home screen.

---

## Technical

### Architecture

Glance (`androidx.glance` 1.1.1).

- `OrbitWidget2x2` / `OrbitWidget4x2` (`GlanceAppWidget`, `SizeMode.Responsive`), each with its `GlanceAppWidgetReceiver`. Class names are permanent: renaming one silently breaks every placed widget.
- `provideGlance` reads everything once before `provideContent`: the people (`WidgetSurfaceUseCase`), the masking flag, the theme (`themeSettingsSnapshot`), and from those the colour providers (`orbitWidgetColorProviders`), the avatar day and night colours (`orbitWidgetAvatarTones`) and each person's face and taps (`widgetPeople`, on the IO dispatcher because it reads photos). The composables in `WidgetContent.kt` only lay them out.
- Glance composes once per breakpoint and Android shows, for the widget's size, the breakpoint that fits and is closest. Inside a composition `LocalSize` is that breakpoint, so the layout functions in `WidgetLayouts.kt` are written over the breakpoints and pinned by `OrbitWidgetLayoutTest`.
- Taps: `AppLinks.openRoute` (a `NAVIGATE_TO` extra for `MainActivity`, with the route as the intent identifier so each person's PendingIntent is distinct) and `dialIntent` (`ACTION_DIAL`); Glance wraps both in `FLAG_IMMUTABLE` PendingIntents.
- Refresh: `WorkManagerWidgetRefreshTrigger` → `WidgetUpdateScheduler` → `WidgetUpdateWorker` (the only code that calls `update`).
- Launcher shortcuts: `LauncherShortcuts.publish` from `OrbitApp.onCreate` (off the main thread, writes only when the set differs); `MainActivity.routeFrom` resolves `AppLinks.ACTION_CALL_NEXT` / `ACTION_SEARCH`.

### Data model

`WidgetSurfaceData`: `primary`, `alternatives` (0 to 2, deduped across lists), and `listIdByContactId`, the list that surfaced each shown person (where they rank earliest), which their tap opens. Nothing is stored for the widgets; Glance's own state is unused.

### Permissions / integrations

- **Manifest:** both receivers, `exported="false"`, with `res/xml/widget_info_*.xml`.
- `READ_CONTACTS` (already held) to read photos; without it the monogram is drawn.
- `ACTION_DIAL` resolves through the manifest's `<queries>`; never `CALL_PHONE` (PRIV-05). A device with no dialer gets no Call control rather than one that does nothing.

### Known gotchas

- Glance recompositions are expensive: they hit the system UI process. Keep logic in `provideGlance`, not inside composable functions.
- `LocalSize` is the breakpoint, not the widget's real size: a layout that needs the real size to fit will clip at the low end of its range. Each breakpoint is the smallest size its arrangement fits in.
- Shapes are drawables, not clipped outlines: Glance's `cornerRadius` relies on the host clipping to an outline, which software rendering (and so the JVM renders) ignores. The card also asks for the system radius with `cornerRadius`; avatars and buttons are tinted ovals and pills.
- A day and night colour pair per tint is what lets a placed widget follow the phone's dark mode without a redraw. Bitmaps cannot switch, so the monogram is a white mask tinted by the widget, and only photos are full-colour bitmaps.
- Preview XML is inflated by the launcher as `RemoteViews`: only `FrameLayout`, `LinearLayout`, `TextView`, `ImageView` and the other allowed views; an empty `FrameLayout` stands in for `Space`.
- `previewImage` is generated, not drawn by hand: run `./gradlew :app:testDebugUnitTest -Pscreenshots --tests "app.orbit.ui.screenshots.PlatformSurfacesGalleryTest"` and copy `build/screenshots/platform/previewImage-*.png` to `res/drawable-nodpi/widget_preview_*.png`.
- Widget providers must register in `AndroidManifest.xml` before installation; missing registration silently hides the widget from the picker.

### Not in scope (technical)

- Legacy `RemoteViews`-based widget implementation. Glance is the forward path.
- In-widget composable animations. Static UI.

### Open technical questions

- ~~`androidx.glance` is not yet in the catalog.~~ Resolved: Glance 1.1.1 is in `libs.versions.toml`.
