# orbit-lists

**Status:** in-progress
**Last reviewed:** 2026-06-09
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/lists/` (`ListsManagerScreen`/`ViewModel`, `ListConfigScreen`/`Body`/`ViewModel`, `CreateListBottomSheet`, `TemplateChoice`, `RuleTemplatePicker`, `MembersPreview`, `ActiveHoursEditor`, `SmartRuleEditor`, …); list picker: `android/app/src/main/java/app/orbit/ui/screens/picker/ListPickerScreen.kt` + `ListPickerViewModel.kt`
- Tests: `android/app/src/test/java/app/orbit/ui/screens/lists/` (`ListsManagerViewModelTest`, `ListConfigViewModelTest`, `CreateListTemplateCatalogTest`, `ActiveHoursFormatterTest`, `IntervalScaleLabelsTest`, `SmartRuleEditIntegrationTest`), `android/app/src/test/java/app/orbit/ui/screens/picker/ListPickerViewModelTest.kt`

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
- Reorder via long-press drag. Archive removes from home while preserving data. Delete requires confirmation (destructive).
- Per-row overflow actions: rename, archive, **list settings** (opens List Configuration), move up/down. The action that opens List Configuration is labeled **"List settings"** everywhere — not "Configure" — matching the home long-press menu (see `features/home/README.md`).
- These per-list actions (add people, mute/unmute prompts, list settings, archive, delete) are **also reachable via a long-press on the list tile on home**. Home is the convenience surface; Lists Manager remains the full manager. The two must stay consistent — same labels, and the same delete-with-Undo behavior. Spec: `features/home/README.md` → "List tile long-press — quick actions".

**List creation.**
- Lists Manager create (`CreateListBottomSheet`) navigates straight to List Configuration on success.
- The list picker supports inline list creation (2026-06-09 #26) — no "create a list first, then come back" detour.

**List Configuration (per list).**
- Template selection is kind-based: a `TemplateChoice` catalog grouped by `RuleKind` (`KEEP_IN_TOUCH`, `LATE_NIGHT`, `ENERGIZE`). See `features/rule-engine/README.md` for the semantics.
- Interval tuning is honest — the slider moves both cooldown bounds together (interval honesty; see `IntervalScaleLabelsTest`).
- Member preview shows true member counts: first 20 rows + "Showing 20 of N" with a "Show all" affordance (`MembersPreview`).
- Active hours (optional) — simple start/end pickers. Example: late night list active 9pm-2am.
- Per-list notification toggle (see `features/notifications/README.md`).

**Cross-list propagation.**
- Calling contact X updates last-call state everywhere X appears — home, card-view, browse, widget — via Flow.
- There is no per-list last-call timestamp. One `CallEntity` per real call; every list derives its view from the same source.

**List ordering.** User-controlled. No inherent priority — lists are peers.

### Acceptance criteria

- [ ] Creating a list without a name is not allowed; prompt is soft, not error-y.
- [ ] Archived lists don't appear on home but remain retrievable from settings.
- [ ] Active-hours toggle uses start/end pickers, not a schedule grid.
- [ ] A call from card-view updates home's due counts and browse's rows for the same contact without a manual refresh.
- [ ] Deleting a list requires confirmation; copy: "this removes the list. people stay in your contacts."
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
- Cross-list propagation is automatic because call data is stored with `contactId` only (no `listId`); every list's due computation reads the same `CallEntity` rows.

### Data model

- `ListEntity` — id, name, sortOrder, isArchived, type (`STATIC` | `SMART`), `smartRuleJson` (smart lists), `ruleTemplateId`, active hours (nullable start/end), notificationsEnabled, `ruleParamsOverrideJson`.
- `ListMembershipEntity` — many-to-many with `ContactEntity`, plus per-list schedule state (`nextDueAt`, `skipCount`).
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
