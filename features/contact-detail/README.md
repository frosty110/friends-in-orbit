# contact-detail

**Status:** in-progress
**Last reviewed:** 2026-10-08 (notes after a call: NOTE-04, NOTE-05; the Log a connection sheet shared with the card, CARD-10; the custom schedule as one interval on the day wheel, LIST-30 and ADR 0011; archived lists out of sight, LIST-24)
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/contact/` (`ContactDetailScreen`, `ContactDetailViewModel`, `ContactDetailUiState`, `sections/`: `NotesSection`, `RuleOverrideSection`, `UnpauseBanner`; the pause sheet is the shared `ui/components/PauseDurationSheet`, and the Log a connection sheet the shared `ui/components/LogConnectionSheet`, with its write in `domain/usecase/LogConnectionUseCase.kt`, both moved out of this screen on 2026-10-07 when the card started logging too, CARD-10 in `features/card-view/README.md`); re-link merge in `android/app/src/main/java/app/orbit/domain/usecase/RelinkContactUseCase.kt`
- The page for writing about a call (NOTE-04): `android/app/src/main/java/app/orbit/ui/screens/note/` (`PostCallNoteScreen`, `PostCallNoteViewModel`, `PostCallNoteUiState`), copy in `res/values/strings_note.xml`. The calls waiting for a note (NOTE-05): `CallEventDao.observeWaitingForNote` and `android/app/src/main/java/app/orbit/data/repository/WaitingCalls.kt`, dismissals in `AppPrefs` (`post_call_dismissed`).
- Tests, all on the JVM: `android/app/src/test/java/app/orbit/ui/screens/contact/` (`ContactDetailViewModelTest`, `ContactOverflowMenuTest`, `ContactDetailCurtainTest`, `ContactDetailScreenTest`, `OrphanBannerTest`, `UnpauseBannerTest`, `sections/NotesMenuTest`, `sections/RuleOverrideSectionTest`), `android/app/src/test/java/app/orbit/domain/usecase/RelinkContactUseCaseTest.kt`. The two banner tests moved from `androidTest` on 2026-10-06, so they gate every push instead of waiting for an emulator. NOTE-04: `ui/screens/note/PostCallNoteViewModelTest`, `PostCallNoteContentTest`. NOTE-05: `data/repository/WaitingCallsTest` (in-memory Room and a real DataStore).

---

## Product

### Why it exists

When the user needs more than the card-view summary — full history, notes, per-contact overrides, pause — this is where it lives. A complete profile of one person's place in the user's orbit. Call history lives here too, so a separate "stats" screen is unnecessary in v1.

### User story

As a user, I open a contact to see their full call history, the lists they're on, patterns over time, and my running notes. I can pause them, tune their rules, or edit them in my phone contacts.

### Behavior

**Hero.** Photo, name, phone number, Call action, and "Log a connection": a sheet that records a connection Orbit couldn't observe (`CallSource.MANUAL`) or, with "Couldn't reach them", an attempt (`CallSource.ATTEMPT`: a voicemail, no answer; CONTACT-09). It is the one sheet and the one write for this, shared with the card's "Log a connection" (CARD-10): `LogConnectionSheet` in `ui/components`, and `LogConnectionUseCase`, which both ViewModels call, so the two screens log exactly alike. Under the number, a status line where one applies: "Paused until 12 Oct", "Paused until you unpause", "Ignored", "Archived". The name is the page's heading and its pane title, so TalkBack announces whose page this is (the app bar carries no title here); a page with no person, "This person isn't in Orbit anymore" or "Couldn't load this person", is announced by that heading instead (until 2026-10-06 those two states had no pane title at all).

**Stats card.** Last call, total calls, average length, longest gap, and "Usually": the part of the day this person is usually called (Mornings, Afternoons, Evenings or Late nights), the same reading Card view shows. Its info tip reads "Based on when you usually answer or call this person." (it said "this contact" until 2026-10-05, as Card view's did). "Usually" and "Average length" need three measured calls (ones with a length; a connection logged by hand has none, so until 2026-10-06 one carrier call plus two logged connections showed that single call's length as the average) and "Longest gap" two; below that each says "Not enough calls yet" (2026-10-05; it was a bare dash, which read as broken, vision CONTACT-3). The words are the shared `components_stat_*` strings, the same ones Card view uses. The label is "Average length", not "Avg length". All neutral framing, no streaks, no achievements. Longest gap surfaced without shame framing.

**Without call log access.** When `READ_CALL_LOG` is denied (read on every resume, ARCH-04), a notice sits above the stats, "Orbit can't see your phone calls" with "Open settings", and nothing below it claims what Orbit cannot know: "Never called", "Not enough calls yet" and "No calls yet." are not shown, while "Total calls" and any calls or connections already recorded stay, because hand-logged connections are real data (rules.md Code 3; Browse drops its call meta for the same reason).

**Lists chip row.** Every list this contact is on, or "Not on any list yet". Under the privacy curtain each chip reads "List", like every list-name surface (it read "Contact"). Not yet clickable (see open questions).

**Call history.** "Recent calls": chronological, newest first, up to 50 rows (`RECENT_EVENTS_LIMIT`), each with an icon for its kind, its length (or "Logged" for a connection added by hand, "Attempted" for a reach-out that didn't connect) and the time since ("11 days ago"). The direction is in words for TalkBack as well as in the icon: "You called" or "{first name} called", the words Call history leads its rows with. List context is the chip row above, not a per-row line (`CallEventEntity` records no originating list). The overflow's "View all calls" opens Call history narrowed to this person (LOG-04 in [`call-history`](../call-history/README.md)). Arriving from a Call history row scrolls to that call, tints it, and offers "Add note to this call" under it (LOG-03); the rows are keyed by call-event id and the scroll target is found by that key, so the index cannot drift from the items (until 2026-10-06 it was hand-counted and landed one row too far for a person on one list).

**Notes.** Running journal, each note timestamped. Add, edit, delete. Edit (long-press) and delete (swipe) also sit in each note's visible "More" menu, so neither is gesture-only (2026-10-05, rubric D5). A note's text has no tap action, only the long press, which TalkBack offers as "edit note"; it used to announce a tap that did nothing. The timestamp is a button that switches between "11 days ago" and the date. Under the privacy curtain a note's body reads "Note hidden". Retroactive back-dated notes can be attached from a call-history row. A note can also be written on its own page right after a call (NOTE-04, below); it lands here like any other.

**Writing about a call (NOTE-04, NOTE-05; added 2026-10-07).** The owner asked, on Card view's Call button, for "a little feedback, survey, or notes ... how I felt about it or what I want to remember about the call", with a timer from zero that stays in view however long the entry gets, and, on Home's post-call banner, for every unnoted call of the last day rather than the last one, stacked and each closable (`vision/flows/owner-review-2026-10-07.md`, decisions 3 and 12). Two parts:

- *The calls waiting for a note* (NOTE-05) are listed on Home (HOME-14 in [`home`](../home/README.md)) and, when Orbit is closed, offered as a notification (NOTIF-16 in [`notifications`](../notifications/README.md)). Both read one definition (`WaitingCalls`), so they never disagree.
- *The post-call note page* (NOTE-04) is where a note about a call is written, from Home's "Add a note", from the notification, and from Card view's "Called Kai" snackbar ("Add a note"; CARD-11 will also open it by itself after a long enough call placed from the card). Contact detail's own note field stays as it is.

The page: an app bar with "Not now", the title "Your call with Kai" ("Your call" under the curtain) and a timer counting up from 0:00 from the moment the page opened ("2:14", "1:02:07" past an hour), in the bar so it never scrolls away. It is a nudge to keep the entry short, not a limit. Its start is kept in the ViewModel's SavedStateHandle, so turning the phone or the process dying never restarts it; TalkBack hears "Writing for 2 minutes", words that change once a minute, and no live region. Under the bar, the call in one line ("You called Kai · 14 min · Today at 4:30pm", or "Kai called you ..."; "You called · 14 min · ..." under the curtain), then one large writing field that fills the page and scrolls inside itself, with "How did it go? What do you want to remember?" as its placeholder and TalkBack label, and "Save note" (the page's one accent element), disabled while the field is blank and kept above the keyboard. Save writes an ordinary note through `AddNoteUseCase`, the same shape this page's note field writes, then returns to wherever the page was opened from, where "Note saved" shows (the app-level snackbar bus). A failed save says "Couldn't save your note. Try again." and keeps every word. "Not now" or Back with words written asks "Discard this note?" ("Keep writing" / "Discard"); with nothing written it just leaves. The draft survives process death (SavedStateHandle). Under the privacy curtain the words are drawn as "Note hidden", as a note here is. A person who is no longer in Orbit gets the same not-found message as their own page, "This person isn't in Orbit anymore" with "Go back"; a failed read, the same "Couldn't load this person" with Try again.

**Pause.** Duration picker: 1 week, 1 month, or until you unpause, in the shared `PauseDurationSheet` (the one "Pause for how long?" sheet Browse uses too). Copy: "pause for a week," not "hide for 7 days." The snackbar reads "Paused {name} for 1 week", "... for 1 month" or "... until you unpause", with Undo. When a timed pause ends, a banner says so (CONTACT-05). Pausing, unpausing and undoing either refresh the widgets (WIDGET-06), so the widget stops offering someone just paused before the hourly sweep.

**Paused status and Unpause.** While a pause is in force (timed or indefinite), a status line under the phone number reads "Paused until {d MMM}" (for example "Paused until 12 Oct") or "Paused until you unpause", and the overflow shows "Unpause" in place of "Pause". Unpause restores surfacing now; snackbar "Unpaused {name}" with Undo back to the exact prior pause. Before this, an indefinite pause could not be undone once its snackbar was gone.

**Per-contact rule override.** Hidden until the contact is on 2+ lists (per PRD) to avoid config clutter for single-list contacts, unless a schedule is already saved: that one stays visible so it can be reset (`showsCustomSchedule`). It reads "Comes up every 14 days, like the rest of {list}." ("like the rest of their list" under the curtain; "Follows the rhythm of {list}." when the list's rhythm cannot be read) with a Secondary "Set a schedule for this person" (until 2026-10-05 a Primary "Override": a second accent element and engineering vocabulary). The editor opens read-only and persists only when the user actually changes a value. It is List settings' own "How often" control, the shared day wheel (`IntervalDaysPicker`, ADR 0011; LIST-30): one interval, no rhythm choice. Until 2026-10-07 it offered Keep in touch, Late night and Energize and read "Follows the keep in touch rhythm from {list}."; those three are one calculation with different starting numbers, so a person's schedule is one number too. An override stored as Late night or Energize shows its real starting interval, settling the wheel where it started writes nothing, and turning it makes it Keep in touch at the chosen interval, committing BOTH cooldown bounds through `toKeepInTouchEvery` so "every 30 days" honestly becomes every 30. The wheel moves a day at a time, announces "Every 14 days" to TalkBack, says "Aim for every day" at one day, and sits in ink rather than the accent.

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
- **CONTACT-03: A schedule for one person.** For someone on two or more lists, or anyone with a schedule saved (it keeps running on one list, so it stays visible and resettable; until 2026-10-08 a person left on one list kept a schedule the page hid), a per-contact override of the rhythm (`ContactEntity.ruleOverrideJson`), edited with the same day wheel as List settings (`IntervalDaysPicker`, ADR 0011; one interval, no rhythm choice since LIST-30), which moves both cooldown bounds.
- **CONTACT-04: Pause.** The overflow's Pause opens the shared duration sheet (1 week, 1 month, until you unpause); the pause commits with an Undo snackbar, and a paused person shows "Paused until ..." with Unpause in the overflow.
- **CONTACT-05: The pause has ended.** When a timed pause has lapsed (never an indefinite one), a banner at the top says so ("{Name} is unpaused" / "They'll come up again on their lists.") and can be dismissed; the whole banner is a button named "Dismiss unpause notice", the same as its x.
- **CONTACT-09: Logging an attempt.** "Log a connection" can record a reach-out that did not connect ("Couldn't reach them": a voicemail, no answer) as `CallSource.ATTEMPT`, confirmed by "Attempt logged." The row reads "Attempted" with a phone-slash icon, and an attempt stays out of Last call, Total calls, Average length and Usually (`ContactMapper.withCallStats`); it advances the rotation only by `AttemptCooldown`.
- **CONTACT-10: Ignored and archived on the page.** An ignored person's page shows "Ignored" under the number and offers Unignore (with Undo) in place of Ignore, and no Pause; an archived person's shows "Archived". The state the user set is said where they set it, as the paused state is.
- **CONTACT-08: A failed read is an error, not a crash.** If a source stream fails, the screen says "Couldn't load this person" with "Try again", which re-subscribes every source. Before 2026-10-05 the exception escaped `viewModelScope` and took the app down (rubric plan 3.5, gate G5).
- **NOTE-01: Notes journal.** A running journal of timestamped notes on the person: add from the inline field, edit, delete with Undo, all written through encrypted Room. Edit and delete are reachable from a visible menu as well as by gesture.
- **NOTE-02: Jump straight to the note field.** Entry points that ask for a note about one call on this page (a call-history row, with `scrollToCallEventId`) open Contact detail with `focusNote=1`, and the note field takes focus once the screen settles. Until 2026-10-07 Home's post-call banner and Card view's "Add a note" came here too; they open the post-call note page now (NOTE-04).
- **NOTE-04: The post-call note page.** A page of its own (`note/{contactId}?callEventId={id}`) for writing about one call: "Not now", "Your call with Kai" and a count-up timer that stays in the bar (kept across rotation and process death, spoken once a minute); the call in one line; one large field ("How did it go? What do you want to remember?"); "Save note", the one accent, disabled while blank. Save writes an ordinary note and returns with "Note saved"; a failed save says so and keeps the text; leaving with words written asks "Discard this note?"; the draft survives process death; the curtain hides the name and the words; a person who is gone gets "This person isn't in Orbit anymore" with "Go back". Without a `callEventId` the page describes the person's latest connected call. Opened from Home's waiting calls (HOME-14), the notification after a call (NOTIF-16) and Card view's "Called Kai" snackbar.
- **NOTE-05: Calls waiting for a note.** A call waits when it came from the call log (not a connection logged by hand, not an attempt), connected and lasted at least 60 seconds, in either direction; its person is on at least one list that is not archived and is neither ignored nor archived; it started within the last 24 hours; no note about that person was written at or after it started; and the user has not dismissed it. One entry per person: their latest such call stands for any earlier one. Dismissals are kept in DataStore (call event ids and when, never a name) across process death, and forgotten after 48 hours. Exposed as a Flow (`WaitingCalls.observe`), so a note saved anywhere takes the call off live. Until 2026-10-07 this was the banner's query: the latest outgoing call of the last ten minutes, dismissed in memory.

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
- [x] NOTE-05's rules each hold at their edges: 60 seconds waits and 59 does not, a call started exactly 24 hours ago waits and one a second earlier does not, a logged connection or an attempt never waits, nor a person who is ignored, archived, on no list or only on an archived list; a note since the call ends the wait and an older one does not; one entry per person; a dismissal survives a new process and is forgotten after 48 hours (`WaitingCallsTest`).
- [x] NOTE-04: the timer's start survives recreation; Save writes one note and leaves with "Note saved"; a failed save keeps the text and says so; Discard forgets the draft; a person who is gone is NotFound (`PostCallNoteViewModelTest`). The timer is spoken by the minute with no live region; Save waits for words; "Not now" and Back ask only with words written; the curtain hides the name and the words (`PostCallNoteContentTest`).

### Not in scope

- Editing the contact's name or phone number in-app. System Contacts is the source of truth.
- Aggregate stats dashboards. Per PRD §v1 Scope "Out," a stats dashboard is v1.1+.
- Per-contact notification toggles. Notifications toggle at the list level only.
- Importing/exporting a single contact's data. The global export flow (see `features/privacy-and-lock/README.md`) covers everything.

### Open product questions

- ~~"Archive contact" action distinct from "remove from list"?~~ Resolved: yes — Ignore and Archive are distinct contact-level actions on this screen; the orphan banner offers Re-link + Archive.
- ~~Notes retention when phone contact is deleted: PRD says "keep app data"; where/how is this surfaced to the user? (Banner? Archive view?)~~ Resolved 2026-10-06: the notes stay visible on the orphaned page, read-only, under the banner's "History stays here"; Re-link merges them onto the linked person, Archive hides the person from lists and keeps them.
- ~~Post-call note prompt, dismissal: per-contact, per-call, or global? Leaning per-call.~~ Resolved 2026-10-07: per call, and kept across process death (NOTE-05). Because one call stands for a person, dismissing it closes that person until they have a newer call.
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
