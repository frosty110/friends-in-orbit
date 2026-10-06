# card-view

**Status:** in-progress
**Last reviewed:** 2026-10-06 (card-view audit)
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/card/` (`CardViewScreen`, `CardViewViewModel`, `CardViewUiState`, `CardSwipeFrame`), `android/app/src/main/java/app/orbit/data/feed/CardFeed.kt`
- Tests (JVM, `android/app/src/test/java/app/orbit/ui/screens/card/`): `CardViewViewModelTest.kt` (the state contract: Loading, Ready, the empty decks, Error and Try again, the pause hint, a malformed id, the rhythm sentence), `CardViewViewModelInteractionTest.kt` (Later and Sooner with their undo, the failure snackbar, the "Called {name}" acknowledgement and what cancels it), `CardListMenuTest.kt` (the list menu's order; Add people absent on a smart list and while the type is unknown), `CardViewScreenTest.kt` (Robolectric: the face opens details and never dials; Call on screen in landscape)
- Instrumented: `android/app/src/androidTest/java/app/orbit/ui/screens/card/CardFaceCurtainTest.kt`

---

## Product

### Why it exists

Card view is the core loop. One contact at a time, one yes-or-no decision. The whole mission — reducing cognitive load — lives or dies here. When this screen works, the rest of the app is scaffolding; when it fails, no other feature compensates.

### User story

As a user, I see one person at a time with just enough context to decide whether to call them right now. I press Call, swipe right to surface them sooner, or swipe left to defer.

### Behavior

**Layout.** Photo + contact name dominate above the fold. Then the reason to call now: how long it has been and the pair's usual rhythm, and the last note you wrote. Stats present but secondary. List context shown as a small badge. Later and Sooner buttons, labelled, either side of Call in the thumb zone for users who don't want to swipe.

**Privacy curtain** (2026-10-05). With the curtain on (app backgrounded), the card face shows "Contact" in place of the person's name and derives the avatar initial from it, like the app bar. Before, the face still showed the real name and initials. Convention: `features/privacy-and-lock/README.md`, "Quick-hide on focus loss".

**Smart lists** (2026-10-05). A smart list's rule matches are stored as members, due when they start matching (`SmartListMembershipSync`, see `features/orbit-lists/README.md`), so its card surfaces people like a static list's. Before, it always showed the no-members empty state.

**Context data.** Last call time, call count, average call length, earliest/latest call time-of-day, days since last contact. Neutral framing — no streaks, no shame.

**Interactions.**
- **Call button** → dials (see Technical → Permissions for dial mechanism). It is the only thing on the screen that dials.
- **Tap the card face** → opens the person's details.
- **Swipe right, or Sooner** → surface sooner (shorter cooldown on this list).
- **Swipe left, or Later** → defer (longer cooldown on this list).
- **Undo** → each Later or Sooner shows a snackbar naming the person and when they come back, with Undo that restores the prior membership schedule (`nextDueAt` + `skipCount`).
- **After a call** → the deck moves on by itself once the call log shows the call (CORE-04), and the screen says "Called {name}" with "Add a note", which opens the person with the note field focused (CARD-03, NOTE-02 in `features/contact-detail/README.md`).
- **The list menu** ("More actions for {list}") → "Browse people", "Add people", "List settings", in that order. "Add people" is not offered on a smart list, in the menu or on its empty deck: a smart list's members are its rule's matches (`features/orbit-lists/README.md`), and anyone added by hand was removed by the next sync with no word. The smart list's empty deck reads "No one matches this rule right now." with "List settings" instead. Nor is it offered while the list's type is not yet known, on the Loading and Error decks (`CardViewUiState.listType` is null there): until 2026-10-06 the menu asked "is it smart?", so a failed smart list offered Add people.

**Requirements** (2026-10-05, from [`vision/ux-rubric.md`](../../vision/ux-rubric.md)):

- **CARD-01: Only Call dials.** Tapping the card face opens the person's details; the labelled Call button is the only control that places a call. A call reaches another person and cannot be undone, so it is never one stray tap away. (The face used to dial; a call was placed by accident in review.)
- **CARD-02: Later and Sooner are named, and each undo is its own.** The side buttons read "Later" and "Sooner" on screen and to TalkBack, and the card face offers Later, Sooner and Call as accessibility actions. Each action's snackbar names the person and says when they come back ("Sarah will come up again tomorrow."). A newer snackbar replaces an older one at once, and Undo carries a token so only the newest action can be undone. (Snackbars used to queue while the undo slot held only the latest action, so Undo on the first of three quick swipes reverted the third person.)
- **CARD-03: A call is acknowledged once it is real.** Once the call log confirms a call placed from the card, within 15 seconds of returning, the snackbar says "Called {first name}" with "Add a note", which opens their details with the note field focused (NOTE-02). Confirmation is evidence of the call: a connected call for that person logged at or after the dial, or the deck moving past them (the call re-scheduled them behind someone else). It used to be motion alone, and on a one-member list, or when everyone else is further out, the person stays at the head after a real call, so nothing was ever said. An unanswered dial logs an attempt, not a connection, and is never acknowledged. Nothing is said if the call is not confirmed. Any swipe cancels the wait, so a deck that moved for another reason is never credited as a call.
- **CARD-04: Why now, in human terms.** The face shows how long it has been, the pair's usual rhythm once there are four calls ("You usually talk about every 2 weeks.", the median gap, stated as a fact, never a deadline), and the most recent note from the last 30 days, quoted, ahead of the statistics. The note is hidden under the privacy curtain.
- **CARD-05: "All quiet for now."** When nobody on the list is due, the empty state says so calmly and names who comes up next and when. It never says "caught up" or "done": the queue is continuous by design (HOME-6 in `vision/00-home/00-home.md`, `SurfaceResult.kt`), so nothing should read as a cleared backlog.
- **CARD-06: The card fits the screen it is on.** On a short, wide window (landscape on a phone: wider than tall and under 480dp high) the card sits on the left and its actions (Call, Later, Sooner, Open details) in a column on the right. Stacked, the face got about 120dp of height. Above 130% text the Call button takes its own full-width row with Later and Sooner beneath it (between them it was too narrow for one word). In any orientation the face scrolls when its content outgrows it (large text, a long note), so nothing is ever clipped, and the list chip sits in the face's flow, so a long list name wraps above the avatar instead of over it.
- **CARD-07: A failed read is an error with Try again, not a crash.** When the deck cannot be read (a database error in any of the feed's sources, or a list id that does not parse, as from a bad deep link), the screen says "Something's off here." / "Nothing is lost. Try again in a moment." with "Try again" as its one accent and "Go home" beneath, and the list's name stays in the app bar when it is known. Try again rebuilds the subscription (`CardFeed` evicts the failed entry, the BROWSE-06 precedent). The failure is caught inside `CardFeed` before its process-scoped `stateIn`: an exception escaping an Eagerly started flow on the application scope has no handler and crashed the app, and a `StateFlow` never throws to its collectors, so the ViewModel's own catch could not see it. Before this, the Error state existed in the contract but was unreachable, and a malformed id rendered "All quiet for now." over a list that does not exist.

**Physics.** The card moves with the finger from touch slop and tilts as dragged, up to about 8 degrees; the "Later" and "Sooner" hint chips fade in at the destination edge from the first few dp of travel. Commit and snap-back settle on one spring (`OrbitMotion.springCardCommit`: low bounce, medium-low stiffness, overshoot held under 5%), driven by `anchoredDraggable`, so a re-grab mid-settle is a new drag, not a fight with a tween. Haptic buzz on swipe commit, not at threshold entry.

**Cross-list propagation.** Calling from this screen updates last-call state on every list this contact belongs to — immediately, via Flow.

**Empty states.** Teaching empty states as built, each the shared `OrbitScreenMessage` with the next step as its one action and "Go home" beneath: `EmptyNoMembers` (the list has no members: "Add people", or "List settings" on a smart list) and `EmptyNothingEligible` ("All quiet for now.", CARD-05, naming who comes back soonest and when; a paused person for when the pause lifts, and nobody for a pause until you unpause). `Error` is CARD-07. The app bar carries the list's name in every state that knows it ("List" under the curtain; blank while loading and when the list itself could not be read), so TalkBack announces the pane and "this list" is named on screen. The loop stays continuous: there is no terminal state, only a kind word while nobody is due. Voice per `features/_foundations/voice.md`.

### Acceptance criteria

Each ticked box says how it was verified: *render* (the preview gallery), *test* (a JVM test that fails without it), or *reasoned* (read in the code, not run). Unticked boxes need a device.

- [x] The card moves with the finger from touch slop; the Later and Sooner hint chips fade in from the first few dp of travel (reasoned: `CardSwipeFrame` and `GhostHints` at `absFrac > 0.01f`; it said "~20dp", a threshold the implementation never had).
- [ ] Commit threshold forgiving enough for one-handed use on a 6.5" phone (not verified: needs a device).
- [x] Tilt max about 8 degrees; commit and snap-back on `springCardCommit`; no bouncy overshoot > 5% (reasoned: `Motion.kt`, `CardSwipeFrame`).
- [x] Haptic fires at commit, not at threshold entry (reasoned: `CardSwipeFrame` performs it on `snapshotFlow { settledValue }`).
- [ ] Calling updates cross-list state on every other list the contact appears on (not verified: needs a device with a call log).
- [ ] Dark mode + 200% font scale + TalkBack pass (render for dark and 200%, `CardViewContentLongNamesPreview` and `CardViewContentErrorPreview`; TalkBack itself needs a device).
- [ ] Drag gesture is not captured by any parent scroll container (not verified: needs a device).
- [x] Tap target for the Call, Later and Sooner buttons >= 48x48dp (render: the gallery's accessibility audit; `OrbitButton` 48dp default, Call at 56dp, `CircleSideButton` 56dp).
- [x] A tap on the card face never dials (CARD-01; test: `CardViewScreenTest`).
- [x] Three quick swipes, then Undo on the first snackbar, changes nothing (CARD-02; test: `CardViewViewModelInteractionTest`).
- [x] In landscape on a phone, the whole card face and the Call button are visible without scrolling (CARD-06; test: `CardViewScreenTest` at `w740dp-h360dp-land`).
- [x] A real call is acknowledged even when the person stays at the head; an attempt, a swipe or 15 seconds of silence is not (CARD-03; test: `CardViewViewModelInteractionTest`).
- [x] A failed read shows Try again and recovers; a malformed id is the same error (CARD-07; test: `CardViewViewModelTest`).
- [x] A seeded list never flashes an empty deck before Ready (CARD-05; test: `CardViewViewModelTest`).

### Not in scope

- Multi-card stacks (showing 3 upcoming contacts). PRD specifies one at a time — reducing choice is the point.
- Per-contact rule tuning. That lives in `features/contact-detail/README.md`.
- Notes entry during swipe. Writing the note itself happens on contact detail (`features/contact-detail/README.md`); Card view only offers the way there (CARD-03).
- Incoming call handling. See `features/notifications/README.md`.

### Open product questions

- ~~Does swipe-right apply a fixed cooldown reduction, or step through cooldown buckets?~~ Resolved: Sooner pulls `nextDueAt` forward by half the list rule's skip penalty, at least one hour (`SurfaceSoonerUseCase`; 12 hours on the default 24-hour penalty, pinned by `CardViewViewModelInteractionTest`). Rule-engine semantics stay in `features/rule-engine/README.md`.
- Rapid repeat swipes: escalate (surface more urgently) vs deprioritize (surface less often). PRD leaves configurable — default needed.
- ~~CALL_PHONE permission (direct `ACTION_CALL`) vs `ACTION_DIAL` (opens dialer)?~~ Resolved: `ACTION_DIAL` via `ui/util/Dialer.kt`, with no `CALL_PHONE` permission. There is no post-dial prompt: the call log is the source of truth and the deck moves on by itself (CORE-04), with "Called {name}" once it does (CARD-03).

---

## Technical

### Architecture

- `CardSwipeFrame` owns the drag state (replaced the generic `SwipeableCard` component, deleted 2026-06-09); drag state stays local so only the card recomposes during drag.
- `CardViewViewModel` exposes `StateFlow<CardViewUiState>` (`Loading` / `Ready` / `EmptyNoMembers` / `EmptyNothingEligible` / `Error`), a thin subscriber to the singleton `CardFeed` through `retryCount.flatMapLatest`, so Try again re-subscribes (CARD-07). `CardFeed`'s placeholder snapshot is `loaded = false` and maps to `Loading`, so a cold open never flashes an empty deck; a failed read arrives as a snapshot with `error` set, caught inside the feed before its `stateIn`.
- The surfaced contact is hydrated via `withCallStats` (last called / avg length / total calls) + `withCallPatterns` (hour histogram for the heat strip) from the snapshot's recent calls.
- On swipe commit: VM captures the prior membership schedule, runs the mutation (`SkipContactUseCase` / `SurfaceSoonerUseCase`), then offers Undo that restores via `UndoStack` + `ListRepository.restoreMembershipSchedule`.
- Post-dial follow-through: `onCall` remembers the dialed contact and the instant; on resume the VM triggers an immediate call-log sync and waits up to 15 seconds for evidence of the call before acknowledging it (CARD-03): a `Ready` for the same person whose `lastCallAt` (connections only, never an attempt) is at or after the dial, or the deck moving past them. There is no "did you talk?" confirmation: the call log is the source of truth, and off-log connections use "Log a connection" on the contact screen.
- One-off messages are a `CardMessage` (`Undoable` with a token, `Called`, `Failed`); the screen collects them with `collectLatest`, so the newest replaces the one on screen.

### Data model

- Reads: `ContactEntity`, `CallEventEntity` (stats computed in Kotlin — no `CallStatEntity`), `ListMembershipEntity`, `NoteEntity`.
- Writes: swipe outcomes update `ListMembershipEntity.nextDueAt` + `skipCount` (no dedicated `CooldownEntity`); marking a call writes `CallEventEntity(source = MANUAL)`.

### Permissions / integrations

- **Haptics:** `VIBRATOR_SERVICE` — no manifest permission needed.
- **Dial:** default `ACTION_DIAL` (no permission required). `CALL_PHONE` → `ACTION_CALL` upgrade is an open product question above.

### Known gotchas

- Card view must not be nested inside any `LazyColumn` or scrollable parent — gesture will fight with parent scroll.
- Recomposition during drag is expensive; keep drag state isolated to the card composable, not hoisted to `HomeState` or similar parent.
- Drag and settle are one `AnchoredDraggableState` (`CardSwipeFrame`), so a re-grab during the settle is a new drag; `animate*AsState` could not be cancelled that way and was replaced.

### Not in scope (technical)

- Persisting drag position across process death. Cards restart clean — product-acceptable.
- Prefetching next contact's photo. Coil's cache handles this.

### Open technical questions

- ~~Use Compose `Animatable` or `animate*AsState` for snap-back?~~ Resolved: `anchoredDraggable` with `OrbitMotion.springCardCommit` (`CardSwipeFrame`), which owns both the drag and the settle and cancels cleanly on a re-grab.
- ~~Where does the cooldown state live?~~ Resolved: columns on `ListMembershipEntity` (`nextDueAt`, `skipCount`).
