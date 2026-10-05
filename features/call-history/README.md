# call-history

**Status:** in-progress
**Last reviewed:** 2026-10-05 (people-screens pass)
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/calllog/` (`CallLogScreen.kt`, `CallLogViewModel.kt`, `CallLogUiState.kt`); route `Routes.CallLogPattern` (`call-log?contactId={contactId}`), reachable from Settings (everyone's calls, `Routes.CallLog`) and from Contact detail's "View all calls" (that person's calls, `Routes.callLogFor(id)`)
- Tests: `android/app/src/test/java/app/orbit/ui/screens/calllog/CallLogViewModelTest.kt`, `android/app/src/test/java/app/orbit/data/dao/CallEventDaoLogTest.kt`

---

## Product

### Why it exists

The in-app call log is a separate surface from the system call log because it shows *contextual* history: only calls to tracked contacts, with list context ("called Jim from sad list, 14 min"). The user can scan the rhythm of their reaching-out without the noise of every incoming spam call. It's also the surface from which the user can retroactively add a note to a recent call.

### User story

As a user, I open the in-app call log to see what I've reached out about recently. I can tap an entry to jump to the contact, or add a retroactive note. From one person's page, "View all calls" shows just our calls.

### Behavior

- Chronological list of calls to tracked contacts only (DAO filters `contactId IS NOT NULL`). Calls to untracked numbers are never shown here — that's the system dialer's job.
- Rows group under sticky calendar-day headers: "Today", "Yesterday", then "Wednesday 3 June"-style labels (LOCAL calendar days; an 11pm call read the next morning sits under "Yesterday"). Headers are headings for TalkBack.
- Each row: contact name, list-context subtitle ("from {ListName}"), duration, direction icon, wall-clock time ("4:30pm" — the day is carried by the section header, so rows don't repeat a relative date).
- **One person (LOG-04).** Opened from Contact detail, the log shows only that person's calls under "Calls with {name}", and back returns to them. Rows lead with what happened ("You called", "Sam called", "You logged a connection", "You tried to reach them") with the duration beneath, instead of repeating the same name and face on every row.
- Manually logged connections (source = MANUAL) render as "Logged" rows with a check-circle icon and no duration.
- Filter: direction chips: All / Incoming / Outgoing, one always chosen (radio semantics), in a row that scrolls sideways rather than breaking a label at large font sizes. MANUAL "Logged" rows count as reaching out: visible under All and Outgoing, hidden under Incoming. A narrowing filter that matches nothing keeps the chip row and shows a quiet one-liner.
- Tap a row → contact-detail scrolled to that call's row, with the inline "Add note to this call" affordance below it (the retroactive-note path).
- Long-press a row → quick actions: "Call again" (`ACTION_DIAL`) and "Open contact". "Add note" is intentionally absent — tap already lands on the focused call with the note affordance.
- Honest pagination: the log renders in 200-row increments with a "Show n more" footer where n is the real next increment (`min(remaining, 200)`); the footer disappears exactly when everything is shown.
- Ignored contacts stay visible but greyed (50% avatar opacity, subtle name + " (ignored)" suffix); rows remain tappable.
- **Honest states (LOG-05).** A quiet skeleton while loading. "No calls yet" only when Orbit can read the call log and there are none. Without call log access and with nothing recorded: "Orbit can't see your calls", what that means, and "Open settings" (Orbit's Settings, which owns the grant and the resync, as Card view's notice does; opened from Settings it goes back instead). Without access but with history: the rows, under a notice that new calls won't appear. A failed read: "Couldn't load your calls" with "Try again".
- Privacy curtain: names render as the literal "Contact" (rows and the "Calls with" title), avatars drop their photo, and the "from {list}" context is left out (list names are masked everywhere, per `ListContextChip`).

### Requirements

- **LOG-01: The in-app call log.** A chronological log of calls with tracked contacts (`CallEventDao.observeForLog` filters `contactId IS NOT NULL`, newest first), reached from Settings. It is a destination of its own (`Routes.CallLog`), not a section of another screen.
- **LOG-04: "View all calls" means this person.** Contact detail's "View all calls" opens the log narrowed to that contact (`call-log?contactId={id}`; the VM reads it with `observeForContact`), titled "Calls with {name}", and back returns to the contact. Without the argument the log is everyone's. Until 2026-10-05 the route had no argument, so the action opened everyone's calls (UX rubric D2).
- **LOG-05: Honest states.** The log never says something false while it waits or when it cannot see. It stays in Loading until both the data and the call-log permission are known (the screen reports the permission on every resume; ARCH-04). No events plus no access is a permission-denied state that explains and offers Settings, not "No calls yet" (rubric "What is unprofessional today" 15). A failed data stream is an error state with Retry, not an uncaught exception (rubric plan 3.5).

### Acceptance criteria

- [x] Only calls with a matched `contactId` appear; unmatched number calls are hidden.
- [x] Duration formatted human-readably via `formatDuration`.
- [x] Retroactive note flow: tap routes to ContactDetail with `scrollToCallEventId`; the inline "Add note to this call" button anchors the note to that call event (LOG-03).
- [x] Scrolls smoothly with large histories — virtualized LazyColumn + 200-row pagination increments.
- [x] "View all calls" on Contact detail shows only that person's calls (LOG-04; `CallLogViewModelTest`).
- [x] Denied access with no history reads as denied, never "No calls yet"; unknown permission stays Loading (LOG-05; `CallLogViewModelTest`).
- [x] A failing stream shows an error with Retry, and Retry recovers (LOG-05; `CallLogViewModelTest`).
- [ ] Dark mode + 200% font scale + TalkBack pass (needs device run; the preview gallery renders every state in light, dark and font scales up to 200%).
- [x] Encrypted Room reads per ADR 0002.

### Not in scope

- Showing all calls, including untracked numbers. System dialer's job.
- Editing call metadata (duration, direction). Sourced from `CallLog.Calls`; read-only.
- Exporting just the call log. Export covers everything (`features/privacy-and-lock/README.md`).
- Analytics dashboards. PRD §v1 Scope "Out."

### Open product questions

- ~~Infinite scroll or monthly pagination?~~ Resolved: in-memory pagination in 200-row increments behind an honest "Show n more" footer.
- Filter by list: show only calls from contacts on a specific list? Useful but clutters the screen; hold for v1.1. (Filter by person shipped as LOG-04.)

---

## Technical

### Architecture

`CallLogViewModel` combines four flows, the events (`CallEventRepository.observeForLog(Int.MAX_VALUE)`, or `observeForContact(id, Int.MAX_VALUE)` when the route names a person; bounded in practice by the 90-day import window), contacts, list memberships, and lists, joins them into pre-formatted `CallLogRow`s, then applies the view query (direction filter + visible count, one atomic StateFlow so a filter change resets pagination in a single emission) and the call-log permission the screen pushes. Grouping, filtering, and pagination happen in-memory on `Dispatchers.Default`; all formatting (wall-clock time, duration, direction word/icon, event kind) is pre-computed on the VM so the composable never touches `Instant` or the JVM clock. Every state carries a `CallLogScope` (everyone, or the person and their name) so the title is right in every state. A retry counter feeds `flatMapLatest`, so Retry re-subscribes every source; a failure is caught into the Error state.

### Data model

Reads: `CallEventEntity` (via `CallEventDao.observeForLog`, which filters `contactId IS NOT NULL` and orders DESC, or `observeForContact`) joined in the VM with `ContactEntity` (name, photo, `isIgnored`) and `ListMembershipEntity` + `ListEntity` (list-context subtitle).

### Permissions / integrations

- READ_CALL_LOG is read (never requested) by the screen on every resume (ARCH-04) and pushed to the VM, only to tell "none" from "can't see". Granting happens in Settings.
- Relies on call-detection having populated the data.
- Encrypted Room per ADR 0002.

### Known gotchas

- A contact may be on multiple lists at the time of a call; "list context" chooses one for display. Decided policy: the contact's most-recent `ListMembership` (max `addedAt`); zero memberships → the subtitle fragment collapses away.
- Orphaned events (contactId no longer resolving during an FK-cascade window) are dropped defensively via `mapNotNull` rather than rendered as ghost rows.
- `CallLogViewModelTest` runs on the plain JVM, where there is no platform Main dispatcher after `resetMain`. Each test cancels and joins its VMs' scope before returning (`runVmTest`); otherwise the VM's Default-dispatched join finishes after the test and fails the next one.

### Not in scope (technical)

- Storing call audio or transcripts. Never.
- Syncing call history across devices.

### Open technical questions

- ~~Where does "originating list" for a call get recorded?~~ Resolved differently: v1 does not record an originating list; list context is derived as the contact's most-recent membership. Revisit only if users find the derived context misleading.
