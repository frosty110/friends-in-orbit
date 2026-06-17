# call-history

**Status:** in-progress
**Last reviewed:** 2026-06-09
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/calllog/` (`CallLogScreen.kt`, `CallLogViewModel.kt`, `CallLogUiState.kt`); route `Routes.CallLog`, reachable from Settings and ContactDetail's "view all calls"
- Tests: `android/app/src/test/java/app/orbit/ui/screens/calllog/CallLogViewModelTest.kt`, `android/app/src/test/java/app/orbit/data/dao/CallEventDaoLogTest.kt`

---

## Product

### Why it exists

The in-app call log is a separate surface from the system call log because it shows *contextual* history: only calls to tracked contacts, with list context ("called Jim from sad list, 14 min"). The user can scan the rhythm of their reaching-out without the noise of every incoming spam call. It's also the surface from which the user can retroactively add a note to a recent call.

### User story

As a user, I open the in-app call log to see what I've reached out about recently. I can tap an entry to jump to the contact, or add a retroactive note.

### Behavior

- Chronological list of calls to tracked contacts only (DAO filters `contactId IS NOT NULL`). Calls to untracked numbers are never shown here — that's the system dialer's job.
- Rows group under sticky calendar-day headers — "Today", "Yesterday", then "Wednesday 3 June"-style labels (LOCAL calendar days; an 11pm call read the next morning sits under "Yesterday").
- Each row: contact name, list-context subtitle ("from {ListName}"), duration, direction icon, wall-clock time ("4:30pm" — the day is carried by the section header, so rows don't repeat a relative date).
- Manually logged connections (source = MANUAL) render as "Logged" rows with a check-circle icon and no duration.
- Filter: direction chips — All / Incoming / Outgoing. MANUAL "Logged" rows count as reaching out: visible under All and Outgoing, hidden under Incoming. A narrowing filter that matches nothing keeps the chip row and shows a quiet one-liner.
- Tap a row → contact-detail scrolled to that call's row, with the inline "Add note to this call" affordance below it (the retroactive-note path).
- Long-press a row → quick actions: "Call again" (`ACTION_DIAL`) and "Open contact". "Add note" is intentionally absent — tap already lands on the focused call with the note affordance.
- Honest pagination: the log renders in 200-row increments with a "Show n more" footer where n is the real next increment (`min(remaining, 200)`); the footer disappears exactly when everything is shown.
- Ignored contacts stay visible but greyed (50% avatar opacity, subtle name + " (ignored)" suffix); rows remain tappable.
- Empty state: "No calls yet" with the phone-icon layout. Voice per `features/_foundations/voice.md`.
- Privacy curtain: names render as the literal "Contact" when the app loses focus, same convention as Browse/Card View.

### Acceptance criteria

- [x] Only calls with a matched `contactId` appear; unmatched number calls are hidden.
- [x] Duration formatted human-readably via `formatDuration`.
- [x] Retroactive note flow: tap routes to ContactDetail with `scrollToCallEventId`; the inline "Add note to this call" button anchors the note to that call event (LOG-03).
- [x] Scrolls smoothly with large histories — virtualized LazyColumn + 200-row pagination increments.
- [ ] Dark mode + 200% font scale + TalkBack pass (needs device run).
- [x] Encrypted Room reads per ADR 0002.

### Not in scope

- Showing all calls, including untracked numbers. System dialer's job.
- Editing call metadata (duration, direction). Sourced from `CallLog.Calls`; read-only.
- Exporting just the call log. Export covers everything (`features/privacy-and-lock/README.md`).
- Analytics dashboards. PRD §v1 Scope "Out."

### Open product questions

- ~~Infinite scroll or monthly pagination?~~ Resolved: in-memory pagination in 200-row increments behind an honest "Show n more" footer.
- Filter by list: show only calls from contacts on a specific list? Useful but clutters the screen; hold for v1.1.

---

## Technical

### Architecture

`CallLogViewModel` combines four flows — `CallEventRepository.observeForLog(Int.MAX_VALUE)` (full correlated set, bounded in practice by the 90-day import window), contacts, list memberships, and lists — joins them into pre-formatted `CallLogRow`s, then applies the view query (direction filter + visible count, one atomic StateFlow so a filter change resets pagination in a single emission). Grouping, filtering, and pagination happen in-memory on `Dispatchers.Default`; all formatting (wall-clock time, duration, direction word/icon) is pre-computed on the VM so the composable never touches `Instant` or the JVM clock.

### Data model

Reads: `CallEventEntity` (via `CallEventDao.observeForLog`, which filters `contactId IS NOT NULL` and orders DESC) joined in the VM with `ContactEntity` (name, photo, `isIgnored`) and `ListMembershipEntity` + `ListEntity` (list-context subtitle).

### Permissions / integrations

- None directly. Relies on call-detection having populated the data.
- Encrypted Room per ADR 0002.

### Known gotchas

- A contact may be on multiple lists at the time of a call; "list context" chooses one for display. Decided policy: the contact's most-recent `ListMembership` (max `addedAt`); zero memberships → the subtitle fragment collapses away.
- Orphaned events (contactId no longer resolving during an FK-cascade window) are dropped defensively via `mapNotNull` rather than rendered as ghost rows.

### Not in scope (technical)

- Storing call audio or transcripts. Never.
- Syncing call history across devices.

### Open technical questions

- ~~Where does "originating list" for a call get recorded?~~ Resolved differently: v1 does not record an originating list; list context is derived as the contact's most-recent membership. Revisit only if users find the derived context misleading.
