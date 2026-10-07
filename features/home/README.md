# home

**Status:** in-progress
**Last reviewed:** 2026-10-07 (HOME-14: the calls waiting for a note replace the post-call banner)
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/home/` (`HomeScreen.kt`, `HomeViewModel.kt`, `HomeUiState.kt`, `RhythmDaySheet.kt`), `android/app/src/main/java/app/orbit/data/feed/HomeFeed.kt`, `android/app/src/main/res/values/strings_home.xml`; the calls waiting for a note (HOME-14): `android/app/src/main/java/app/orbit/ui/components/NotesWaitingStack.kt`, their state in `AppViewModel.notesWaiting` over `data/repository/WaitingCalls.kt` (NOTE-05)
- Tests: `android/app/src/test/java/app/orbit/ui/screens/home/HomeViewModelTest.kt`, `HomeTileMenuTest.kt`, `HomeContentTest.kt`, `HomeNotesWaitingTest.kt`, `RhythmDaySheetTest.kt`; `android/app/src/test/java/app/orbit/AppViewModelTest.kt` (the stack's state and dismissals); `android/app/src/test/java/app/orbit/data/feed/HomeFeedRhythmTest.kt`; the archived-list gate in `android/app/src/test/java/app/orbit/notify/ListPromptWorkerTest.kt`; the gallery previews `HomeContentPreview`, `HomeContentLongNamesPreview`, `HomeContentNotesWaitingPreview`, `HomeContentEmptyPreview`, `HomeContentLoadingPreview`, `HomeContentErrorPreview`, `RhythmDaySheetBodyPreview`, and the stack's own `NotesWaitingStack*Preview`s (`PreviewGalleryTest`, with the curtain pass)

---

## Product

### Why it exists

Home is the front door after unlock. Its job is to orient the user in two seconds and hand them someone to call: one card per list, each already showing the person that list would surface first ("Next up", HOME-3), how long it has been, and a quiet button that opens the dialer in one tap (HOME-9). It is a launchpad, not a dashboard. There is no inbox to clear, no count of people "due" and no "you're done" (HOME-6): the right framing is a person worth reaching, available at any moment, and Home should never read as a backlog.

The page view is [`features/page-views/home.md`](../page-views/home.md); the reasoning behind each move is `vision/00-home/00-home.md`.

### User story

As a user, I open the app and see, for each of my lists, who I would reach first and how long it has been. I call them from the card, open the list's deck to decide one person at a time, or glance at the last seven days to remember who I spoke with. I manage a list from its card without leaving Home.

### Behavior

**What is on the screen.**

- App bar: the Orbit wordmark, then Search, Lists (a bulleted-list icon; the plain hamburger also meant "list options" on Card view) and Settings.
- A calm date header (HOME-6): the eyebrow "Today" over the date ("Wednesday 3 June"). No count, no "ready", no "caught up". Shown in the Ready state only; Loading is quiet chrome and Empty carries the first-install CTA.
- One full-width tonal card per active list (HOME-5), in the order set on Lists Manager. The tinted name block carries the list's name, its size ("4 people", "No one yet"; the line stays quiet while the count is still hydrating) and, on a smart list, a glyph that TalkBack reads as "Smart list" (LIST-07; type is not a name, so it stays visible under the privacy curtain).
- "Next up" on every card (HOME-3): the head of that list's queue, the exact person Card view would show first (`SurfaceNextUseCase` via `HomeFeed.enrichment`), with their face or initial, their first name and a warm recency line: "You spoke today", "You spoke yesterday", "You spoke 3 weeks ago", "You haven't spoken yet", in the app's one "ago" wording (`formatAgo`). Never "you haven't called X in N days" (voice.md); until 2026-10-06 the line read "{span} since you last spoke", which for a gap under two weeks rendered "3 days since you last spoke", the same framing, hidden from the string audit because the span arrived as an argument. A list with people but nobody to suggest reads "All quiet for now"; never "caught up" or "no one due".
- One tap to a call (HOME-9): the "Next up" row ends in a quiet, muted, labelled phone button ("Call Kai") that opens the dialer with the number filled in; Orbit never places the call itself. The rest of the card opens the list's deck. Muted, not accent: rules.md Design 6 allows a quiet dial per person row. A person with no number has no button. Masked under the privacy curtain ("Call Someone").
- The "Last 7 days" rhythm strip under each card (HOME-7, HOME-8), described below.
- "New list" after the last card, and the reflection line at the foot of the scroll.
- At the top of the list, the calls waiting for a note (HOME-14, below): one as a card, two or more as a pile.
- Smart lists get cards like static ones (2026-10-05): their rule's matches are stored as members (`SmartListMembershipSync`, `features/orbit-lists/README.md`), so size and "Next up" work the same. Before, a smart list's card surfaced no one.

**The one accent.** On a fresh install the accent is spent on "Create your first list". With lists on the page nothing is in the accent: the call buttons are muted, today's weekday letter is ink in SemiBold, and the calls waiting for a note use a Secondary "Add a note" (the post-call banner they replaced spent the accent on its "Add a note" and its icon, a second accent on a screen whose rule is none). Until 2026-10-06 today's letter was painted in the accent on every card, so a Home with N lists spent the screen's one accent N times on letters nobody taps (rules.md Design 5); the prototype draws it that way and the app deliberately does not.

**Today is derived once per resume (`HOME-12`, added 2026-10-06).** Home is the root destination and stays composed across the dialer round-trip and across midnight. The date header and the strip's weekday letters are keyed on one `today` value the screen refreshes on every resume, and the same resume tells `HomeFeed` the date (`noteToday`) so every list's rhythm re-buckets when the day has changed. Before, a `LocalDate.now()` read once at composition left the header on yesterday's date and the letters a day behind the feed's buckets until Home was recomposed from scratch; and because `HomeFeed.buildRhythm` buckets only when a Room flow re-emits, a UI-only fix would have drawn the right letters over yesterday's buckets. A resume on the same date is a no-op (the feed's date is a StateFlow, so equal values conflate), which keeps ADR 0006's "no projection re-fire on navigation".

**Calls waiting for a note (`HOME-14`, added 2026-10-07).** Replaces the single post-call banner ("You just called Sam", NOTE-02), which showed only the latest outgoing call of the last ten minutes and forgot a dismissal when the process died. The owner asked for every unnoted call of the last day, stacked and each closable, and a cleaner look (`vision/flows/owner-review-2026-10-07.md`, decision 12). Which calls wait is NOTE-05 in [`contact-detail`](../contact-detail/README.md): a connected call of a minute or more, either direction, in the last 24 hours, with someone on a list, nothing written about them since, not dismissed; one per person.

- One call is one clean card: the person's face, "You called Kai" or "Kai called you", "14 min · 2 hours ago" (the app's duration words and `formatRelativeFine`'s time since), a Secondary "Add a note" and a Ghost "Dismiss".
- Two or more lie in a pile: the newest card on top with the edges of one or two more under it, and "3 calls to write about". The whole pile is one button; a tap opens it, accordion style, into one row per call (face, the two lines, "Add a note", "Dismiss"), then "Dismiss all"; the count line at the top folds it again. Opening, closing and a row leaving are a short fade and resize, instant with the system's animations off.
- "Add a note" opens the post-call note page for that call (NOTE-04); the call keeps waiting until a note is saved. "Dismiss" and "Dismiss all" close calls for good (persisted, NOTE-05), with "Dismissed 1 call" / "Dismissed 3 calls" and Undo; a dismissal that could not be saved says "Couldn't save your change". A dismissal also takes away that call's notification (NOTIF-16).
- TalkBack: the closed pile is one button, "3 calls to write about, Collapsed"; open, the count line says "Expanded" and folds it. Each call's buttons say whose call it is ("Add a note about your call with Kai", "Dismiss your call with Kai").
- Privacy curtain: "You called someone" / "Someone called you", no photo, initials from "Someone", and the buttons read "Add a note about this call" / "Dismiss this call".
- Placement: the first item of the scrolling list, so an open pile scrolls with the cards however tall it gets (the banner sat above the list and could not scroll). When it arrives after the cards while the list is at the top, the list scrolls up to show it.
- Freshness: the 24 hour window moves to now on every resume (the `LifecycleResumeEffect` that drives HOME-12), so a day-old call leaves the stack even while Home stays composed; a note saved anywhere and a dismissal take a call off live, through Room and DataStore.

**Error state (`HOME-10`, added 2026-10-05; the feed covered 2026-10-06).** If the lists cannot be read, Home says "Orbit couldn't load your lists", that nothing is lost, and offers Try again, which re-subscribes. The failure can come from the ViewModel's own source (the member counts) or from either of `HomeFeed`'s projections (`tiles`, `enrichment`): the feed catches what its sources throw and reports it as data (`HomeFeed.failed`), and Try again re-subscribes the feed as well as the ViewModel. Before 2026-10-06 the feed's flows had no catch, so a failing `observeActive()` escaped the handler-less application scope and crashed the app, and HOME-10 held only for the member-count query. Before 2026-10-05 a failed read ended the stream and Home sat on stale or empty chrome (UX rubric D6).

**Empty state (`HOME-11`).** "Start with the people you keep meaning to call." with the one accent button, "Create your first list", which routes to Lists Manager with the create sheet already open. Shown only when no list exists: a Loading state covers the slow-SQLCipher cold-open window as quiet chrome, so the CTA never flashes at a user who has lists (ADR 0006 reserves it for the genuinely empty case). Home does not route to onboarding; the cold-start destination is decided by `AppViewModel` from the `onboardingComplete` flag before Home composes.

**List card long-press: quick actions (added 2026-06-08).**

Long-press on a card (a haptic tick; TalkBack: "Quick actions") opens an anchored menu of manage-this-list actions, so the actions otherwise two screens deep in Lists Manager are reachable without leaving Home. Tap is unchanged (it routes to Card view for the list) and the menu never duplicates it. The menu is built by `homeTileMenuActions` and pinned by `HomeTileMenuTest`.

- Menu items, in order:
  1. **Add people**: routes to the contact picker scoped to this list (`Routes.pickContacts(listId)`). Not offered on smart lists (2026-10-06): a smart list's members are what its rule matches, and anyone added by hand was removed by the next reconcile with no message, the silent fallback rules.md Code 3 forbids. Lists Manager hides its "+" for the same reason, so the two surfaces offer the same actions.
  2. **List settings**: opens List settings (`Routes.listConfig(listId)`).
  3. **Pause nudges** / **Resume nudges**: pauses or resumes the list's nudges in place, no navigation. Label and effect reflect the list's current `notificationsEnabled`. Confirms with a brief snackbar ("Nudges paused." / "Nudges on."). The words are the glossary's (voice.md): a list's nudges are paused and resumed, the list itself is not paused, so the inverse is never "Unpause".
  4. a divider
  5. **Archive**: removes the list from Home, reversible, with the supporting line "Hides {list} from home. You can restore it." (the same line the Lists row shows; until 2026-10-06 Archive explained itself there and was a bare word here). Under the privacy curtain the line carries the masked name. Reuses the existing "List archived." + Undo snackbar.
  6. **Delete**: destructive. Opens the existing confirmation dialog ("This removes the list. People stay in your contacts."), then deletes with an Undo snackbar (see the decision below).
- Archive and delete end the list's nudge chain, and undoing an archive re-schedules it (NOTIF-11, `features/notifications/README.md`). Home's mutations mirror `ListsManagerViewModel`'s exactly, `NudgeScheduler` calls included; until 2026-10-06 Home flipped the flag only, and the chain kept nudging for a list the user had put away until they unarchived and re-archived from Lists Manager. As defence in depth the nudge worker also skips, and lets its chain end, for an archived list.
- A change that could not be saved says "Couldn't save your change" and nothing else: no success snackbar, and no Undo that would undo nothing (rules.md Code 3). The ViewModel's `runMutation` returns whether the write landed and the success event is sent only then.
- Long-press fires a single haptic tick on entry. Only one menu is open at a time; long-pressing another card (or tapping out) dismisses the current one.
- **"Start this list" is intentionally not an item.** A plain tap already routes to Card view scoped to the list, so a menu entry would duplicate the primary gesture. Resolved against ground truth, not assumption (`HomeScreen.kt` card `onClick` routes to `Routes.card(listId)`).
- **Delete is reachable directly here** (not gated behind archive-first as it is in Lists Manager's archived section). Removing that archive buffer is why this surface's Delete carries an Undo; see Open product questions.

**7-day rhythm strip (`HOME-7`; direction and tap-through `HOME-8`, added 2026-07-31).**

The strip under each card shows the list's last 7 days, one stacked bar per qualifying call (at least 3 minutes; the 3-minute floor and the per-list relative scaling are HOME-7 and unchanged). HOME-8 makes it answer who and which way.

- **Direction rim.** Each bar keeps its per-person fill (`OrbitTones.rhythmBarForId`) and wears a direction mark (`directionMark` in `RhythmDaySheet.kt`): a 3dp rim, `OrbitColors.directionOutgoing` (pink) for a call you made and `directionIncoming` (teal) for one you received, then a 1.5dp near-black ring (`directionSeparator`) that insulates the rim from the fill. Fill is the person, rim is the direction; the two encodings never share a channel. The pair is a semantic colour slot, not a per-theme tone: a theme-derived direction hue would collide with the Cool and Plum accents. A two-swatch key ("You" / "Them") sits on the "Last 7 days" line; the swatch is the bar mark itself (rim, ring, neutral fill), so the key cannot teach a different encoding than the strip uses.
  - Until 2026-10-07 the rim was a 2dp violet (`#5E3D96`) or blue (`#2F84B8`) border drawn straight onto the fill, and a fill of similar lightness swallowed it: the owner could not tell who called whom. The new pair is ~180° apart in hue and separated in lightness (1.6:1 light, 2.4:1 dark), which is what still tells them apart under a colour-vision deficiency. `ThemeContrastTest` holds each rim to 3:1 against the card, every list wash and the ring, and the pair to 1.5:1 against each other, in every theme and accent hue.
- **Bar floor.** The mark raises the per-bar minimum to 14dp (3dp of rim and 1.5dp of ring top and bottom leave 5dp of person colour). On a crowded day the floor yields to an even split of the height the 3dp gaps leave over, so a many-call day still fits the 48dp strip instead of overflowing it, and below the floor the rim and ring shrink in proportion so the fill never disappears.
- **Tap a day to see who.** A day column with at least one call is a tap target ("See this day") and opens `RhythmDaySheet`: the day header ("Today" / "Yesterday" / "Wednesday 3 June"), a symmetric summary ("You called 2 · They called 1", the empty half dropped rather than printed as a zero), then one row per call: avatar wearing the same direction mark (rim, then ring), name, "You called" / "They called", duration, wall-clock time. A row taps through to Contact detail (no note focus; this is "who was that", not a post-call prompt). A quiet day has nothing to open and stays inert.
- **What TalkBack hears (added 2026-10-06).** A day column is one node named by the day, not by its drawn letter: "Thursday 1 October, 2 calls. You called 1 · They called 1. Tap to see who." The spoken day comes from the same `formatDayHeader` call the sheet's title uses, so a column and the sheet it opens can never disagree. A quiet day is still one node and announces "Friday 2 October, No calls". Before, TalkBack read the one-letter glyph: "T" is Tuesday or Thursday and "S" either weekend day, and a quiet day was a lone letter.
- **Privacy curtain (PRIV-03).** Sheet names mask to "Someone", photos are withheld, and initials derive from the masked literal (the `BrowseRow` / `CallLogScreen` convention). The strip itself is already name-free.
- **Data.** `RhythmCall` carries `callEventId`, `contactName`, `photoUri`, `direction`, and pre-formatted `durationLabel` / `timeLabel`. Names hydrate in `HomeFeed.enrichOne` from `ContactRepository.observeForListMembers(listId)` (Room shares the query with `SurfaceNextUseCase`'s own member read; no extra round trip). Labels are formatted in the data layer so composables stay free of `Instant` and the JVM clock, matching `CallLogRow`. A contact removed from the list after the call renders as "Someone" rather than dropping the call. Manual "Logged" connections are written with `durationSeconds = 0` and never clear the 3-minute floor, so `direction` here is always carrier-observed.
- **Voice.** The two directions are drawn and named symmetrically, always same weight; counts appear only inside the sheet the user opened deliberately, never on the card surface; no ratio, target, balance score or streak. See `vision/00-home/00-home.md` HOME-8 for the full brand-risk reasoning.

**Retired.** "Surprise me" (HOME-1 in the vision doc) shipped as a cross-list one-tap pick and was removed from Home on 2026-06-12: a random "just give me someone" fought the model of intentional recommendation, and the always-on "Next up" delivers the "zero deciding, one name" payoff without the gamble. ADR 0007 (`features/_foundations/ADRs/0007-surprise-me-cross-list.md`), which specified the pick, is superseded and kept as a record. The per-card "due count" badge, the "N people ready" header and the "All caught up" state (HOME-2) were retired by HOME-6 on 2026-06-22; the word "due" is now off every screen (voice.md, Never say).

### Acceptance criteria

- [x] One full-width card per active list, in Lists Manager order, with name, size and "Next up" from live flows (`HomeFeed.tiles` + `HomeFeed.enrichment`, `ListRepository.observeMemberCountsByListId`). `HomeViewModelTest`: `multiple tiles project to Ready preserving order`, `one tile emits Ready with one tile`, `next up carries the person and the number the call button dials`.
- [x] The header is the date only: no count, no "caught up", no "due" (HOME-6). `HomeUiState.Ready` carries only `lists`; `VoiceAuditTest` holds the strings.
- [x] The "Next up" why line reads "You haven't spoken yet", "You spoke today", "You spoke yesterday" or "You spoke {3 weeks ago}", in the app's one "ago" wording, and breaks no voice rule for any gap. `HomeViewModelTest`: `the why line reads never, today, yesterday or how long ago you spoke`; `WhyLineVoiceTest`.
- [x] "Call {first name}" on each card with a number (HOME-9), muted, 48dp; "Call Someone" under the curtain; no button without a number. `HomeContentTest`: `the_call_button_names_the_person_by_first_name`, `under_the_curtain_the_call_button_says_Someone`; `HomeViewModelTest`: `a blank phone hides the call button`.
- [x] If the lists cannot be read, from the ViewModel's source or from either feed projection, Home shows "Orbit couldn't load your lists" with Try again, which re-subscribes and recovers (HOME-10). `HomeViewModelTest`: `failing member counts emit Error and retry recovers`, `a failed feed maps to Error and Try again asks the feed to retry`, `a throwing list read inside the real feed shows Error and recovers on retry`.
- [x] The Empty state (create-first-list CTA) renders only when no list exists; Loading renders quiet chrome, never the CTA (HOME-11). `HomeViewModelTest`: `empty tiles emits Empty`; `HomeContentLoadingPreview` and `HomeContentEmptyPreview` in the gallery.
- [x] The date header and the weekday letters follow the day Home resumed on, and the strip re-buckets on the same resume (HOME-12). `HomeFeedRhythmTest`: `the strip re-buckets when told the day has changed`; `HomeScreen.kt` derives `today` in its `LifecycleResumeEffect` and passes it to `HomeContent`.
- [x] Voice rules pass: sentence case, no exclamation, no gamification copy. `VoiceAuditTest` over `strings_home.xml`.
- [x] Dark mode and 200% font scale render without clipping; every control has a TalkBack label and a 48dp target. `PreviewGalleryTest` over the Home previews (light and dark, font scales 0.85 to 2.0; `a11y-report.md` "None").
- [x] Long-press a card opens the quick-actions menu; a plain tap still routes to Card view (unchanged). `HomeContentTest`: `long_press_opens_the_menu_in_the_pinned_order`; `ListTile.onClick` routes through `onOpenList`.
- [x] Menu order is exactly: Add people · List settings · Pause/Resume nudges · (divider) · Archive · Delete. `HomeTileMenuTest`: `the order is Add people, List settings, Pause nudges, Archive, Delete`, `archive and delete are the only destructive entries`; `HomeContentTest` reads the same order off the open menu.
- [x] "Add people" is absent from a smart list's menu. `HomeTileMenuTest`: `a smart list is not offered Add people`.
- [x] "Pause nudges" / "Resume nudges" matches the list's current `notificationsEnabled`; tapping it flips the flag in place with a confirming snackbar ("Nudges paused." / "Nudges on.") and no navigation. `HomeTileMenuTest`: `paused nudges read Resume nudges in the same slot`; `HomeViewModelTest`: `toggleNotifications flips the flag in place and confirms with the matching copy`.
- [x] Archive carries "Hides {list} from home. You can restore it.", with the masked name under the curtain. `HomeTileMenuTest`: `archive says what it does to the named list`, `under the curtain the supporting line carries the masked name`.
- [x] Archive shows "List archived." with Undo; Delete opens the confirmation dialog, then "List deleted." with Undo, and the purge is held until the Undo window closes. `HomeViewModelTest`: `archiveList cancels the nudge chain and offers Undo, and undoArchive schedules it again`, `requestDelete hides the tile optimistically and undoDelete restores it`, `requestDelete offers Undo and commitDelete purges the list and ends its nudge chain`.
- [x] Archiving or deleting from Home ends the list's nudge chain and undoing an archive re-schedules it (NOTIF-11); the worker never posts for an archived list. The two `HomeViewModelTest` cases above; `ListPromptWorkerTest`: `worker_skipsAndEndsChain_whenListArchived`.
- [x] A write that fails says "Couldn't save your change" once, with no success snackbar and no Undo. `HomeViewModelTest`: `a failed archive emits only the failure and leaves the nudge chain alone`, `a failed nudge toggle emits only the failure`.
- [x] Only one quick-actions menu is open at a time; long-pressing another card or tapping out dismisses it. `menuAnchorListId` in `HomeContent` is one nullable value with one writer (rules.md Code 7); the menu renders where it equals the card's id.
- [x] Long-press carries an `onLongClickLabel` ("Quick actions") and every menu item is at least 48dp with a TalkBack-readable label. `ListTile`'s `combinedClickable`; `OrbitDropdownMenu` renders Material `DropdownMenuItem`s (48dp) with the resolved label as text.
- [x] Every rhythm bar carries a direction rim; the rim colour is the same in every theme, and the "You"/"Them" key renders on the "Last 7 days" line. `DayColumn` marks every bar with `directionMark` in `directionColor`, a semantic `OrbitColors` slot shared by every theme, insulated from the fill by the `directionSeparator` ring; `DirectionLegend` sits in the eyebrow row.
- [x] A day with calls opens the day sheet on tap; a quiet day is inert (no tap target, no empty sheet). `HomeContentTest`: `a_day_with_calls_announces_the_full_day_and_what_happened` (has a click action), `a_quiet_day_announces_No_calls_and_is_inert` (has none).
- [x] A day column announces to TalkBack as one node named by the full day ("Thursday 1 October, 1 call. You called 1. Tap to see who."), never a bare weekday letter; a quiet day announces "{day}, No calls". The same two `HomeContentTest` cases.
- [x] The smart-list glyph announces "Smart list"; a static list's card does not. `HomeContentTest`: `a_smart_list_card_says_so`.
- [x] The day sheet's summary drops the empty half instead of printing "They called 0", and never shows a ratio, target or streak. `RhythmDaySheetTest`.
- [x] A many-call day's bars stay inside the 48dp strip. `DayColumn`'s per-bar floor yields to an even split of the strip's height (the `minBar` budget).
- [x] Under the privacy curtain the day sheet shows "Someone" with no photo and masked initials, and no card leaks a person's or list's name. `RhythmCallRow` masks with `home_rhythm_someone`; the gallery's curtain pass (`PreviewGalleryTest -Porbit.screenshots.curtain`, `curtain-report.md` "None") over the Home and day-sheet previews.
- [x] Today's weekday letter is ink in SemiBold, and with lists on the page nothing on Home is in the accent (rules.md Design 5). `DayColumn` colours today `colors.fg`; the gallery renders of `HomeContentPreview`.
- [x] One waiting call is a single card whose buttons name the person; three are a closed pile, one button that says how many and that it is collapsed, which opens into a row per call and folds again; Dismiss hands Home one call and Dismiss all every one; under the curtain no name reaches text or TalkBack (HOME-14). `HomeNotesWaitingTest`.
- [x] The stack reads nothing until Home resumes, words each call ("14 min · 2 hours ago"), keeps the 24 hour window and the minute floor, and a dismissal says so with an Undo that restores it, or says "Couldn't save your change" with nothing to undo (HOME-14). `AppViewModelTest`.

### Not in scope

- Notifications. Covered by `features/notifications/README.md` (the nudge worker's archived-list gate lives there in spirit; its test is `ListPromptWorkerTest`).
- Widgets. Covered by `features/widgets/README.md`.
- Per-list configuration UI. Lives in `features/orbit-lists/README.md`.
- Sorting lists by urgency or alphabetically. List order is user-controlled via Lists Manager.

### Open product questions

- ~~Should "surprise me" weight by most-overdue-across-all-lists, or most-recently-interacted list?~~ Resolved 2026-05-04 (most-overdue, per contact; ADR 0007), then moot: "Surprise me" was removed from Home on 2026-06-12 and ADR 0007 is superseded.
- If only one list exists, does home auto-skip to card-view, or still render the single card? Leaning render for consistency. A one-list Home has one card and a long quiet canvas; the composed one-list layout the rubric mentions has not been designed (vision/00-home HOME-5).
- ~~Should "Start this list" be the hero long-press action?~~ Resolved 2026-06-08: no. A plain tap already routes to Card view scoped to the list, so the menu is manage-only and omits it.
- **Delete recoverability.** Resolved 2026-06-08: long-press Delete uses the confirmation dialog plus an Undo snackbar (soft-delete window). Rationale: surfacing Delete directly on home removes the archive-first buffer that previously justified Lists Manager's no-Undo delete. With the buffer gone, a fast confirm-tap must still be recoverable, and the warm, unhurried voice expects destructive actions to feel safe. ~~(Lists Manager's archived-section Delete should adopt the same Undo so the two surfaces stay consistent.)~~ Resolved 2026-10-05: it does, with the same "List deleted." + Undo and deferred delete.
- **Browse-from-home.** Should the menu also offer "Browse list" (scan the whole roster) now that tap goes to the decide-loop rather than a list view? Left out for v1 to keep the menu to manage-actions only; revisit if users want a non-loop way to view members from home.

---

## Technical

### Architecture

UI-only screen. `HomeViewModel` is a thin subscriber to the process-scoped `HomeFeed` singleton (ADR 0006 Rule 1) and exposes one `StateFlow<HomeUiState>` (ARCH-02, `WhileSubscribed(5_000L)`). No DAO access from UI. Navigation Compose routes into Card view and the other destinations.

The calls waiting for a note (HOME-14) are a second, separate state: `AppViewModel.notesWaiting` (`StateFlow<List<NoteWaiting>>`, `WhileSubscribed(5_000L)`), the precedent the post-call banner set, because they are not a list's data and `HomeFeed` knows nothing of notes. `HomeScreen` collects it, calls `AppViewModel.onHomeResumed()` on every resume, and hands `HomeContent` the list, its "Add a note" leg (the NavHost's `onOpenPostCallNote`) and the dismissals. Whether the pile is open is `HomeContent`'s own `rememberSaveable` (rules.md Code 7).

State shape (`ui/screens/home/HomeUiState.kt`, a sealed interface, every variant `@Immutable`):
```
HomeUiState.Loading                  // pre-first-database-answer window only (quiet chrome, no skeleton)
HomeUiState.Error                    // HOME-10: a source could not be read; Try again
HomeUiState.Ready(
    lists: List<ListTileState>,      // id, name, dueCount, type, nextUp, rhythm, notificationsEnabled, memberCount
)
HomeUiState.Empty                    // zero lists in Room: create-first-list CTA (HOME-11)
```
There is no count on `Ready`: HOME-6 retired the header, and the per-list membership observers that computed a distinct due-contact union (one Room query per visible list on every subscription) went with it on 2026-10-06. `dueCount` on a tile is `ListEntity.dueCount`, the denormalized column (ADR 0006 Rule 2), passed through for the feed's other consumers; nothing on Home renders it.

**Cache-first.** When `HomeFeed.tiles` already holds real tiles, the `stateIn` initial value renders them synchronously with the cached enrichment, so "Next up" never blinks on re-entry. The feed's `emptyList()` placeholder before the first database answer maps to `Loading`, never `Empty`.

**Failure as data (HOME-10).** `HomeFeed.tiles` and `HomeFeed.enrichment` are `flatMapLatest` over an internal retry counter; each catches what its sources throw into its own flag (one writer each, rules.md Code 7), and `HomeFeed.failed` is the OR of the two. The flag is set in the catch and cleared by the projection's next successful emission, not by `retry()`: cleared at retry, the ViewModel would see "not failed" with the stale value still in the StateFlow and flash Empty until the fresh read answered. `HomeViewModel.uiState` combines `failed` with its own sources and maps it to `Error` before reading the cached tiles; `onRetry` calls `HomeFeed.retry()` and bumps its own retry counter so the member-count source re-subscribes too. The shape BrowseFeed uses for BROWSE-06, adapted because `tiles` and `enrichment` are plain vals with no per-key cache to evict.

**Today (HOME-12).** `HomeScreen` holds one `today: LocalDate`, written in its `LifecycleResumeEffect`, and passes it to `HomeContent`, the date header, `RhythmStrip` (glyphs and spoken labels) and `rhythmDayLabel` (the sheet title). The same effect calls `HomeViewModel.onResumed()`, which hands `HomeFeed.noteToday` the clock's local date; `enrichOne` combines that `StateFlow<LocalDate?>` as a trigger so `buildRhythm` (which still reads `clock.now()`) re-buckets when the date changed and not otherwise.

**Long-press quick-actions menu.** The card is `combinedClickable(onClick, onLongClick, onLongClickLabel = "Quick actions")` with a haptic tick on long-press entry, the precedent from `BrowseListScreen.kt`. The menu is `OrbitDropdownMenu` over `homeTileMenuActions(resources, listName, notificationsEnabled, type, ...)`, an `internal` pure builder in `HomeScreen.kt` in the shape of `listRowMenuActions` and `browseRowMenuActions`, so `HomeTileMenuTest` pins the order, the destructive tier, the smart-list omission and the supporting line against real resources. The open menu is a single nullable `menuAnchorListId` in `HomeContent`, so only one shows at a time. The items dispatch callbacks the stateless `HomeContent` receives and `OrbitNavHost` wires:

- `onAddPeople(listId)` routes to `Routes.pickContacts(listId)` (not offered for `ListType.SMART`)
- `onListSettings(listId)` routes to `Routes.listConfig(listId)`
- `onToggleMute(listId, currentlyEnabled)` flips `notificationsEnabled` in place
- `onArchive(listId)` and `onDeleteConfirmed(listId)`: list mutations plus snackbar feedback

Navigation callbacks stay in the NavHost. The mutations (`toggleNotifications`, `archiveList`, `undoArchive`, `requestDelete` / `commitDelete`) call `ListRepository` directly, inside a `runMutation` wrapper that surfaces a failure as "Couldn't save your change" and returns whether the write landed; a success snackbar is sent only on `true`. Archive and delete call `NudgeScheduler.cancel(listId)` inside the same `runMutation` as the write, and undo-archive calls `scheduleFromEntity` after the write, exactly as `ListsManagerViewModel` does (NOTIF-11), so the repo write and the WorkManager change move together.

`HomeSnackbarEvent` (message, optional action label, payload list id, kind) is the one event type both Home and Lists Manager emit; its copy is `UiText` resolved by the screen.

### Data model

Reads: `ListEntity` (name, order, archived flag, type, `notificationsEnabled`, `dueCount`) through `HomeFeed.tiles`; per-list enrichment (`ListEnrichment(nextUp: NextUpRaw?, rhythm: List<RhythmDay>)`) through `HomeFeed.enrichment`, one `SurfaceNextUseCase` plus one call-events observer plus one member observer per active list; member counts through `ListRepository.observeMemberCountsByListId()`.

`ListTileState` (`ui/screens/home/HomeUiState.kt`): `id`, `name`, `dueCount`, `type` (`ListType.SMART` draws the glyph and drops "Add people"), `nextUp: NextUp?` (contact id, name, photo, the `UiText` why line, `phone` for the call button; null until enrichment hydrates or when nobody is surfaceable), `rhythm: List<RhythmDay>` (index 0 is six days ago, index 6 today), `notificationsEnabled` (straight from the entity, so the menu reads "Pause nudges" or "Resume nudges" without a second read), `memberCount: Int?` (null until hydrated; the subtitle stays quiet rather than showing a wrong "No one yet").

`RhythmCall` carries `callEventId`, `contactId`, `contactName` (null for someone no longer on the list, shown as "Someone"), `photoUri`, `durationSeconds`, `direction`, `durationLabel` (`UiText`) and `timeLabel` (a clock string).

### Permissions / integrations

None directly. Depends on call-detection and contacts-ingestion having populated Room. The call button hands a `tel:` intent to the dialer (`dialPhoneNumber`); Orbit never places the call (PRIV-05).

### Known gotchas

- **Terminology: one name for the config screen.** The destination was labeled "Configure" in the Lists Manager overflow menu and in `ArchivedListRow.kt` until 2026-06-08, when this feature introduced "List settings" on Home and both were renamed to match (`ListRow.kt` reads `lists_menu_list_settings`), so Home and Lists Manager never name one action two ways. "List settings" (not bare "Settings") avoids colliding with the app-level Settings gear in the Home app bar.
- **Snackbar sequencing: the swallowed-toast trap.** Home's action snackbars share the bug found 2026-06-08 in Lists Manager (quick-task `260608` and the rename fix that followed): an archive snackbar carries an "Undo" action, which makes Material3's `showSnackbar` default to `Indefinite` duration; if the host collects events with a plain `collect`, the collector stays suspended in that indefinite snackbar and a following "List deleted." (or any next event) is buffered and never shown, leaving a stale "Undo" pointing at a list that's already gone. Home's snackbar host must collect with `collectLatest` (the latest action's feedback wins and cancels the prior snackbar), and any Undo payload must ride the event (survive navigation) rather than a screen-scoped `launch`. The event flow is buffered (four slots) so a failure event that follows an action is never the one dropped.
- **Success only on success.** `runMutation` returns a Boolean for a reason: until 2026-10-06 `archiveList` emitted "List archived." with Undo whether or not the write landed, so a failed archive was followed by a success message and an Undo that unarchived nothing (rules.md Code 3). The same applied to the nudge toggle. Keep the success emit behind the result.
- **Delete-with-Undo needs deferred delete.** (Done on both surfaces 2026-10-05: `ListsManagerViewModel.deleteList` now hides the row, emits the same `HomeSnackbarEvent`, and purges in `commitDelete` when the snackbar closes. The original note follows.) The old `ListsManagerViewModel.deleteList` hard-deleted immediately with no Undo, relying on FK `ON DELETE CASCADE` for memberships. An Undo window means either deferring the actual purge until the snackbar dismisses (soft-delete) or snapshot-and-reinsert, and reinsert is awkward because the cascade already dropped memberships. Prefer deferred delete: hide the tile optimistically, purge when the Undo window closes. Applied to the Lists Manager archived-section Delete so both surfaces behave identically.
- **The feed's flags clear on data, not on retry.** See Architecture, "Failure as data". A reader who moves the reset into `HomeFeed.retry()` for symmetry reintroduces the Empty flash ADR 0006 forbids.
- **Long-press vs drag.** Home cards do not support drag-reorder (that lives in Lists Manager behind drag handles), so long-press is free to mean "quick actions" here with no gesture conflict. If drag-to-reorder is ever added to Home, this decision has to be revisited.

### Not in scope (technical)

- Caching due-counts. The column is denormalized by the mutator use cases (ADR 0006 Rule 2); Home only reads it.
- Pre-fetching Card view state. Card view owns its own state.

### Open technical questions

- ~~"Surprise me" determinism: session-key from process start time, or from app-state version?~~ Resolved 2026-05-04: pure-deterministic, no session seed (ADR 0007); moot since the feature's removal on 2026-06-12.
