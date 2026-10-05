# orbit-lists

**Status:** in-progress
**Last reviewed:** 2026-10-05
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/lists/` (`ListsManagerScreen`/`ViewModel`, `ListConfigScreen`/`Body`/`ViewModel`, `CreateListBottomSheet`, `TemplateChoice`, `RuleTemplatePicker`, `MembersPreview`, `ActiveHoursEditor`, `SmartRuleEditor`, …); list picker: `android/app/src/main/java/app/orbit/ui/screens/picker/ListPickerScreen.kt` + `ListPickerViewModel.kt`; smart-list membership: `android/app/src/main/java/app/orbit/data/feed/SmartListMembershipSync.kt`
- Tests (pickers): `android/app/src/test/java/app/orbit/ui/screens/picker/` (`ContactPickerViewModelTest`, `ContactPickerUiStateTest`, `ListPickerViewModelTest`, `PickerModeTitleTest`)
- Tests: `android/app/src/test/java/app/orbit/ui/screens/lists/` (`ListsManagerViewModelTest`, `ListConfigViewModelTest`, `CreateListTemplateCatalogTest`, `ActiveHoursFormatterTest`, `IntervalScaleLabelsTest`, `SmartRuleEditIntegrationTest`), `android/app/src/test/java/app/orbit/ui/screens/picker/ListPickerViewModelTest.kt`, `android/app/src/test/java/app/orbit/data/feed/SmartListMembershipSyncTest.kt`

---

## Product

### Why it exists

Lists are how users shape their orbit. Not priority tiers, not categories — moods and contexts (inner orbit, late night, people who ground me). Each list has its own rules and active hours. A contact on multiple lists shares one global last-call timestamp — calling them anywhere updates everywhere. Without this cross-list propagation, lists would contradict each other and the app's coherence collapses.

### User story

As a user, I create lists that match how I actually think about my people. Each list surfaces on its own rhythm. When I call someone, every list they're on knows.

### Behavior

**Lists Manager.**
- Rows: name, member count, cooldown summary.
- Dashed "new list" CTA at end of list.
- Reorder via long-press drag. Archive removes from home while preserving data. Delete requires confirmation (destructive), then shows "List deleted." with Undo; the delete is held until the snackbar goes away, as on home (2026-10-05).
- Per-row overflow actions: rename, archive, **list settings** (opens List Configuration), move up/down. The action that opens List Configuration is labeled **"List settings"** everywhere — not "Configure" — matching the home long-press menu (see `features/home/README.md`).
- These per-list actions (add people, mute/unmute prompts, list settings, archive, delete) are **also reachable via a long-press on the list tile on home**. Home is the convenience surface; Lists Manager remains the full manager. The two must stay consistent — same labels, and the same delete-with-Undo behavior. Spec: `features/home/README.md` → "List tile long-press — quick actions".

**List creation.**
- Lists Manager create (`CreateListBottomSheet`) navigates straight to List Configuration on success.
- The list picker supports inline list creation (2026-06-09 #26), so there is no "create a list first, then come back" detour.
- **LIST-20: One way to create a list on Lists.** The floating "New list" button when lists exist; the centred "New list" button when there are none. The app bar's "+" was removed (2026-10-05): three create controls on one screen read as unfinished (UX rubric D2), and the empty state showed two accent elements.
- **LIST-21: List settings spend no accent on settings.** Only "Done" is in the accent. Selected rhythms, nudge days, switches and sliders use ink or the soft tint (rules.md §Design 5); there were seven or more accent elements before 2026-10-05. Sliders are the shared `OrbitSlider`, which tells TalkBack its value in words ("Every 14 days") rather than a percentage of the track. Words follow the voice.md glossary: "Rhythm" and "How often" (not "Cadence" and "Interval"), "Nudges" and "Send nudges" (not "Notifications" and "Reminders"), "1 day / 2 weeks / 1 month / 2 months" under the slider (not "1d / 2w / 1m / 2m"), and "Make this a regular list" (not "Convert to static list").
- Each create template writes its own Keep in touch interval as the list's override (2026-10-05; before, every template made the same 2-day list): "Inner orbit" "Closest people, about weekly." (7 days), "Family" "Steady, every couple of weeks." (14 days), "Mentors" "Every couple of months." (60 days, the slider's cap per ADR 0010, so no longer quarterly), "Drifted" "Reconnect about once a month." (30 days). "Recently added, not called" ("Auto-updates as you add people.") is smart and carries Keep in touch; "Start from blank" ("Choose your own rhythm.") keeps the template default (2 days). Pinned by `CreateListTemplateCatalogTest` and `ListsManagerViewModelTest`.

**List Configuration (per list).**
- Template selection is kind-based: a `TemplateChoice` catalog grouped by `RuleKind` (`KEEP_IN_TOUCH`, `LATE_NIGHT`, `ENERGIZE`). See `features/rule-engine/README.md` for the semantics.
- Interval tuning is honest — the slider moves both cooldown bounds together (interval honesty; see `IntervalScaleLabelsTest`).
- Member preview shows true member counts: first 20 rows + "Showing 20 of N" with a "Show all" affordance (`MembersPreview`).
- Cadence and Interval show for smart lists too (static only before 2026-10-05), so a smart list can be given a rhythm.
- Active hours (optional) — simple start/end pickers. Example: late night list active 9pm-2am. They gate when the list's nudge may post and never change its nudge days; changing them reschedules the nudge immediately (see `features/notifications/README.md`).
- Per-list notification toggle (see `features/notifications/README.md`).

**Smart lists (2026-10-05).**
- `SmartListMembershipSync` keeps each non-archived smart list's stored members equal to what its rule matches. A contact who starts matching becomes a member, due now; one who stops matching is removed (for "Recently added, not called", that is the moment you call them). The list's due count is recomputed after each change.
- So smart lists surface wherever static lists do: Home ("Next up", due counts), Card view, Browse and its queue, and nudges. Before, their members existed only inside List settings.
- A smart list with no cadence is given Keep in touch.
- Convert to static keeps the current members as a snapshot and ends syncing (the list is no longer smart); a list with no cadence gets Keep in touch.

**Pickers** (`ui/screens/picker/`; page views [Add people](../page-views/picker-contacts.md) and [Add to lists](../page-views/picker-lists.md)).
- The contact picker (BULK-05) files people into a list: search by name or number, sort, filters, an A to Z rail, and a docked bar that commits ("Add 3 to Inner orbit"). The list picker (BULK-06) is the reverse: one person, several lists.
- One visual system (2026-10-05, UX rubric D4): every filter is the shared `OrbitFilterChip`, every check mark the shared `OrbitCheckbox` (ink, not accent; the row carries the checkbox semantics), and the sort and "On a list" menus are the shared `OrbitDropdownMenu`. They were Material chips, checkboxes and menus with colour overrides. The only accent on either picker is the commit button; the rail's current letter is bold ink.
- Plain words (rubric D7): "Never called" (sentence case; it was lowercase), "On Inner orbit, Late night" (was "In: ..."), the "Not on a list" filter (was "Unsorted"), "On a list" (was "In list…"), "Select all 14 matches" (was "Select all matching (14)"), "Already added" on a list the person is on (was "added"), "Try removing a filter." (was "Try removing a chip or widening your thresholds in Settings.").
- The sort control and both "Clear" actions meet the 48dp floor (they were about 36 and 38dp); the search field has one clear control, not two.
- Privacy curtain: names, photos and list names are masked (list names read "List", or "On 2 lists"), and the list picker's title drops the person's name.

**Picker requirements** (defined 2026-10-05 from what the code already cites for them; PICK-09 is new):
- **BULK-05: Add people from a list.** The "+" on Browse and "Add people" elsewhere open the contact picker for that list (`pick/contacts?targetListId=...`), in Add, Move or Copy mode; the title says which ("Add people", "Move 3 people"). Until 2026-10-05 these read "Add contacts" and "Move 3 contacts"; the app says "people" for the people in Orbit.
- **BULK-06: Add one person to lists.** "Add to lists" on Contact detail and "Add to list" in Search open the list picker for that person, which can also make a new list inline.
- **PICK-01: Filter by list.** "On a list" filters the candidates to members of one of your other non-archived lists; the applied filter reads "On {list}".
- **PICK-02: Filters narrow together.** Active filters combine as AND: a contact shows only if it matches every one.
- **PICK-04: One row per person.** Avatar, name, a call line ("Last called 3 days ago · 4 calls", or "Never called" for no calls, never "0 calls"), the lists they're on, and a check mark; the "Never called" filter reads `callCount == 0` directly.
- **PICK-05: Search composes.** Search uses the shared `ContactSearch` matcher (name with accents folded, or phone digits) and narrows whatever the filters left.
- **PICK-06: A docked commit bar.** With anything selected, a bar docked under the list (not floating over it) shows the count, Clear, and the commit button; it never hides the last row.
- **PICK-08: Ignored people stay out.** Ignored contacts are hidden from the picker unless "Show ignored" is on; shown, they are muted, tagged "Ignored", and offer Unignore instead of selection.
- **PICK-09: A failed read is an error, not a crash.** Either picker shows "Couldn't load your contacts" / "Couldn't load your lists" with "Try again" when a source fails (the contact picker's address-book read included), instead of an uncaught exception in `viewModelScope`. The selection survives the error.

**Cross-list propagation.**
- Calling contact X updates last-call state everywhere X appears — home, card-view, browse, widget — via Flow.
- There is no per-list last-call timestamp. One `CallEntity` per real call; every list derives its view from the same source.

**List ordering.** User-controlled. No inherent priority — lists are peers.

### Acceptance criteria

- [ ] Creating a list without a name is not allowed; prompt is soft, not error-y.
- [ ] Archived lists don't appear on home but remain retrievable from settings.
- [ ] Active-hours toggle uses start/end pickers, not a schedule grid.
- [ ] A call from card-view updates home's due counts and browse's rows for the same contact without a manual refresh.
- [ ] Deleting a list requires confirmation; copy: "This removes the list. People stay in your contacts." (sentence case per voice.md; was lowercase until 2026-10-05)
- [ ] Dark mode + 200% font scale + TalkBack pass.

### Not in scope

- ~~Smart lists derived from filters~~ — no longer out of scope: smart lists shipped (`ListType.SMART`, `SmartListEngine`, `SmartRuleEditor`, convert-to-static via `ConvertToStaticDialog`).
- Sharing lists across devices / users. Local-only per `features/privacy-and-lock/README.md`.
- Importing/exporting a single list. Global export covers everything.

### Open product questions

- Max number of lists — soft limit, hard limit, or none? PRD says none; consider a soft cap if perf degrades.
- Rule template change after the list has data: re-evaluate all cooldowns or only new calls? Leaning recompute (rule-engine is pure and data is small).

---

## Technical

### Architecture

- `ListsManagerViewModel` observes `Flow<List<ListEntity>>` from Room; reorder via `sh.calvin.reorderable` in `ListsManagerScreen`.
- `ListConfigViewModel` observes a single list + its rule config Flow.
- `ListPickerViewModel` (under `ui/screens/picker/`) handles add-to-list flows, including inline list creation.
- `SmartListMembershipSync` (`data/feed/`, `@Singleton`) is started once from `OrbitApp.onCreate` and runs on `@ApplicationScope`. It reconciles each smart list's rows against `SmartListEngine.membership(rule)`, so smart lists use the same surfacing path as static ones instead of a second one.
- Cross-list propagation is automatic because call data is stored with `contactId` only (no `listId`); every list's due computation reads the same `CallEntity` rows.

### Data model

- `ListEntity` — id, name, sortOrder, isArchived, type (`STATIC` | `SMART`), `smartRuleJson` (smart lists), `ruleTemplateId`, active hours (nullable start/end), notificationsEnabled, `ruleParamsOverrideJson`.
- `ListMembershipEntity` — many-to-many with `ContactEntity`, plus per-list schedule state (`nextDueAt`, `skipCount`). A smart list's rows are written by `SmartListMembershipSync`, not by the user.
- Rule config as built: `ruleTemplateId` references `RuleTemplateEntity`; per-list param tweaks live in `ruleParamsOverrideJson` (kotlinx-serialization, total parsing). A per-contact override (`ContactEntity.ruleOverrideJson`) still wins over the list's params.

### Permissions / integrations

None directly.

### Known gotchas

- Rule config JSON parsing must be total: every field nullable and defaulted, tolerant of unknown fields. A rule schema change must not brick existing lists.
- Deleting a list must cascade: remove memberships, cancel scheduled list prompts (`features/notifications/README.md`).

### Not in scope (technical)

- Materializing per-list cooldown snapshots in a cache table. Rule-engine recomputes live.
- Per-list database instances. One Room DB for everything.

### Open technical questions

- ~~Rule config JSON library: kotlinx-serialization or Moshi?~~ Resolved: kotlinx-serialization (`domain/JsonProvider.kt`, `data/serialization/RuleOverrideSerializers.kt`).
