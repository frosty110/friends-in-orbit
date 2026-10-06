# orbit-lists

**Status:** in-progress
**Last reviewed:** 2026-10-06
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/lists/` (`ListsManagerScreen`/`ViewModel`, `ListConfigScreen`/`Body`/`ViewModel`, `CreateListBottomSheet`, `TemplateChoice`, `RuleTemplatePicker`, `MembersPreview`, `ActiveHoursEditor`, `SmartRuleEditor`, …); list picker: `android/app/src/main/java/app/orbit/ui/screens/picker/ListPickerScreen.kt` + `ListPickerViewModel.kt`; smart-list membership: `android/app/src/main/java/app/orbit/data/feed/SmartListMembershipSync.kt`
- Tests (pickers): `android/app/src/test/java/app/orbit/ui/screens/picker/` (`ContactPickerViewModelTest`, `ContactPickerUiStateTest`, `ListPickerViewModelTest`, `PickerModeTitleTest`)
- Tests: `android/app/src/test/java/app/orbit/ui/screens/lists/` (`ListsManagerViewModelTest`, `ListConfigViewModelTest`, `ListRowMenuOrderTest`, `ListsManagerScreenTest`, `TemplateSelectionSemanticsTest`, `RenameListDialogCurtainTest`, `CreateListTemplateCatalogTest`, `ActiveHoursFormatterTest`, `IntervalScaleLabelsTest`, `SmartRuleEditIntegrationTest`), `android/app/src/test/java/app/orbit/ui/screens/picker/ListPickerViewModelTest.kt`, `android/app/src/test/java/app/orbit/data/feed/SmartListMembershipSyncTest.kt`
- Page views: [Lists](../page-views/lists-manager.md), [List settings](../page-views/list-config.md)

---

## Product

### Why it exists

Lists are how users shape their orbit. Not priority tiers, not categories — moods and contexts (inner orbit, late night, people who ground me). Each list has its own rules and active hours. A contact on multiple lists shares one global last-call timestamp — calling them anywhere updates everywhere. Without this cross-list propagation, lists would contradict each other and the app's coherence collapses.

### User story

As a user, I create lists that match how I actually think about my people. Each list surfaces on its own rhythm. When I call someone, every list they're on knows.

### Behavior

**Lists Manager.**
- Rows: name, member count, rhythm summary. A regular list's second line is its rhythm ("Every 14 days" for Keep in touch, from the per-list override or the template's default; "Late night rhythm" / "Energize rhythm" for the two with nothing to set); a smart list's is its rule ("Recently added · 30 days"). Regular lists had no second line until 2026-10-06 (`ListsManagerViewModelTest`).
- One "New list" control (LIST-20).
- Reorder by the row's drag handle, or Move up/down in its menu. Archive removes from home while preserving data. Delete requires confirmation (destructive), then shows "List deleted." with Undo; the delete is held until the snackbar goes away, as on home (2026-10-05).
- Per-row overflow actions, in order: rename, **list settings** (opens List Configuration), pause or resume nudges, move up/down, then archive behind the divider (`ListRowMenuOrderTest`). The action that opens List Configuration is labeled **"List settings"** everywhere, not "Configure", matching the home long-press menu (see `features/home/README.md`). Pause nudges / Resume nudges and the Archive line use Home's strings, so the two menus cannot drift word by word; pausing a list's nudges was possible from Home but not from here until 2026-10-06.
- These per-list actions (add people, pause or resume nudges, list settings, archive, delete) are **also reachable via a long-press on the list tile on home**. Home is the convenience surface; Lists Manager remains the full manager. The two must stay consistent: same labels, and the same delete-with-Undo behavior. Spec: `features/home/README.md`, the list tile long-press quick actions section.
- Every confirmation follows the write (rules.md Code 3). "List archived." with Undo, "List restored.", "Nudges paused." / "Nudges on." and List settings' "This is now a regular list." are emitted by the ViewModel only once the write succeeded; a failed write says "Couldn't save your change" and nothing else. Until 2026-10-06 a failed archive queued its success message behind the failure, and the restore and convert messages were the screen's and showed before the write resolved (`ListsManagerViewModelTest`, `ListConfigViewModelTest`).
- Archive, restore and a committed delete ask the widget to refresh (WIDGET-06, `features/widgets/README.md`): the widget reads the active lists and would otherwise keep offering an archived list's lead until its hourly sweep. Undo of a delete changes nothing in the database, so it asks for nothing.
- While the lists load, the screen shows the shared list skeleton, never a blank body and never "No lists yet" before the lists are known (ADR 0006 as amended; `ListsManagerLoadingPreview`).

**List creation.**
- Lists Manager create (`CreateListBottomSheet`) navigates straight to List Configuration on success. The sheet's templates are one radio group (each tile announces as a radio button with its selected state, `TemplateSelectionSemanticsTest`); Create stays disabled until a template and a non-blank name exist, and the "Name your list" heading is the prompt (no error line).
- The list picker makes a new list inline when the user has no lists yet (2026-06-09 #26), so a first "Add to lists" has no "create a list first, then come back" detour.
- **LIST-20: One way to create a list on Lists.** The floating "New list" button when lists exist; the centred "New list" button when there are none. The app bar's "+" was removed (2026-10-05): three create controls on one screen read as unfinished (UX rubric D2), and the empty state showed two accent elements.
- **LIST-23: Tapping a list opens its cards, everywhere.** On Lists a row tap opened List settings while the same tap on Home opened the list's deck; one object, two meanings (UX rubric D2). Both now open the deck; "List settings" stays in the row's overflow menu, as on Home's long-press menu. The screen therefore leaves for two routes and takes two callbacks: `onOpenList` for the row tap (`Routes.card`) and `onOpenListSettings` for the menu's "List settings", the archived row's settings control and a successful Create (`Routes.listConfig`). Until 2026-10-06 it had only the first, so all three opened the deck and a brand-new list opened as a deck with nobody in it (`ListsManagerScreenTest` pins each control's destination). The NavHost passes both callbacks; the settings one defaults to the deck's until it does.
- **LIST-22: Lists and list settings have an error state.** If the lists or the list cannot be read, the screen says so ("Orbit couldn't load your lists" / "this list"), that nothing is lost, and offers Try again (UX rubric D6). Before, a failed read ended the stream silently. A list that no longer exists (deleted elsewhere, a stale link) is not an error: List settings says "List not found" / "It may have been deleted." with "Go back", through the shared `OrbitScreenMessage`, instead of one centred line with nothing to do. Tested: `ListsManagerViewModelTest` and `ListConfigViewModelTest` (a failing source shows Error, Try again recovers, a missing list is NotFound).
- **LIST-21: List settings spend no accent on settings.** Only the app bar's "Done" is in the accent: the foot-of-form Done is Secondary and the active-hours bar is ink on a muted track (both were accent until 2026-10-06, so a list with active hours showed three accent elements). Selected rhythms, nudge days, switches and sliders use ink or the soft tint (rules.md §Design 5); there were seven or more accent elements before 2026-10-05. Sliders are the shared `OrbitSlider`, which tells TalkBack its value in words ("Every 14 days") rather than a percentage of the track. Words follow the voice.md glossary: "Rhythm" and "How often" (not "Cadence" and "Interval"), "Nudges" and "Send nudges" (not "Notifications" and "Reminders"), "1 day / 2 weeks / 1 month / 2 months" under the slider (not "1d / 2w / 1m / 2m"), and "Make this a regular list" (not "Convert to static list"). Each setting that has a value reads as one sentence with the value as its argument ("Aim for every 14 days", "Added in the last 30 days", "The top 20% of the people you call"), so a translation can reorder the words (voice.md, "keep a sentence whole"); the slider's TalkBack value stays its own words.
- Each create template writes its own Keep in touch interval as the list's override (2026-10-05; before, every template made the same 2-day list): "Inner orbit" "Closest people, about weekly." (7 days), "Family" "Steady, every couple of weeks." (14 days), "Mentors" "Every couple of months." (60 days, the slider's cap per ADR 0010, so no longer quarterly), "Drifted" "Reconnect about once a month." (30 days). "Recently added, not called" ("Auto-updates as you add people.") is smart and carries Keep in touch; "Start from blank" ("Choose your own rhythm.") keeps the template default (2 days). Pinned by `CreateListTemplateCatalogTest` and `ListsManagerViewModelTest`.

**List Configuration (per list).**
- Template selection is kind-based: a `TemplateChoice` catalog grouped by `RuleKind` (`KEEP_IN_TOUCH`, `LATE_NIGHT`, `ENERGIZE`). See `features/rule-engine/README.md` for the semantics. The rhythm rows are one radio group, so TalkBack says which one is selected (`TemplateSelectionSemanticsTest`).
- Interval tuning is honest — the slider moves both cooldown bounds together (interval honesty; see `IntervalScaleLabelsTest`).
- The "People" section (not "Members preview": it adds and removes people) shows the true count: first 20 rows + "Showing 20 of N" with a "Show all" affordance (`MembersPreview`).
- The rename dialog on Lists and the inline rename row here both mask the name under the privacy curtain (PRIV-03, `RenameListDialogCurtainTest`); the dialog did not until 2026-10-06.
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

**Picker requirements** (defined 2026-10-05 from what the code already cites for them; PICK-09 is new; PICK-03 defined 2026-10-06, it was cited by the picker's code and no spec):
- **BULK-05: Add people from a list.** The "+" on Browse and "Add people" elsewhere open the contact picker for that list (`pick/contacts?targetListId=...`); the title says "Add people". The picker adds people to a list and, from Contact detail's orphan state, re-links a person to a phone contact. Moving and copying people between lists happen from Browse's selection bar, through its own "Move to which list?" / "Copy to which list?" sheet (see the [Browse](../page-views/browse.md) page view), not from the picker: the picker's Move and Copy modes exist only by route today, with no caller, and are a candidate for removal. Until 2026-10-05 the titles read "Add contacts" and "Move 3 contacts"; the app says "people" for the people in Orbit.
- **BULK-06: Add one person to lists.** "Add to lists" on Contact detail and in Search (one label; Search said "Add to list" until 2026-10-06) open the list picker for that person, titled "Add {name} to lists", which makes a new list inline when there are none yet.
- **PICK-01: Filter by list.** "On a list" filters the candidates to members of one of your other non-archived lists; the applied filter reads "On {list}".
- **PICK-02: Filters narrow together.** Active filters combine as AND: a contact shows only if it matches every one.
- **PICK-03: Select all N matches.** Offered whenever search or a filter narrows the list and at least one match is unselected ("Select all 14 matches"); capped at 200, above which a note says to narrow the search; never in Re-link, which picks exactly one person.
- **PICK-04: One row per person.** Avatar, name, a call line ("Last called 3 days ago · 4 calls", or "Never called" for no calls, never "0 calls"), the lists they're on, and a check mark; the "Never called" filter reads `callCount == 0` directly. Each row's three dots (or a long-press) offer "Open in Contacts", which hands the person to the phone's own address book, and Ignore, with a line saying they stay in your phone's contacts.
- **PICK-05: Search composes.** Search uses the shared `ContactSearch` matcher (name with accents folded, or phone digits) and narrows whatever the filters left.
- **PICK-06: A docked commit bar, on both pickers.** With anything selected, a bar docked under the list (not floating over it) shows the count, Clear, and the commit button ("Add 3 to Inner orbit", "Add to 2 lists"); it never hides the last row. The list picker's bar floated over its last row until 2026-10-06.
- **PICK-08: Ignored people stay out.** Ignored contacts are hidden from the picker unless "Show ignored" is on; shown, they are muted, tagged "Ignored", and offer Unignore instead of selection.
- **PICK-09: A failed read is an error, not a crash.** Either picker shows "Couldn't load your contacts" / "Couldn't load your lists" with "Try again" when a source fails (the contact picker's address-book read included), instead of an uncaught exception in `viewModelScope`. The selection survives the error.

**Cross-list propagation.**
- Calling contact X updates last-call state everywhere X appears — home, card-view, browse, widget — via Flow.
- There is no per-list last-call timestamp. One `CallEntity` per real call; every list derives its view from the same source.

**List ordering.** User-controlled. No inherent priority — lists are peers.

### Acceptance criteria

- [x] Creating a list without a name is not allowed; prompt is soft, not error-y. (Create is disabled until a template and a name exist; the "Name your list" heading is the prompt. A danger-coloured "Give your list a name" line existed in the code but could never show, since a disabled button cannot be attempted; it went in 2026-10-06.)
- [ ] Archived lists don't appear on home but remain retrievable from the Lists manager's Archived section.
- [x] Active-hours toggle uses start/end pickers, not a schedule grid. (`ActiveHoursEditor`: two time chips, each opening the time picker.)
- [ ] A call from card-view updates home's due counts and browse's rows for the same contact without a manual refresh.
- [x] Deleting a list requires confirmation; copy: "This removes the list. People stay in your contacts." (sentence case per voice.md; was lowercase until 2026-10-05; `DeleteListDialog`, reached from the Archived section and from Home's long-press menu)
- [x] Dark mode + 200% font scale pass in the screenshot gallery (every state of both screens has a preview since 2026-10-06, with the a11y and curtain audits at "None").
- [ ] TalkBack pass (needs a device; the JVM tests pin roles, labels and the curtain, not the spoken order).

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
