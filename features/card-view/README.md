# card-view

**Status:** in-progress
**Last reviewed:** 2026-06-09
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/card/` (`CardViewScreen`, `CardViewViewModel`, `CardViewUiState`, `CardSwipeFrame`), `android/app/src/main/java/app/orbit/data/feed/CardFeed.kt`
- Tests: `android/app/src/test/java/app/orbit/ui/screens/card/CardViewViewModelTest.kt`

---

## Product

### Why it exists

Card view is the core loop. One contact at a time, one yes-or-no decision. The whole mission — reducing cognitive load — lives or dies here. When this screen works, the rest of the app is scaffolding; when it fails, no other feature compensates.

### User story

As a user, I see one person at a time with just enough context to decide whether to call them right now. I tap to call, swipe right to surface them sooner, or swipe left to defer.

### Behavior

**Layout.** Photo + contact name dominate above the fold. Stats present but secondary. List context shown as a small badge. Subtle skip button in the thumb zone for users who don't want to swipe.

**Context data.** Last call time, call count, average call length, earliest/latest call time-of-day, days since last contact. Neutral framing — no streaks, no shame.

**Interactions.**
- **Tap** → initiate call (see Technical → Permissions for dial mechanism). Returning from the dialer surfaces a one-tap "Talked to {name}? Mark it" prompt that records a manual call (`CallSource.MANUAL`, zero duration — engines treat it as a full-cooldown call).
- **Swipe right** → surface sooner (shorter cooldown on this list).
- **Swipe left** → defer (longer cooldown on this list).
- **Skip button** → same as swipe left, for users who don't want to swipe.
- **Undo** → each swipe emits a snackbar with Undo that restores the prior membership schedule (`nextDueAt` + `skipCount`).

**Physics.** Card tilts as dragged — up to ~8°. "Heat strip" grows at the destination edge as drag progresses. Snap-back animation 250ms ease-out, no overshoot > 5%, no bounce. Haptic buzz on swipe commit.

**Cross-list propagation.** Calling from this screen updates last-call state on every list this contact belongs to — immediately, via Flow.

**Empty states.** Teaching empty states as built: `EmptyNoMembers` (the list has no members) and `EmptyNothingEligible` (nobody due now; shows when the soonest member comes due). The terminal "all caught up" state was removed 2026-05-08 — the loop is continuous. Voice per `features/_foundations/voice.md`.

### Acceptance criteria

- [ ] Visual feedback on drag starts at ~20dp displacement.
- [ ] Commit threshold forgiving enough for one-handed use on a 6.5" phone.
- [ ] Tilt max ~8°; snap-back 250ms ease-out; no bouncy overshoot > 5%.
- [ ] Haptic fires at commit, not at threshold entry.
- [ ] Calling updates cross-list state on every other list the contact appears on.
- [ ] Dark mode + 200% font scale + TalkBack pass.
- [ ] Drag gesture is not captured by any parent scroll container.
- [ ] Tap target for call button and skip button >= 48×48dp.

### Not in scope

- Multi-card stacks (showing 3 upcoming contacts). PRD specifies one at a time — reducing choice is the point.
- Per-contact rule tuning. That lives in `features/contact-detail/README.md`.
- Notes entry during swipe. Post-call note prompt covered by `features/contact-detail/README.md`.
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
- Post-dial follow-through: `onCall` remembers the dialed contact; on resume a "Talked to {name}? Mark it" affordance records a manual call via `MarkCalledUseCase`.

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
