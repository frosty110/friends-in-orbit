# contact-detail

**Status:** in-progress
**Last reviewed:** 2026-10-06
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/contact/` (`ContactDetailScreen`, `ContactDetailViewModel`, `ContactDetailUiState`, `sections/`: `LogConnectionSheet`, `NotesSection`, `RuleOverrideSection`, `UnpauseBanner`; the pause sheet is the shared `ui/components/PauseDurationSheet`); re-link merge in `android/app/src/main/java/app/orbit/domain/usecase/RelinkContactUseCase.kt`
- Tests, all on the JVM: `android/app/src/test/java/app/orbit/ui/screens/contact/` (`ContactDetailViewModelTest`, `ContactOverflowMenuTest`, `ContactDetailCurtainTest`, `ContactDetailScreenTest`, `OrphanBannerTest`, `UnpauseBannerTest`, `sections/NotesMenuTest`, `sections/RuleOverrideSectionTest`), `android/app/src/test/java/app/orbit/domain/usecase/RelinkContactUseCaseTest.kt`. The two banner tests moved from `androidTest` on 2026-10-06, so they gate every push instead of waiting for an emulator.

---

## Product

### Why it exists

When the user needs more than the card-view summary — full history, notes, per-contact overrides, pause — this is where it lives. A complete profile of one person's place in the user's orbit. Call history lives here too, so a separate "stats" screen is unnecessary in v1.

### User story

As a user, I open a contact to see their full call history, the lists they're on, patterns over time, and my running notes. I can pause them, tune their rules, or edit them in my phone contacts.

### Behavior

**Hero.** Photo, name, phone number, Call action, and "Log a connection": a sheet that records a connection Orbit couldn't observe (`CallSource.MANUAL`) or, with "Couldn't reach them", an attempt (`CallSource.ATTEMPT`: a voicemail, no answer; CONTACT-09). Under the number, a status line where one applies: "Paused until 12 Oct", "Paused until you unpause", "Ignored", "Archived". The name is the page's heading and its pane title, so TalkBack announces whose page this is (the app bar carries no title here).

**Stats card.** Last call, total calls, average length, longest gap, and "Usually": the part of the day this person is usually called (Mornings, Afternoons, Evenings or Late nights), the same reading Card view shows. Its info tip reads "Based on when you usually answer or call this person." (it said "this contact" until 2026-10-05, as Card view's did). "Usually" and "Average length" need three measured calls (ones with a length; a connection logged by hand has none, so until 2026-10-06 one carrier call plus two logged connections showed that single call's length as the average) and "Longest gap" two; below that each says "Not enough calls yet" (2026-10-05; it was a bare dash, which read as broken, vision CONTACT-3). The words are the shared `components_stat_*` strings, the same ones Card view uses. The label is "Average length", not "Avg length". All neutral framing, no streaks, no achievements. Longest gap surfaced without shame framing.

**Without call log access.** When `READ_CALL_LOG` is denied (read on every resume, ARCH-04), a notice sits above the stats, "Orbit can't see your phone calls" with "Open settings", and nothing below it claims what Orbit cannot know: "Never called", "Not enough calls yet" and "No calls yet." are not shown, while "Total calls" and any calls or connections already recorded stay, because hand-logged connections are real data (rules.md Code 3; Browse drops its call meta for the same reason).

**Lists chip row.** Every list this contact is on, or "Not on any list yet". Under the privacy curtain each chip reads "List", like every list-name surface (it read "Contact"). Not yet clickable (see open questions).

**Call history.** "Recent calls": chronological, newest first, up to 50 rows (`RECENT_EVENTS_LIMIT`), each with an icon for its kind, its length (or "Logged" for a connection added by hand, "Attempted" for a reach-out that didn't connect) and the time since ("11 days ago"). The direction is in words for TalkBack as well as in the icon: "You called" or "{first name} called", the words Call history leads its rows with. List context is the chip row above, not a per-row line (`CallEventEntity` records no originating list). The overflow's "View all calls" opens Call history narrowed to this person (LOG-04 in [`call-history`](../call-history/README.md)). Arriving from a Call history row scrolls to that call, tints it, and offers "Add note to this call" under it (LOG-03); the rows are keyed by call-event id and the scroll target is found by that key, so the index cannot drift from the items (until 2026-10-06 it was hand-counted and landed one row too far for a person on one list).

**Notes.** Running journal, each note timestamped. Add, edit, delete. Edit (long-press) and delete (swipe) also sit in each note's visible "More" menu, so neither is gesture-only (2026-10-05, rubric D5). A note's text has no tap action, only the long press, which TalkBack offers as "edit note"; it used to announce a tap that did nothing. The timestamp is a button that switches between "11 days ago" and the date. Under the privacy curtain a note's body reads "Note hidden". Retroactive back-dated notes can be attached from a call-history row. Post-call prompt ("want to add a note?") surfaces as a dismissible banner on next app open after a call, never blocking.

**Pause.** Duration picker: 1 week, 1 month, or until you unpause, in the shared `PauseDurationSheet` (the one "Pause for how long?" sheet Browse uses too). Copy: "pause for a week," not "hide for 7 days." The snackbar reads "Paused {name} for 1 week", "... for 1 month" or "... until you unpause", with Undo. When a timed pause ends, a banner says so (CONTACT-05). Pausing, unpausing and undoing either refresh the widgets (WIDGET-06), so the widget stops offering someone just paused before the hourly sweep.

**Paused status and Unpause.** While a pause is in force (timed or indefinite), a status line under the phone number reads "Paused until {d MMM}" (for example "Paused until 12 Oct") or "Paused until you unpause", and the overflow shows "Unpause" in place of "Pause". Unpause restores surfacing now; snackbar "Unpaused {name}" with Undo back to the exact prior pause. Before this, an indefinite pause could not be undone once its snackbar was gone.

**Per-contact rule override.** Hidden until the contact is on 2+ lists (per PRD) to avoid config clutter for single-list contacts. It reads "Follows the keep in touch rhythm from {list}." with a Secondary "Set a schedule for this person" (until 2026-10-05 a Primary "Override": a second accent element and engineering vocabulary). The editor opens read-only and persists only when the user actually changes a value; the interval slider is the shared `OrbitSlider` (until 2026-10-06 a stock Material slider styled by hand, so it looked different from List settings'): it moves a day at a time, announces "Every 14 days" to TalkBack (it read "10 percent"), sits in ink rather than the accent, and commits BOTH cooldown bounds via `RuleParams.KeepInTouch.withIntervalHours` so "every 30 days" honestly becomes "every 14".

**Privacy curtain.** With the curtain on, the name reads "Contact", the photo falls back to initials of that word, list names read "List" (or "its list" in the schedule sentence), and note bodies read "Note hidden". Until 2026-10-05 the hero photo, the note bodies and the list names showed through.

**States.** Loading shows a quiet placeholder shaped like the hero. A contact that no longer exists says "This person isn't in Orbit anymore" with "Go back". A failed read says "Couldn't load this person" with "Try again" (CONTACT-08).

**Open in Contacts.** The overflow hands the person to the phone's Contacts app (`Intent.ACTION_VIEW` on their `ContactsContract.Contacts` row, `Context.openPhoneContact`) when the phone knows them (`ContactEntity.phoneContactId`); a call-log-only person has no card to open, so the item is absent. Orbit does not own name or number, so there is no in-app edit. Until 2026-10-06 the README promised this and no menu had it.

**Ignored and archived (CONTACT-10).** An ignored person's page says "Ignored" under the number, the overflow offers "Unignore" in place of "Ignore" (`UnignoreContactUseCase`, snackbar "Unignored {name}" with Undo, which re-ignores), and Pause is not offered: a paused ignored person surfaces nowhere either way. An archived person's page says "Archived"; there is no unarchive here (`ArchiveContactUseCase` defers that surface to v1.1). Before this an ignored person's page looked normal and offered Ignore again, the same shape as the missing Unpause fixed as B3.

**Orphaned state (CONTACT-06).** If the phone contact is deleted, the Orbit data remains: the orphan banner offers Re-link and Archive, Call demotes to Secondary (the banner's Re-link holds the screen's one accent), and the name, number, lists, stats, calls and notes stay readable, the notes read-only, because they are Orbit's own data and the banner says "History stays here" (until 2026-10-06 they vanished with the phone contact). Log a connection, Add to lists and the custom schedule wait for the re-link; the overflow keeps "View all calls".

**Re-link (CONTACT-07).** Re-link opens the contact picker in Relink mode ([picker page view](../page-views/picker-contacts.md)), which offers only other contacts mirrored from the phone that are not orphaned, ignored or archived. Committing merges the picked contact's Orbit row into the orphan (`RelinkContactUseCase`): the orphan keeps its id, so this screen stays open on it, now linked, and it takes the phone contact's number, name, photo, starred flag and device id; that contact's calls, notes, numbers and list memberships move onto it and the emptied row is deleted. User-owned state (pause, rule override, ignore, archive) stays the orphan's, the other row's pause or override fills in only where the orphan has none, "first seen" keeps the earlier date, and a list both were on keeps one membership with the earlier added date and the later next-due date. The snackbar reads "Re-linked to {phone contact name}" with Undo, which splits the two contacts back exactly. If the merge is refused (a contacts sync restored the orphan meanwhile, or the pick is no longer a live phone contact) it reads "Couldn't save that" and nothing changes.

### Requirements

Defined 2026-10-05 from what the code already cites for them (they were cited in source with no spec behind them; see rules.md "Known documentation debt"):

- **CONTACT-01: One person's whole picture.** Contact detail shows the person's photo (initials as the fallback), name and number, the lists they are on, their stats and their call history, from one state contract (`ContactDetailUiState`).
- **CONTACT-02: Neutral stats.** Last call, total calls, average length, longest gap and "Usually", in factual labels: no "overdue", no "haven't called", no time-since framing beyond the facts. A stat without enough history says "Not enough calls yet" ("Average length" and "Usually" need three measured calls, "Longest gap" two), and a stat Orbit cannot know without call log access is left out under a notice rather than claimed.
- **CONTACT-03: A schedule for one person.** For someone on two or more lists, a per-contact override of the rhythm (`ContactEntity.ruleOverrideJson`), edited with the same kind picker as List settings and an interval slider (the shared `OrbitSlider`) that moves both cooldown bounds.
- **CONTACT-04: Pause.** The overflow's Pause opens the shared duration sheet (1 week, 1 month, until you unpause); the pause commits with an Undo snackbar, and a paused person shows "Paused until ..." with Unpause in the overflow.
- **CONTACT-05: The pause has ended.** When a timed pause has lapsed (never an indefinite one), a banner at the top says so ("{Name} is unpaused" / "They'll come up again on their lists.") and can be dismissed; the whole banner is a button named "Dismiss unpause notice", the same as its x.
- **CONTACT-09: Logging an attempt.** "Log a connection" can record a reach-out that did not connect ("Couldn't reach them": a voicemail, no answer) as `CallSource.ATTEMPT`, confirmed by "Attempt logged." The row reads "Attempted" with a phone-slash icon, and an attempt stays out of Last call, Total calls, Average length and Usually (`ContactMapper.withCallStats`); it advances the rotation only by `AttemptCooldown`.
- **CONTACT-10: Ignored and archived on the page.** An ignored person's page shows "Ignored" under the number and offers Unignore (with Undo) in place of Ignore, and no Pause; an archived person's shows "Archived". The state the user set is said where they set it, as the paused state is.
- **CONTACT-08: A failed read is an error, not a crash.** If a source stream fails, the screen says "Couldn't load this person" with "Try again", which re-subscribes every source. Before 2026-10-05 the exception escaped `viewModelScope` and took the app down (rubric plan 3.5, gate G5).
- **NOTE-01: Notes journal.** A running journal of timestamped notes on the person: add from the inline field, edit, delete with Undo, all written through encrypted Room. Edit and delete are reachable from a visible menu as well as by gesture.
- **NOTE-02: Jump straight to the note field.** Entry points that ask for a note (the post-call banner, a call-history row) open Contact detail with `focusNote=1`, and the note field takes focus once the screen settles.

### Acceptance criteria

- [x] A failing source shows the error state and Retry recovers (CONTACT-08; `ContactDetailViewModelTest`).
- [x] Stats update live when call-detection records a new call (`ContactDetailViewModelTest`, "a new call event re-emits Ready with the stats updated").
- [ ] Pause copy feels calm, not punitive.
- [x] Note input supports multi-line, 16sp minimum (`NotesSection`: `BasicTextField(maxLines = 4, textStyle = OrbitTheme.type.body)`; `body` is 16sp, never smaller, rules.md Design 2).
- [x] Longest-gap framing neutral, never prefixed with "you haven't..." (the label is "Longest gap", the value a bare span such as "21 days", `time_span_days`).
- [x] Deleting a phone contact does not crash this screen; the orphan banner renders with Re-link and Archive, and the notes stay (`ContactDetailViewModelTest` "an orphaned person emits Orphaned with their notes and call ids", `OrphanBannerTest`, `ContactDetailScreenTest`).
- [x] Arriving from a Call history row shows that call and "Add note to this call" (`ContactDetailScreenTest`).
- [ ] Dark mode + 200% font scale + TalkBack pass.
- [ ] All user-private data (notes, full call history) written through encrypted Room per ADR 0002.

### Not in scope

- Editing the contact's name or phone number in-app. System Contacts is the source of truth.
- Aggregate stats dashboards. Per PRD §v1 Scope "Out," a stats dashboard is v1.1+.
- Per-contact notification toggles. Notifications toggle at the list level only.
- Importing/exporting a single contact's data. The global export flow (see `features/privacy-and-lock/README.md`) covers everything.

### Open product questions

- ~~"Archive contact" action distinct from "remove from list"?~~ Resolved: yes — Ignore and Archive are distinct contact-level actions on this screen; the orphan banner offers Re-link + Archive.
- ~~Notes retention when phone contact is deleted: PRD says "keep app data"; where/how is this surfaced to the user? (Banner? Archive view?)~~ Resolved 2026-10-06: the notes stay visible on the orphaned page, read-only, under the banner's "History stays here"; Re-link merges them onto the linked person, Archive hides the person from lists and keeps them.
- Post-call note prompt — dismissal: per-contact, per-call, or global? Leaning per-call.
- The lists chips should open their list, per this spec; they are read-only today. Doing it needs a navigation callback the screen doesn't have yet.

---

## Technical

### Architecture

`ContactDetailViewModel` composes a `ContactDetailUiState` by `combine`-ing multiple Flows: `ContactEntity`, `Flow<List<CallEventEntity>>`, `Flow<List<ListMembershipEntity>>`, `Flow<List<NoteEntity>>`. The per-contact rule override lives as a JSON column on the contact (`ContactEntity.ruleOverrideJson`) — there is no separate override entity.

All encrypted-Room reads per ADR 0002. Stats (longest gap, avg length, time-of-day patterns) computed in Kotlin from the call-history Flow — pure and testable, no SQL view. "Usually" comes from Card view's call-pattern overlay (`withCallPatterns`), bucketed by an injected `ZoneId`.

### Data model

Reads: `ContactEntity` (including `isIgnored`, `isArchived`, `phoneContactId`), `CallEventEntity[]`, `ListMembershipEntity[]`, `NoteEntity[]`.
Writes: `NoteEntity` (create/update/delete, incl. retroactive back-dated notes), `CallEventEntity` (a connection logged by hand, `source = MANUAL`, or an attempt, `source = ATTEMPT`, both with `durationSeconds = 0`, through `MarkCalledUseCase`), `ContactEntity.pausedUntil`, `ContactEntity.ruleOverrideJson` (set/clear), ignore/unignore (`IgnoreContactUseCase`, `UnignoreContactUseCase`) and archive state, and the re-link merge (`RelinkContactUseCase`: one transaction moves the picked row's call events, notes, phones and memberships onto the orphan, then deletes that row).

### Permissions / integrations

- `READ_CONTACTS` — photo resolution when not cached.
- `READ_CALL_LOG`: not requested here, but read on every resume (ARCH-04, `ContextCompat.checkSelfPermission`) so the stats say when Orbit cannot see the phone's calls.
- System Contacts deep-link: `Intent.ACTION_VIEW` on `ContactsContract.Contacts.CONTENT_URI` with the person's `phoneContactId` (`Context.openPhoneContact`, the overflow's "Open in Contacts").
- Call action inherits dial mechanism from card-view (see `features/card-view/README.md`).
- Widgets: pause, unpause and their undos ask `WidgetRefreshTrigger` for a refresh (WIDGET-06 in [`widgets`](../widgets/README.md)).

### Known gotchas

- Notes are user-private — must write through encrypted Room (ADR 0002), not DataStore.
- Orphaned contacts must render without crashing — `ContactEntity` persists even if `androidContactId` lookup returns null. Photo falls back to generated initial.

### Not in scope (technical)

- SQL views for stats. Kotlin computation is fast enough and keeps the engine testable on JVM.
- Caching stats. Recompute per collect; call-history Flow already debounces upstream.

### Open technical questions

- Longest-gap computation: over all history, or windowed to the same time window the user is viewing? Leaning all-history — one number, unambiguous.
