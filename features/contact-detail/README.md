# contact-detail

**Status:** in-progress
**Last reviewed:** 2026-06-09
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/contact/` (`ContactDetailScreen`, `ContactDetailViewModel`, `ContactDetailUiState`, `sections/` — `LogConnectionSheet`, `NotesSection`, `PauseSheet`, `RuleOverrideSection`, `UnpauseBanner`)
- Tests: `android/app/src/test/java/app/orbit/ui/screens/contact/` (`ContactDetailViewModelTest`, `RuleOverrideSectionTest`); instrumented: `android/app/src/androidTest/java/app/orbit/ui/screens/contact/` (`OrphanBannerTest`, `UnpauseBannerTest`)

---

## Product

### Why it exists

When the user needs more than the card-view summary — full history, notes, per-contact overrides, pause — this is where it lives. A complete profile of one person's place in the user's orbit. Call history lives here too, so a separate "stats" screen is unnecessary in v1.

### User story

As a user, I open a contact to see their full call history, the lists they're on, patterns over time, and my running notes. I can pause them, tune their rules, or edit them in my phone contacts.

### Behavior

**Hero.** Photo, name, phone number, Call action, and "Log a connection" — a sheet that records a manual call event (`CallSource.MANUAL`) for calls Orbit couldn't observe.

**Stats card.** Last call, total calls, average length, longest gap, patterns by time-of-day (earliest/latest). All neutral framing — no streaks, no achievements. Longest gap surfaced without shame framing.

**Lists chip row.** Every list this contact is on, clickable to jump to that list.

**Call history.** Chronological, with date, duration, direction (outgoing/incoming), and list context ("called from late night list, 14 min").

**Notes.** Running journal, each note timestamped. Add, edit, delete. Retroactive back-dated notes can be attached from a call-history row. Post-call prompt ("want to add a note?") surfaces as a dismissible banner on next app open after a call — never blocking.

**Pause.** Duration picker — 1 week, 1 month, indefinite. Copy: "pause for a week," not "hide for 7 days." Unpause prompt surfaces when the duration ends.

**Per-contact rule override.** Hidden until the contact is on 2+ lists (per PRD) to avoid config clutter for single-list contacts. The override sheet opens read-only and persists only when the user actually changes a value; the interval slider commits BOTH cooldown bounds via `RuleParams.KeepInTouch.withIntervalHours` so "every 30 days" honestly becomes "every 14".

**Edit.** Deep-links to the system Contacts app — Orbit does not own name/number.

**Orphaned state.** If the phone contact is deleted, the Orbit data remains; a "disconnected" badge surfaces and the user can archive or re-link.

### Acceptance criteria

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

---

## Technical

### Architecture

`ContactDetailViewModel` composes a `ContactDetailUiState` by `combine`-ing multiple Flows: `ContactEntity`, `Flow<List<CallEventEntity>>`, `Flow<List<ListMembershipEntity>>`, `Flow<List<NoteEntity>>`. The per-contact rule override lives as a JSON column on the contact (`ContactEntity.ruleOverrideJson`) — there is no separate override entity.

All encrypted-Room reads per ADR 0002. Stats (longest gap, avg length, time-of-day patterns) computed in Kotlin from the call-history Flow — pure and testable, no SQL view.

### Data model

Reads: `ContactEntity`, `CallEventEntity[]`, `ListMembershipEntity[]`, `NoteEntity[]`.
Writes: `NoteEntity` (create/update/delete, incl. retroactive back-dated notes), `CallEventEntity` (manual log, `source = MANUAL`), `ContactEntity.pausedUntil`, `ContactEntity.ruleOverrideJson` (set/clear), ignore/archive state.

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
