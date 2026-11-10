# widgets

**Status:** shipped
**Last reviewed:** 2026-11-09 (each widget has its own settings: the list it suggests from, and whether it shows names and faces)
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/widget/`. `OrbitWidget2x2` ("Next call") and `OrbitWidget4x2` ("Call suggestions") with their receivers; `WidgetLayouts.kt` (breakpoints and arrangements), `WidgetPeople.kt` (each person's face, colours and taps, resolved before composition), `WidgetContent.kt` (the Glance composables, left out of the unit-test coverage count as UI), `WidgetIntents.kt` (the dial, open-Orbit, open-list and Widget settings intents they fire), `WidgetSnapshot.kt` (one widget's settings, people and taps, read once per render; and the removed-widget cleanup), `settings/` (Widget settings: `WidgetSettingsActivity`, `WidgetSettingsViewModel`, `WidgetSettingsScreen`, `WidgetSettingsUiState`), `WidgetUpdateScheduler` / `WidgetUpdateWorker` (when they refresh). Data: `domain/usecase/WidgetSurfaceUseCase.kt`; each widget's settings in `data/AppPrefs.kt` (`widgetSettings`, `WidgetSource`, `WidgetSettings`). Theme: `ui/theme/WidgetColors.kt`, `ui/theme/WidgetTheme.kt`. The avatar: `ui/components/AvatarBitmaps.kt`, `ui/components/AvatarFace.kt`. Resources: `res/xml/widget_info_*.xml`, `res/layout/widget_preview_*.xml`, `res/layout/widget_loading.xml`, `res/drawable-nodpi/widget_preview_*.png`.
- Launcher: `android/app/src/main/java/app/orbit/launcher/LauncherShortcuts.kt`, `nav/AppLinks.kt`, `MainActivity.routeFrom`; `res/mipmap-anydpi-v26/ic_launcher*.xml` (themed icon).
- Tests: `OrbitWidgetLayoutTest`, `WidgetPreviewResourcesTest`, `WidgetPeopleTest` (what the widgets draw and what a tap does: masking, the face, `max`, the dial and open intents; Robolectric), `WidgetUpdateSchedulerTest`, `UpdateTriggersTest`, `WidgetSurfaceUseCaseTest` (every list, and one list's queue), `WidgetSettingsViewModelTest`, `AppPrefsTest` (per-widget settings), `ImportServiceTest` (import resets them), `WidgetColorsTest`, `AvatarFaceTest`, `AppLinksTest`, `LauncherShortcutsTest`, `LauncherIconTest`. Renders of both widgets at every breakpoint and typical phone sizes, the empty state and the picker previews, light and dark: `PlatformSurfacesGalleryTest` (`-Pscreenshots`, writes `build/screenshots/platform/`).

---

## Product

### Why it exists

Widgets bring the next suggestion onto the home screen: zero-friction glance. For users who don't want notifications but still want ambient presence, the widget is the primary surface.

### User story

As a user, I add Orbit to my home screen. It shows who's next, with their face. I tap Call to ring them, or tap them to open Orbit on their card for context. I can make it as small as one row or as large as a card, and it still looks like Orbit, in light and in dark. I can point a widget at one list, so the *Family* widget on one home screen shows my family and the *Old friends* widget on another shows them, and I can have a widget leave names and faces off.

### Behavior

As built 2026-10-05 (UX rubric plan item 3.2). Before: a square first initial on a grey tile, a bare phone glyph, a 12sp list of alternatives, square corners, one fixed size each, blank previews in the picker, and a tap anywhere on a person dialled them.

**Two widgets.** "Next call" (placed at 2×2) shows one person. "Call suggestions" (placed at 4×2) shows the lead person and up to two more. By default both pick people across every active list the way Card view would (`WidgetSurfaceUseCase`: each list's head, ranked across lists); each can instead follow one list (below).

**Each widget has its own settings** (WIDGET-12 to WIDGET-16, built 2026-11-09). Two per placed widget, and deliberately no more:

| Setting | Choices | Default |
|---|---|---|
| Suggest people from | "Every list", or one active list | Every list |
| Show names and faces | On / off | On |

Widget settings opens when a widget is placed (backing out keeps a working widget on every list; launchers that skip the step place one too) and from the launcher's long-press reconfigure, where the launcher offers it. Changes save as they are made and redraw that widget within a second or two; a save that fails says "Couldn't save that. Try again." and keeps the previous choice. The screen previews who the widget will lead with ("Up now on this widget"), offers "Every list" then each active list in Lists order, and has Done as its one accent element. It is Orbit's window and shows list names, so it carries the privacy curtain and, in release builds, no screenshots. What it owes the user: [`page-views/widget-settings.md`](../page-views/widget-settings.md).

- *Every list* is the widgets as they always were: the lead is the cross-list head, and "Call suggestions" lists other lists' heads, one person per list (ADR 0008).
- *One list* is a home-screen copy of that list's deck: "Next call" shows its head, the person its Card view shows on top; "Call suggestions" shows its first three in its queue order, the order of Browse's "Next up". A tap on a person opens that list's deck; the empty state's tap opens it too, since that is where the user pointed the widget.
- *The list is archived or deleted:* the widget does not fall back to every list, which would put people on it the user kept off. It shows the Orbit glyph and "Choose a list", and a tap opens Widget settings; TalkBack hears "This widget's list is archived or deleted. Choose a list". It names no list. Restoring an archived list brings its people back on the next refresh, because the choice is kept.
- *Names and faces off:* every name on that widget reads "Contact" and every face is the plain circle, in text and to TalkBack ("Call Contact"). This is where WIDGET-04's masking finally has a switch, per widget.
- *Not settings, on purpose:* how many people (the size decides, WIDGET-07), a theme or transparency per widget (the widget follows the app, WIDGET-11), what a tap does (only Call dials, WIDGET-08), and Later or Sooner (no Undo path; Not in scope).

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

**Names on the home screen.** The widget shows person names and faces: the user chose to put it on the home screen, which is behind the phone's own lock. It never shows a list name, whichever list it follows; "Choose a list" is fixed words. Each widget can leave names and faces off (WIDGET-15). See `features/privacy-and-lock/README.md`, "Surfaces outside the app".

**Update triggers.** The widgets refresh after a call, a Later or Sooner, a pause or unpause, a membership change, a list being archived, unarchived or deleted (from Home's long-press menu or from Lists), a theme change, and an import (debounced 30 seconds; a change in Widget settings instead redraws that one widget at once, WIDGET-12), and once an hour for active-hours boundaries (WIDGET-05, WIDGET-06; no list has an active-hours window since 2026-10-08, LIST-25, so today the hourly sweep only catches due times no write announced). Until 2026-10-06 a Sooner and a pause did not refresh them, so a widget kept offering someone the user had just paused, with a live Call button, until an unrelated write or the hourly sweep; and until later that day only Lists' archive and delete asked for a refresh, so a list archived from Home kept its lead on the widget the same way.

### Launcher shortcuts and the themed icon

As built 2026-10-05 (UX rubric plan item 3.3).

**Long-press shortcuts** (LAUNCH-01). A long press on Orbit's icon offers "Call next" and "Search". "Call next" opens the person the widgets show first, on their list's deck, where Call is one tap away; it never dials by itself. "Search" opens search. Both wait for onboarding to finish; before that, they simply open Orbit.

*Why only these, with fixed words.* A long-press menu, and any shortcut pinned from it, sits outside the app where the privacy curtain cannot reach; anyone holding the unlocked phone can read it, and launchers may show shortcuts in search and suggestions. List names are the most relationship-revealing words Orbit holds, so a shortcut per list ("Call someone from Recovery support") would publish what the curtain hides; a shortcut per person would do the same for names. So the labels never change and never name anyone, and "next" is worked out inside Orbit at the moment of the tap.

**Themed icon** (LAUNCH-02). On Android 13 and later, with themed icons on, Orbit's icon is drawn in the home screen's palette like the system's own: the launcher icon has a monochrome layer, the same two rings and dot. The shortcut icons have one too. The nudge's status-bar icon is the same glyph.

### Requirements

Defined 2026-10-05; WIDGET-01 to WIDGET-06 record what the code already cites, the rest are new.

- **WIDGET-01: "Next call".** A home-screen widget showing the one person Orbit would suggest next across all lists, placed at 2×2.
- **WIDGET-02: "Call suggestions".** A home-screen widget showing the lead person and up to two more, placed at 4×2. Glance has no horizontal scroll, so the others are a static list, not a carousel.
- **WIDGET-03: Widgets read through one narrow door.** `WidgetEntryPoint` exposes only the surface use case and preferences to the widget process; no DAO, repository or key provider. Widget settings is an ordinary in-app screen with ordinary injection; the door is about the widget's render.
- **WIDGET-04: A widget-only masking flag.** Superseded 2026-11-09 by WIDGET-15. The global `minimal_mode_enabled` flag had no switch and nothing set it; it is removed, and its masking is now each widget's own "Show names and faces".
- **WIDGET-05: Refreshes are debounced.** Every in-app change asks for one refresh 30 seconds later (`ExistingWorkPolicy.KEEP`), so bulk edits do not flood the launcher; `WidgetUpdateScheduler` and `WidgetUpdateWorker` are the only code that updates widgets.
- **WIDGET-06: Refreshes follow the data.** Every change to who can be surfaced asks for a refresh: a call, Later, Sooner, pause and unpause (single and in bulk, and their Undo), membership changes, a list archived, unarchived or deleted (and the Undo of each), and theme changes. The domain use cases fire `WidgetRefreshTrigger` beside their write; the ViewModel-level writes call it beside theirs: unpause on Contact detail and Browse (`ContactDetailViewModel`, `BrowseViewModel`), and a list archived, unarchived or deleted on Home and on Lists (`HomeViewModel`, `ListsManagerViewModel`), each only once the write landed. An hourly sweep catches boundaries no write announces (active hours, while any list had them; none has since LIST-25 on 2026-10-08); a data reset cancels pending refreshes and runs one more so a wiped name never lingers. Pinned by `UpdateTriggersTest` for the use cases and by `HomeViewModelTest` and `ListsManagerViewModelTest` for the list writes.
- **WIDGET-07: Every size has an arrangement.** Both widgets resize from one row to a large card (`SizeMode.Responsive`, `WidgetBreakpoints`), each breakpoint has a deliberate arrangement (`nextCallLayout`, `suggestionsLayout`), and every breakpoint holds a full 48dp Call button.
- **WIDGET-08: Only Call dials.** The lead person's labelled accent Call button opens the dialer (`ACTION_DIAL`, no `CALL_PHONE`); a tap on a person opens their list's deck with them on top. No surface of the widget dials on a stray tap.
- **WIDGET-09: Real previews.** Android 12+ pickers draw `previewLayout` (live, light or dark); `previewImage` is a rendering of the same layout for launchers that only show images. Preview colours copy the Warm theme's tokens and are held to them by `WidgetPreviewResourcesTest`.
- **WIDGET-10: "All quiet for now."** The empty state, with the Orbit glyph, opening Orbit on tap.
- **WIDGET-11: It looks like Orbit.** Android's widget corner radius, the user's theme in light and dark, and the app's avatar (photo, else the monogram on its palette colour, a circle), from the same rules as the in-app `Avatar` (`avatarInitials`, `OrbitTones.avatarPalette`). The first frame, before Orbit has drawn, is the card with the Orbit glyph, not a spinner.
- **WIDGET-12: Each widget has its own source.** A placed widget suggests from every active list (the default, and the value when nothing is stored) or from one list, chosen per `appWidgetId` in Widget settings (`WidgetSettingsActivity`, the provider info's `configure`, with `reconfigurable|configuration_optional`). A change saves at once and redraws only that widget (`WidgetUpdateScheduler.refreshOne`, not debounced; it falls back to the debounced refresh if the redraw throws). The screen refuses an id that is not one of Orbit's placed widgets, and sets `RESULT_OK` on open so a cancelled first configuration keeps the widget.
- **WIDGET-13: A one-list widget reads that list's queue.** Its people are the first `max` of `SurfaceQueueUseCase(listId)`, so it shows the order Card view and Browse show, and applies no filter of its own (IGNORE-08's single-source rule). The cross-list loop takes one head per list and would leave "Call suggestions" with one person.
- **WIDGET-14: A missing list is said, not swapped.** When the chosen list is archived or deleted, the widget shows "Choose a list" and opens Widget settings on tap; it never falls back to every list (`WidgetSurfaceData.sourceMissing`).
- **WIDGET-15: Names and faces, per widget.** A per-widget switch, on by default; off, that widget writes "Contact" and draws the silhouette, in text and TalkBack labels, and so does the settings screen's preview. Replaces WIDGET-04's global flag.
- **WIDGET-16: Widget settings live and die with the widget.** Stored per `appWidgetId` in `AppPrefs` (`widget_list_{id}`, `widget_names_hidden_{id}`; absent means every list, names shown, so widgets placed earlier need no migration). Deleted when the widget is removed (the receivers' `onDeleted`), wiped by reset (SET-06) with the rest of the store, and cleared by import before it replaces the database, since the backup's list ids may name different lists.
- **LAUNCH-01: Shortcuts that name no one.** "Call next" and "Search" as dynamic shortcuts with fixed labels and an action, never a route or list id; `MainActivity` resolves them when tapped, after onboarding. No per-list or per-person shortcuts.
- **LAUNCH-02: Themed icon.** The adaptive launcher icon and the shortcut icons carry a monochrome layer for Android 13+ themed icons: `res/mipmap-anydpi-v26/ic_launcher.xml` declares `<monochrome>` beside its foreground and background, and so does each shortcut icon. Pinned by `LauncherIconTest`, which parses those resources for the element; whether the launcher tints it is a device check.

### Acceptance criteria

- [ ] Both widgets render at every size from one row to a large card (resize test on a device).
- [ ] Updates arrive within ~30s of an in-app state change (WIDGET-05 debounce).
- [x] Tap targets ≥ 48×48dp at every breakpoint (`OrbitWidgetLayoutTest`).
- [ ] Widget survives process death — cold-start renders from persisted state, not a blank placeholder.
- [ ] Voice rules: sentence case, no exclamation.
- [ ] Rounded corners match the other widgets on the home screen (device check; the JVM render cannot clip to outlines).
- [ ] The picker shows the preview, in light and dark (device check).
- [ ] With themed icons on (Android 13+), Orbit's icon is tinted like the rest (device check).
- [x] A shortcut's action and a widget's route resolve to the right landing, and the shortcuts wait for onboarding (`AppLinksTest.landingFor_extraWinsOverAction`, `landingFor_callNext_beforeOnboarding_isNothing`, `landingFor_search_opensGlobalSearch`, `landingFor_unrelatedAction_isNothing`).
- [ ] Long-pressing the icon shows "Call next" and "Search"; "Call next" opens the deck of the person the widget shows (device check).
- [ ] Placing either widget opens Widget settings; backing out leaves a working "Every list" widget (device check: Pixel Launcher).
- [ ] Long-press, then reconfigure, opens the same screen with that widget's choices (device check).
- [ ] Choosing a list redraws only that widget within a few seconds; another widget is unaffected (device check).
- [x] "Call suggestions" on one list shows that list's first three in its queue order (`WidgetSurfaceUseCaseTest.oneList_showsItsQueueInOrder_andIgnoresOtherLists`).
- [x] An archived or deleted list is "missing", never every list (`WidgetSurfaceUseCaseTest.oneList_archived_isSourceMissing`, `oneList_deleted_isSourceMissing_withoutFallingBackToEveryList`); that the widget then draws "Choose a list" is a device check.
- [x] Names and faces off masks every name and face (`WidgetPeopleTest.namesHidden_masksEveryNameWithTheCurtainWord_andDropsEveryFace`) and the preview (`WidgetSettingsViewModelTest`).
- [x] A failed save says so and keeps the previous choice (`WidgetSettingsViewModelTest.a_failed_save_tells_the_user_and_keeps_the_previous_choice`).
- [x] Removing, resetting and importing forget widget settings (`AppPrefsTest.clearWidgetSettings_forgets_only_the_removed_widgets`, `resetAll_clears_widget_settings`, `ImportServiceTest`); that `onDeleted` fires on removal is a device check.

### Not in scope

- Widgets for tablet layouts beyond the sizes above. Defer to v1.1 if users ask.
- Direct swipe-to-defer / swipe-to-prioritize gestures inside the widget. Launcher gesture support is too inconsistent. Tap-through only.
- Later and Sooner buttons on the widget (`vision/12-widgets` WIDGET-1). They change the deck, and a widget has nowhere to offer Undo.
- In-widget notes. Open the app for anything richer than "call now."

### Open product questions

- 4×2 cycling: swipe gesture vs tap-to-cycle? Moot for now: the others are listed, not cycled.
- Should the empty widget say who comes up next and when, as Card view's empty state does? It would need the widget's source to read upcoming people, which today it deliberately does not (see Empty state).
- ~~Should the widget follow the privacy curtain?~~ Answered per widget, 2026-11-09: a widget can leave names and faces off (WIDGET-15). The curtain itself stays a state of Orbit's window.
- A subset of lists per widget (two or three, ranked against each other)? A small step from one list (`WidgetSource` could carry a set), but it makes "what does this widget show?" harder to answer. Only if people ask.
- Launchers with no reconfigure entry leave remove-and-re-add as the only way to change a widget. A "Widgets" row in Settings listing each placed widget would be a fallback, and a second entry point to keep. Only if a launcher we care about lacks reconfigure.
- Should "Call next" (LAUNCH-01) follow a widget's list? No for now: a shortcut belongs to no one widget, and its words must stay fixed.

---

## Technical

### Architecture

Glance (`androidx.glance` 1.1.1).

- `OrbitWidget2x2` / `OrbitWidget4x2` (`GlanceAppWidget`, `SizeMode.Responsive`), each with its `GlanceAppWidgetReceiver`. Class names are permanent: renaming one silently breaks every placed widget.
- `provideGlance` reads everything once before `provideContent`: the people (`WidgetSurfaceUseCase`), the masking flag, the theme (`themeSettingsSnapshot`), and from those the colour providers (`orbitWidgetColorProviders`), the avatar day and night colours (`orbitWidgetAvatarTones`) and each person's face and taps (`widgetPeople`, on the IO dispatcher because it reads photos). The composables in `WidgetContent.kt` only lay them out.
- Glance composes once per breakpoint and Android shows, for the widget's size, the breakpoint that fits and is closest. Inside a composition `LocalSize` is that breakpoint, so the layout functions in `WidgetLayouts.kt` are written over the breakpoints and pinned by `OrbitWidgetLayoutTest`.
- Taps: `AppLinks.openRoute` (a `NAVIGATE_TO` extra for `MainActivity`, with the route as the intent identifier so each person's PendingIntent is distinct) and `dialIntent` (`ACTION_DIAL`); Glance wraps both in `FLAG_IMMUTABLE` PendingIntents.
- Refresh: `WorkManagerWidgetRefreshTrigger` → `WidgetUpdateScheduler` → `WidgetUpdateWorker` (the only code that calls `update`, with `WidgetUpdateScheduler.refreshOne` for one widget from its settings).
- Per-widget settings: `provideGlance` calls `widgetSnapshot`, which turns the `GlanceId` into the `appWidgetId`, reads `AppPrefs.widgetSettings`, and passes the source to `WidgetSurfaceUseCase(source, max)` and `namesHidden` to `widgetPeople`. `sourceMissing` swaps the body for `ChooseListBody`.
- Widget settings is `WidgetSettingsActivity`, a separate activity rather than a route in `MainActivity`: the launcher starts a configure activity for a result about one widget, and routing it through the main task would bring the open Orbit screen forward and tangle the result with its back stack. Own task (`taskAffinity=""`), excluded from recents, exported (the launcher starts it). It holds the curtain from its own ON_STOP / ON_START, as `MainActivity` does, and `FLAG_SECURE` in release. Its ViewModel writes on the application scope, one write at a time in tap order, and redraws through `OneWidgetRefresher`.
- Launcher shortcuts: `LauncherShortcuts.publish` from `OrbitApp.onCreate` (off the main thread, writes only when the set differs); `MainActivity.routeFrom` resolves `AppLinks.ACTION_CALL_NEXT` / `ACTION_SEARCH`.

### Data model

`WidgetSurfaceData`: `primary`, `alternatives` (0 to 2, deduped across lists), `listIdByContactId`, the list that surfaced each shown person (where they rank earliest), which their tap opens, and `sourceMissing` (WIDGET-14). Each widget's settings are two `AppPrefs` keys per `appWidgetId` (WIDGET-16); Glance's own state is unused, because reset already wipes `AppPrefs` and a second store would be one more thing for reset and import to remember.

### Permissions / integrations

- **Manifest:** both receivers, `exported="false"`, with `res/xml/widget_info_*.xml`; `WidgetSettingsActivity`, `exported="true"` with the `APPWIDGET_CONFIGURE` filter, which refuses any id that is not one of Orbit's placed widgets.
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
- An `update()` that lands while a widget's Glance session is still alive (it lingers after each render) only recomposes that session; `provideGlance` does not run again, so anything read there is stale. Found on a device 2026-11-09: a settings change seconds after placing the widget redrew it with the old settings. The running session therefore watches its own settings (`rememberWidgetSnapshot`) and re-reads when they change. Data changes still arrive only through `update()`, so a debounced refresh that lands inside a live session may draw the previous people until the next one (reasoned from the same mechanism, not observed).
- `appWidgetId`, not `GlanceId`, keys a widget's settings: `GlanceId` is opaque. `GlanceAppWidgetManager.getAppWidgetId` converts.
- A widget's taps to Widget settings carry the id in the data URI as well as the extra: PendingIntents compare intents without extras, so two widgets would otherwise share one and open each other's settings.
- `onDeleted` cleanup does not use `goAsync`: Glance's own `onDeleted` takes the receiver's pending result. A cleanup lost to process death leaves keys for an id Android does not reuse.
- After a launcher backup and restore, widgets can come back under new ids. Orbit sets `allowBackup="false"`, so its prefs do not travel, and a restored widget reads absent keys: every list, names shown.

### Not in scope (technical)

- Legacy `RemoteViews`-based widget implementation. Glance is the forward path.
- In-widget composable animations. Static UI.

### Open technical questions

- ~~`androidx.glance` is not yet in the catalog.~~ Resolved: Glance 1.1.1 is in `libs.versions.toml`.
- Does One UI honour `configuration_optional`? Where a launcher ignores it, cancelling the first Widget settings could remove the widget; setting `RESULT_OK` on open is meant to make that moot. Device check.
