# card-view

**Status:** in-progress
**Last reviewed:** 2026-10-05 (core-loop pass)
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/card/` (`CardViewScreen`, `CardViewViewModel`, `CardViewUiState`, `CardSwipeFrame`), `android/app/src/main/java/app/orbit/data/feed/CardFeed.kt`
- Tests: `android/app/src/test/java/app/orbit/ui/screens/card/CardViewViewModelTest.kt`, instrumented: `android/app/src/androidTest/java/app/orbit/ui/screens/card/CardFaceCurtainTest.kt`

---

## Product

### Why it exists

Card view is the core loop. One contact at a time, one yes-or-no decision. The whole mission — reducing cognitive load — lives or dies here. When this screen works, the rest of the app is scaffolding; when it fails, no other feature compensates.

### User story

As a user, I see one person at a time with just enough context to decide whether to call them right now. I tap to call, swipe right to surface them sooner, or swipe left to defer.

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
- **After a call** → the deck moves on by itself once the call log shows the call (CORE-04), and the screen says "Called {name}" with "Add a note".

**Requirements** (2026-10-05, from [`vision/ux-rubric.md`](../../vision/ux-rubric.md)):

- **CARD-01: Only Call dials.** Tapping the card face opens the person's details; the labelled Call button is the only control that places a call. A call reaches another person and cannot be undone, so it is never one stray tap away. (The face used to dial; a call was placed by accident in review.)
- **CARD-02: Later and Sooner are named, and each undo is its own.** The side buttons read "Later" and "Sooner" on screen and to TalkBack, and the card face offers Later, Sooner and Call as accessibility actions. Each action's snackbar names the person and says when they come back ("Sarah will come up again tomorrow."). A newer snackbar replaces an older one at once, and Undo carries a token so only the newest action can be undone. (Snackbars used to queue while the undo slot held only the latest action, so Undo on the first of three quick swipes reverted the third person.)
- **CARD-03: A call is acknowledged once it is real.** When the call log confirms a call placed from the card (the deck moves past the person within 15 seconds of returning), the snackbar says "Called {first name}" with "Add a note", which opens their details. Nothing is said if the call is not confirmed. Any swipe cancels the wait, so a deck that moved for another reason is never credited as a call.
- **CARD-04: Why now, in human terms.** The face shows how long it has been, the pair's usual rhythm once there are four calls ("You usually talk about every 2 weeks.", the median gap, stated as a fact, never a deadline), and the most recent note from the last 30 days, quoted, ahead of the statistics. The note is hidden under the privacy curtain.
- **CARD-05: "All quiet for now."** When nobody on the list is due, the empty state says so calmly and names who comes up next and when. It never says "caught up" or "done": the queue is continuous by design (HOME-6 in `vision/00-home/00-home.md`, `SurfaceResult.kt`), so nothing should read as a cleared backlog.
- **CARD-06: The card fits the screen it is on.** On a short, wide window (landscape on a phone: wider than tall and under 480dp high) the card sits on the left and its actions (Call, Later, Sooner, View details) in a column on the right. Stacked, the face got about 120dp of height. Above 130% text the Call button takes its own full-width row with Later and Sooner beneath it (between them it was too narrow for one word). In any orientation the face scrolls when its content outgrows it (large text, a long note), so nothing is ever clipped.

**Physics.** Card tilts as dragged — up to ~8°. "Heat strip" grows at the destination edge as drag progresses. Snap-back animation 250ms ease-out, no overshoot > 5%, no bounce. Haptic buzz on swipe commit.

**Cross-list propagation.** Calling from this screen updates last-call state on every list this contact belongs to — immediately, via Flow.

**Empty states.** Teaching empty states as built: `EmptyNoMembers` (the list has no members) and `EmptyNothingEligible` ("All quiet for now.", CARD-05, with when the soonest member comes due). The loop stays continuous: there is no terminal state, only a kind word while nobody is due. Voice per `features/_foundations/voice.md`.

### Acceptance criteria

- [ ] Visual feedback on drag starts at ~20dp displacement.
- [ ] Commit threshold forgiving enough for one-handed use on a 6.5" phone.
- [ ] Tilt max ~8°; snap-back 250ms ease-out; no bouncy overshoot > 5%.
- [ ] Haptic fires at commit, not at threshold entry.
- [ ] Calling updates cross-list state on every other list the contact appears on.
- [ ] Dark mode + 200% font scale + TalkBack pass.
- [ ] Drag gesture is not captured by any parent scroll container.
- [ ] Tap target for the Call, Later and Sooner buttons >= 48×48dp.
- [ ] A tap on the card face never dials (CARD-01).
- [ ] Three quick swipes, then Undo on the first snackbar, changes nothing (CARD-02; `CardViewViewModelInteractionTest`).
- [ ] In landscape on a phone, the whole card face and the Call button are visible without scrolling (CARD-06; gallery at `w740dp-h360dp-land`).

### Not in scope

- Multi-card stacks (showing 3 upcoming contacts). PRD specifies one at a time — reducing choice is the point.
- Per-contact rule tuning. That lives in `features/contact-detail/README.md`.
- Notes entry during swipe. Writing the note itself happens on contact detail (`features/contact-detail/README.md`); Card view only offers the way there (CARD-03).
- Incoming call handling. See `features/notifications/README.md`.

### Open product questions

- Does swipe-right apply a fixed cooldown reduction, or step through cooldown buckets? Decision owner: rule-engine semantics (`features/rule-engine/README.md`).
- Rapid repeat swipes: escalate (surface more urgently) vs deprioritize (surface less often). PRD leaves configurable — default needed.
- ~~CALL_PHONE permission (direct `ACTION_CALL`) vs `ACTION_DIAL` (opens dialer)?~~ Resolved: `ACTION_DIAL` via `ui/util/Dialer.kt` — no `CALL_PHONE` permission. The post-dial "Mark it" prompt compensates for the dialer hand-off.

---

## Technical

### Architecture

- `CardSwipeFrame` owns the drag state (replaced the generic `SwipeableCard` component, deleted 2026-06-09); drag state stays local so only the card recomposes during drag.
- `CardViewViewModel` exposes `StateFlow<CardViewUiState>` (`Loading` / `Ready` / `EmptyNoMembers` / `EmptyNothingEligible` / `Error`), a thin subscriber to the singleton `CardFeed`.
- The surfaced contact is hydrated via `withCallStats` (last called / avg length / total calls) + `withCallPatterns` (hour histogram for the heat strip) from the snapshot's recent calls.
- On swipe commit: VM captures the prior membership schedule, runs the mutation (`SkipContactUseCase` / `SurfaceSoonerUseCase`), then offers Undo that restores via `UndoStack` + `ListRepository.restoreMembershipSchedule`.
- Post-dial follow-through: `onCall` remembers the dialed contact; on resume the VM triggers an immediate call-log sync and waits up to 15 seconds for the deck to move past that person before acknowledging the call (CARD-03). There is no "did you talk?" confirmation: the call log is the source of truth, and off-log connections use "Log a connection" on the contact screen.
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
- `animate*AsState` suffices for snap-back in the common path; switch to `Animatable` only if gesture cancellation during animation becomes visible.

### Not in scope (technical)

- Persisting drag position across process death. Cards restart clean — product-acceptable.
- Prefetching next contact's photo. Coil's cache handles this.

### Open technical questions

- Use Compose `Animatable` or `animate*AsState` for snap-back? Latter simpler; former gives cancellation control if the user re-grabs mid-animation. Start simpler.
- ~~Where does the cooldown state live?~~ Resolved: columns on `ListMembershipEntity` (`nextDueAt`, `skipCount`).
