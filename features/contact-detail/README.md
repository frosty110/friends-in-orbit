# contact-detail

**Status:** in-progress
**Last reviewed:** 2026-10-05
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/contact/` (`ContactDetailScreen`, `ContactDetailViewModel`, `ContactDetailUiState`, `sections/` — `LogConnectionSheet`, `NotesSection`, `PauseSheet`, `RuleOverrideSection`, `UnpauseBanner`); re-link merge in `android/app/src/main/java/app/orbit/domain/usecase/RelinkContactUseCase.kt`
- Tests: `android/app/src/test/java/app/orbit/ui/screens/contact/` (`ContactDetailViewModelTest`, `ContactOverflowMenuTest`, `RuleOverrideSectionTest`), `android/app/src/test/java/app/orbit/domain/usecase/RelinkContactUseCaseTest.kt`; instrumented: `android/app/src/androidTest/java/app/orbit/ui/screens/contact/` (`OrphanBannerTest`, `UnpauseBannerTest`)

---

## Product

### Why it exists

When the user needs more than the card-view summary — full history, notes, per-contact overrides, pause — this is where it lives. A complete profile of one person's place in the user's orbit. Call history lives here too, so a separate "stats" screen is unnecessary in v1.

### User story

As a user, I open a contact to see their full call history, the lists they're on, patterns over time, and my running notes. I can pause them, tune their rules, or edit them in my phone contacts.

### Behavior

**Hero.** Photo, name, phone number, Call action, and "Log a connection" — a sheet that records a manual call event (`CallSource.MANUAL`) for calls Orbit couldn't observe.

**Stats card.** Last call, total calls, average length, longest gap, and "Usually": the part of the day this person is usually called (Mornings, Afternoons, Evenings or Late nights), the same reading Card view shows. "Usually" and "Average length" need three connected calls and "Longest gap" two; below that each says "Not enough calls yet" (2026-10-05; it was a bare dash, which read as broken, vision CONTACT-3). The label is "Average length", not "Avg length". All neutral framing, no streaks, no achievements. Longest gap surfaced without shame framing.

**Lists chip row.** Every list this contact is on, or "Not on any list yet". Under the privacy curtain each chip reads "List", like every list-name surface (it read "Contact"). Not yet clickable (see open questions).

**Call history.** Chronological, with date, duration, direction (outgoing/incoming), and list context ("called from late night list, 14 min"). The overflow's "View all calls" opens Call history narrowed to this person (LOG-04 in [`call-history`](../call-history/README.md)).

**Notes.** Running journal, each note timestamped. Add, edit, delete. Edit (long-press) and delete (swipe) also sit in each note's visible "More" menu, so neither is gesture-only (2026-10-05, rubric D5). The timestamp is a button that switches between "11 days ago" and the date. Under the privacy curtain a note's body reads "Note hidden". Retroactive back-dated notes can be attached from a call-history row. Post-call prompt ("want to add a note?") surfaces as a dismissible banner on next app open after a call, never blocking.

**Pause.** Duration picker: 1 week, 1 month, or until you unpause. Copy: "pause for a week," not "hide for 7 days." The snackbar reads "Paused {name} for 1 week", "... for 1 month" or "... indefinitely", with Undo. Unpause prompt surfaces when the duration ends.

**Paused status and Unpause.** While a pause is in force (timed or indefinite), a status line under the phone number reads "Paused until {d MMM}" (for example "Paused until 12 Oct") or "Paused until you unpause", and the overflow shows "Unpause" in place of "Pause". Unpause restores surfacing now; snackbar "Unpaused {name}" with Undo back to the exact prior pause. Before this, an indefinite pause could not be undone once its snackbar was gone.

**Per-contact rule override.** Hidden until the contact is on 2+ lists (per PRD) to avoid config clutter for single-list contacts. It reads "Follows the keep in touch rhythm from {list}." with a Secondary "Set a schedule for this person" (until 2026-10-05 a Primary "Override": a second accent element and engineering vocabulary). The editor opens read-only and persists only when the user actually changes a value; the interval slider moves a day at a time, announces "Every 14 days" to TalkBack (it read "10 percent"), sits in ink rather than the accent, and commits BOTH cooldown bounds via `RuleParams.KeepInTouch.withIntervalHours` so "every 30 days" honestly becomes "every 14".

**Privacy curtain.** With the curtain on, the name reads "Contact", the photo falls back to initials of that word, list names read "List" (or "its list" in the schedule sentence), and note bodies read "Note hidden". Until 2026-10-05 the hero photo, the note bodies and the list names showed through.

**States.** Loading shows a quiet placeholder shaped like the hero. A contact that no longer exists says "This person isn't in Orbit anymore" with "Go back". A failed read says "Couldn't load this person" with "Try again" (CONTACT-08).

**Edit.** Deep-links to the system Contacts app — Orbit does not own name/number.

**Orphaned state (CONTACT-06).** If the phone contact is deleted, the Orbit data remains; a "disconnected" badge surfaces and the orphan banner offers Re-link and Archive.

**Re-link (CONTACT-07).** Re-link opens the contact picker in Relink mode ([picker page view](../page-views/picker-contacts.md)), which offers only other contacts mirrored from the phone that are not orphaned, ignored or archived. Committing merges the picked contact's Orbit row into the orphan (`RelinkContactUseCase`): the orphan keeps its id, so this screen stays open on it, now linked, and it takes the phone contact's number, name, photo, starred flag and device id; that contact's calls, notes, numbers and list memberships move onto it and the emptied row is deleted. User-owned state (pause, rule override, ignore, archive) stays the orphan's, the other row's pause or override fills in only where the orphan has none, "first seen" keeps the earlier date, and a list both were on keeps one membership with the earlier added date and the later next-due date. The snackbar reads "Re-linked to {phone contact name}" with Undo, which splits the two contacts back exactly. If the merge is refused (a contacts sync restored the orphan meanwhile, or the pick is no longer a live phone contact) it reads "Couldn't save that" and nothing changes.

### Requirements

Defined 2026-10-05 from what the code already cites for them (they were cited in source with no spec behind them; see rules.md "Known documentation debt"):

- **CONTACT-01: One person's whole picture.** Contact detail shows the person's photo (initials as the fallback), name and number, the lists they are on, their stats and their call history, from one state contract (`ContactDetailUiState`).
- **CONTACT-02: Neutral stats.** Last call, total calls, average length, longest gap and "Usually", in factual labels: no "overdue", no "haven't called", no time-since framing beyond the facts. A stat without enough history says "Not enough calls yet".
- **CONTACT-03: A schedule for one person.** For someone on two or more lists, a per-contact override of the rhythm (`ContactEntity.ruleOverrideJson`), edited with the same kind picker as List settings and an interval slider that moves both cooldown bounds.
- **CONTACT-04: Pause.** The overflow's Pause opens a duration sheet (1 week, 1 month, until you unpause); the pause commits with an Undo snackbar, and a paused person shows "Paused until ..." with Unpause in the overflow.
- **CONTACT-05: Unpause prompt.** When a timed pause has lapsed (never an indefinite one), a banner at the top offers to unpause.
- **CONTACT-08: A failed read is an error, not a crash.** If a source stream fails, the screen says "Couldn't load this person" with "Try again", which re-subscribes every source. Before 2026-10-05 the exception escaped `viewModelScope` and took the app down (rubric plan 3.5, gate G5).
- **NOTE-01: Notes journal.** A running journal of timestamped notes on the person: add from the inline field, edit, delete with Undo, all written through encrypted Room. Edit and delete are reachable from a visible menu as well as by gesture.
- **NOTE-02: Jump straight to the note field.** Entry points that ask for a note (the post-call banner, a call-history row) open Contact detail with `focusNote=1`, and the note field takes focus once the screen settles.

### Acceptance criteria

- [x] A failing source shows the error state and Retry recovers (CONTACT-08; `ContactDetailViewModelTest`).
- [ ] Stats update live when call-detection records a new call.
- [ ] Pause copy feels calm, not punitive.
- [ ] Note input supports multi-line, 16sp minimum.
- [ ] Longest-gap framing neutral — never prefixed with "you haven't..."
- [ ] Deleting a phone contact does not crash this screen; disconnected badge renders.
- [ ] Dark mode + 200% font scale + TalkBack pass.
- [ ] All user-private data (notes, full call history) written through encrypted Room per ADR 0002.

### Not in scope

- Editing the contact's name or phone number in-app. System Contacts is the source of truth.
- Aggregate stats dashboards. Per PRD §v1 Scope "Out," a stats dashboard is v1.1+.
- Per-contact notification toggles. Notifications toggle at the list level only.
- Importing/exporting a single contact's data. The global export flow (see `features/privacy-and-lock/README.md`) covers everything.

### Open product questions

- ~~"Archive contact" action distinct from "remove from list"?~~ Resolved: yes — Ignore and Archive are distinct contact-level actions on this screen; the orphan banner offers Re-link + Archive.
- Notes retention when phone contact is deleted — PRD says "keep app data"; where/how is this surfaced to the user? (Banner? Archive view?)
- Post-call note prompt — dismissal: per-contact, per-call, or global? Leaning per-call.
- The lists chips should open their list, per this spec; they are read-only today. Doing it needs a navigation callback the screen doesn't have yet.

---

## Technical

### Architecture

`ContactDetailViewModel` composes a `ContactDetailUiState` by `combine`-ing multiple Flows: `ContactEntity`, `Flow<List<CallEventEntity>>`, `Flow<List<ListMembershipEntity>>`, `Flow<List<NoteEntity>>`. The per-contact rule override lives as a JSON column on the contact (`ContactEntity.ruleOverrideJson`) — there is no separate override entity.

All encrypted-Room reads per ADR 0002. Stats (longest gap, avg length, time-of-day patterns) computed in Kotlin from the call-history Flow — pure and testable, no SQL view. "Usually" comes from Card view's call-pattern overlay (`withCallPatterns`), bucketed by an injected `ZoneId`.

### Data model

Reads: `ContactEntity`, `CallEventEntity[]`, `ListMembershipEntity[]`, `NoteEntity[]`.
Writes: `NoteEntity` (create/update/delete, incl. retroactive back-dated notes), `CallEventEntity` (manual log, `source = MANUAL`), `ContactEntity.pausedUntil`, `ContactEntity.ruleOverrideJson` (set/clear), ignore/archive state, and the re-link merge (`RelinkContactUseCase`: one transaction moves the picked row's call events, notes, phones and memberships onto the orphan, then deletes that row).

### Permissions / integrations

- `READ_CONTACTS` — photo resolution when not cached.
- System Contacts deep-link: `Intent.ACTION_VIEW` on `ContactsContract.Contacts.CONTENT_URI`.
- Call action inherits dial mechanism from card-view (see `features/card-view/README.md`).

### Known gotchas

- Notes are user-private — must write through encrypted Room (ADR 0002), not DataStore.
- Orphaned contacts must render without crashing — `ContactEntity` persists even if `androidContactId` lookup returns null. Photo falls back to generated initial.

### Not in scope (technical)

- SQL views for stats. Kotlin computation is fast enough and keeps the engine testable on JVM.
- Caching stats. Recompute per collect; call-history Flow already debounces upstream.

### Open technical questions

- Longest-gap computation: over all history, or windowed to the same time window the user is viewing? Leaning all-history — one number, unambiguous.
