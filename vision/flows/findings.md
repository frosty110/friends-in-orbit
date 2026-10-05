# Findings

> Bugs and oddities found while rebuilding every screen for the [flow prototype](flows.md). Each one names what you would see, the mechanism in the code, and how sure I am. **All fourteen are now fixed** (2026-10-05); each section ends with what changed and the test that pins it. B14 is fixed in part: one rule conflict is left for a product decision.

**Method.** Five readers extracted each screen's behaviour from the Compose source; every item below was then checked by hand against the code at commit `15b6bfb`. Paths are relative to `android/app/src/main/java/app/orbit/`.

**Confidence labels.**
- **Confirmed in code:** the mechanism is fully visible in the source; no runtime doubt.
- **Reasoned:** follows from how the framework behaves, but nobody has watched it happen. This environment has no Android SDK, so nothing here was run on a device.

**How the fixes were verified.** An Android SDK was installed for the fix pass. Every fix has a unit test that runs on the JVM (Robolectric where Android classes are involved), and the full unit suite, ktlint, the debug build and the instrumented-test compile were run. Instrumented Compose tests were compiled but not run on a device or emulator; B7's was additionally run once on the JVM through a temporary Robolectric setup, against both the old and the fixed code.

**IDs.** `B1` to `B14` are local handles for this file and the prototype, where each bug also shows on its screen. Screen IDs (`S11` and so on) match [flows.md](flows.md).

---

## Summary

| ID | Severity | Screen | In one line | Status |
|---|---|---|---|---|
| B1 | High | S10, S11, S13, S31 | Smart lists never surface anyone, and converting one leaves it with no cadence | Fixed |
| B2 | High | S20, S32 | Re-link adds people to an unrelated list, or fails; it never re-links | Fixed |
| B3 | High | S20, S13 | An indefinite pause cannot be undone once its snackbar is gone | Fixed |
| B4 | High | S31, S04 | With active hours set, your nudge days are ignored and "nudges off" keeps nudging | Fixed |
| B5 | Medium | S30 | List templates promise different rhythms but all create the same 2-day list | Fixed |
| B6 | Medium | S05 to S07 | First run can create duplicate first lists | Fixed |
| B7 | Medium | S13 | Tapping or long-pressing a Browse row does nothing | Fixed (confirmed by test first) |
| B8 | Medium | S07 | Removing someone during first run has no undo, and later messages stall | Fixed |
| B9 | Low | S20 | The "Usually" stat on Contact detail is always blank | Fixed |
| B10 | Low | S30, S10 | Deleting a list has Undo on Home but not on Lists | Fixed |
| B11 | Low | S11 | The privacy curtain hides the list name but not the person's name on the card | Fixed (card face only) |
| B12 | Low | S40, S41 | The ignored count can disagree with the Ignored screen | Fixed |
| B13 | Low | S13 | Bulk pause says "Paused 3 contacts for indefinitely" | Fixed |
| B14 | Low | S13, S22, S40 | Three screens break the app's own accent and phone-icon rules | Fixed in part |

---

## High

### B1 · Smart lists never surface anyone · **Confirmed in code**

**What you see.** Create "Recently added, not called". Its List settings page lists matching people. Everywhere else it is empty: Home shows "No one yet" and "No one up next", Card view says "No one is in this list yet.", Browse says "No one here yet." Convert it to a static list and it still surfaces no one, because the Cadence section now shows nothing selected.

**Mechanism.**
1. The smart template is created with no cadence: `ruleKind = null` (`ui/screens/lists/TemplateChoice.kt:94`), so `ruleTemplateId` is null (`ui/screens/lists/ListsManagerViewModel.kt:232`).
2. Smart membership is never stored. It is only projected inside List settings (`ui/screens/lists/ListConfigViewModel.kt:133`) and snapshotted once at convert time (`data/repository/ListRepositoryImpl.kt:153`).
3. Every surface reads stored membership rows and needs a cadence. Card view returns "no members" when there are no rows (`domain/usecase/SurfaceNextUseCase.kt:104`) and "nothing eligible" without a cadence (`:110`). Browse and Home "Next up" return an empty queue without a cadence (`domain/usecase/SurfaceQueueUseCase.kt:83`). Home's member count also comes from rows (`ui/screens/home/HomeViewModel.kt:147`).
4. Convert writes the rows but never sets a cadence (`data/repository/ListRepositoryImpl.kt:148-176`), and List settings only offers Cadence on static lists (`ui/screens/lists/ListConfigBody.kt:261`), so a smart list cannot be given one before converting.

**Fix direction.** Decide whether smart lists are meant to surface people. If yes, give them a cadence at creation and have surfacing evaluate the rule (or materialise rows). Either way, convert should carry a default cadence over.

**Fixed.** Smart lists now surface people like static ones. `data/feed/SmartListMembershipSync.kt` (started from `OrbitApp`) keeps each smart list's stored rows equal to its rule's matches: a new match is due now, and someone who stops matching leaves the list. A smart list with no cadence gets Keep in touch, the smart template carries it from creation, List settings shows Cadence for smart lists, and convert sets Keep in touch when the list had none. Pinned by `SmartListMembershipSyncTest` and `ListConfigViewModelTest`.

### B2 · Re-link writes to the wrong list · **Confirmed in code**

**What you see.** On a contact deleted from the phone, Re-link opens "Add contacts". Picking people either fails with "Couldn't save that" or adds them to some unrelated list. The orphaned contact stays orphaned.

**Mechanism.** The nav call passes the *contact's* id as the target *list* id with `mode = "relink"` (`nav/OrbitNavHost.kt:231`). The picker parses that id as a list id and treats any unknown mode as Add (`ui/screens/picker/ContactPickerViewModel.kt:128-136`). Commit inserts memberships with `listId` set to the contact's id (`:339-353`). Memberships are foreign-keyed to lists (`data/entity/ListMembershipEntity.kt:20-25`), so if no list has that number the insert fails, and if one does (ids start at 1 in both tables) people land in it. Nothing in the picker links a phone contact to the orphan.

**Fixed.** Re-link opens the picker in a Relink mode for that contact (`Routes.relinkContact`), with single selection and only live phone contacts as candidates. Committing merges the picked contact into the orphan (`domain/usecase/RelinkContactUseCase.kt`, CONTACT-07): the orphan keeps its id and history, takes the phone contact's number, name and photo, and gains its calls, notes, numbers and lists; the emptied row is deleted. "Re-linked to {name}" carries an Undo that splits them back exactly. Pinned by `RelinkContactUseCaseTest` (real in-memory Room, including a following contacts sync), `ContactPickerViewModelTest` and `RoutesTest`.

### B3 · An indefinite pause is permanent · **Confirmed in code**

**What you see.** Pause someone "Indefinite - until you unpause". Once the snackbar is gone there is no Unpause anywhere: not in Contact detail's menu, not in Browse's long-press menu. A timed pause cannot be ended early either.

**Mechanism.** The only unpause control is the banner, and it shows only for a timed pause that has already expired and never for an indefinite one (`ui/screens/contact/ContactDetailViewModel.kt:268-273`). The comment there says indefinite pauses are cleared "through the overflow sheet flow", but the overflow holds View all calls, Pause, and Ignore (`ui/screens/contact/ContactDetailScreen.kt:280-290`), and Browse's row menu holds Call, Select, Pause, Ignore (`ui/screens/browse/BrowseListScreen.kt:729-733`). The only other `setPausedUntil(.., null)` call is the snackbar's Undo. The workaround is to pause again for 1 week and wait.

**Fixed.** Contact detail's overflow and Browse's quick actions offer Unpause whenever the person is paused, and Contact detail shows "Paused until 12 Oct" or "Paused until you unpause" under the number. Pinned by `ContactOverflowMenuTest`, `BrowseRowMenuTest`, `ContactDetailViewModelTest` and `BrowseViewModelTest`.

### B4 · Active hours override your nudge days · **Confirmed in code**

**What you see.** Set a list to nudge on weekdays at 10am and give it active hours of 9am to 5pm. It nudges every day, at 9am and at 10am. Turn every day off ("No days selected - nudges off") and it still nudges daily. Onboarding promised "One quiet nudge per list per day" (`ui/screens/onboarding/OnboardingPermNotificationsScreen.kt:50`).

**Mechanism.** The scheduler always goes through `effectiveSchedule` (`notify/NudgeScheduler.kt:83`). When active hours are set it adds the active-hours start as a time and merges all seven days into the schedule (`:178-191`). A schedule has one set of days shared by every time, so your own times also fire every day. The comment says only the added slot was meant to fire daily.

**Fixed.** Active hours no longer touch your days or add a second nudge. The window start is added as a time only when every time you chose falls outside the window (otherwise the list could never nudge), and only on your days; "nudges off" stays off; changing active hours reschedules at once (`notify/NudgeScheduler.kt`, `effectiveSchedule`). Pinned by `NudgeSchedulerEffectiveSlotsTest` and `ListConfigViewModelTest`.

---

## Medium

### B5 · Templates that all do the same thing · **Confirmed in code**

**What you see.** "Inner orbit: Closest people, called often", "Family: Steady, longer cadence", "Mentors: Quarterly check-ins", and "Drifted" all create the same list: Keep in touch, every 2 days. Quarterly is not reachable at all; the interval slider stops at 60 days.

**Mechanism.** All four use `RuleKind.KEEP_IN_TOUCH` (`ui/screens/lists/TemplateChoice.kt:46-84`) and creation writes `ruleParamsOverrideJson = null` (`ui/screens/lists/ListsManagerViewModel.kt:249`), which means the 48-hour default. The slider range is `1f..60f` (`ui/screens/lists/ListConfigBody.kt:445`).

**Fixed.** Each template writes its own Keep in touch interval at creation: Inner orbit 7 days, Family 14, Drifted 30, Mentors 60. ADR 0010 caps the interval at 60 days, so Mentors now promises "Every couple of months." instead of "Quarterly check-ins." Pinned by `CreateListTemplateCatalogTest`.

### B6 · Duplicate first lists · **Confirmed in code (back-press path reasoned)**

**What you see.** If the app dies while you are on "Make your first list", relaunching sends you back to the sync screen, and going through Preview creates a second list. The first one is still there. System back from "Make your first list" does the same.

**Mechanism.** Resume maps the first-list step to Sync because the list id is not saved (`AppViewModel.kt:153`). Both Preview buttons create a new list every time they run (`nav/OrbitNavHost.kt:349-374`, helper at `:492-523`).

**Fixed.** Onboarding remembers the list it is building until Done (`ui/screens/onboarding/OnboardingListStarter.kt`). Returning to Sync continues straight into that list, and Preview's buttons reuse it (a typed name is kept, members are only added); "Add another list" is the one deliberate new list. Pinned by `OnboardingListStarterTest` and `OnboardingDoneViewModelTest`.

### B7 · Browse rows ignore taps · **Reasoned, then confirmed by test**

**What you see (expected).** Tapping a person in Browse does not open them, and long-pressing does not open quick actions, so multi-select is unreachable. The phone icon still works.

**Mechanism.** Each row is a `combinedClickable` wrapper (`ui/screens/browse/BrowseListScreen.kt`, around `:455-487`) holding a `BrowseRow` whose own root carries `.clickable(onClick = onTap)` (`ui/components/BrowseRow.kt:82`), passed an empty `onTap = {}` (`BrowseListScreen.kt:497`). In Compose the innermost clickable consumes the press, so the wrapper never sees it. Worth one tap on a device before fixing; if confirmed, drop the inner `clickable` when the parent owns the gesture.

**Fixed, after confirming it.** A temporary Robolectric Compose test ran the old row inside Browse's wrapper: the tap never arrived ("tap reaches the Browse wrapper expected:<1> but was:<0>"). The row now takes `onTap = null` to mean "the caller owns the gesture" and adds no clickable of its own; the same test passes, and Global Search rows still open on tap. Kept as `androidTest/.../ui/components/BrowseRowGestureTest.kt` (compiled, not run on a device).

### B8 · First run swallows its undo · **Confirmed in code**

**What you see.** Removing a person in "Make your first list" shows no snackbar and offers no Undo.

**Mechanism.** The screen collects snackbar events into a `SnackbarHostState` (`ui/screens/onboarding/OnboardingFirstListScreen.kt:74-84`) but never places a `SnackbarHost`. `showSnackbar` then suspends with nothing to display or dismiss it, so the first event ("Removed {name}" with Undo, `ui/screens/lists/ListConfigViewModel.kt:400`) stalls every later one.

**Fixed.** `OnboardingScaffold` takes the step's snackbar host and shows it above the CTAs, and the first-list step handles Undo the way List settings does, so "Removed {name}" appears and Undo puts them back. The host is UI wiring with no JVM test; it compiles and was not run on a device.

---

## Low

### B9 · "Usually" is always blank on Contact detail · **Confirmed in code**

The label is only computed by the `withCallPatterns` overlay, which only Card view applies (`ui/screens/card/CardViewViewModel.kt:270`). Contact detail uses the base mapping, where it is empty (`data/mappers/ContactMapper.kt:39`), and renders the placeholder (`ui/screens/contact/ContactDetailScreen.kt:711`). The card shows "Mornings" for the same person.

**Fixed.** Contact detail now applies the same call-pattern overlay with the device time zone, so "Usually" shows the same answer as the card. Pinned in `ContactDetailViewModelTest`.

### B10 · Delete has Undo on Home, not on Lists · **Confirmed in code**

Home emits "List deleted." with Undo and defers the delete (`ui/screens/home/HomeViewModel.kt:268-272`). Lists emits "List deleted." with no action (`ui/screens/lists/ListsManagerViewModel.kt:171`). Same action, two levels of safety.

**Fixed.** Lists now defers the delete behind "List deleted." with Undo, the same as Home (`ui/screens/lists/ListsManagerViewModel.kt`). Pinned in `ListsManagerViewModelTest`.

### B11 · The curtain misses the card face · **Confirmed in code**

With the app in the background, Card view's title becomes "Contact" (`ui/screens/card/CardViewScreen.kt:201`) but the face still renders the real name and initials (`:642`, `:654`). Also from the readers, not re-checked by hand: the phone number on Contact detail and "from {list}" in Call history are not masked, and notifications and widgets ignore the curtain.

**Fixed for the card face.** Under the curtain the face shows "Contact" and initials derived from it, like the app bar (`androidTest/.../ui/screens/card/CardFaceCurtainTest.kt`, compiled, not run on a device). **Not fixed:** the reader-reported gaps above were never confirmed and are still open.

### B12 · Ignored count and Ignored list disagree · **Confirmed in code**

Settings counts every ignored contact (`ui/screens/settings/SettingsViewModel.kt:192`); the Ignored screen hides archived ones (`ui/screens/settings/ignored/SettingsIgnoredViewModel.kt:58-60`). "1 ignored" can open onto "No ignored contacts".

**Fixed.** The ignored query itself now excludes archived contacts (`data/dao/ContactDao.kt`, `observeIgnored`), so the count and the screen read the same rows and the screen's own filter is gone. Pinned in `ContactDaoIgnoredTest`.

### B13 · "for indefinitely" · **Confirmed in code**

Bulk pause builds "Paused {N} contacts for {label}" (`domain/usecase/BulkPauseUseCase.kt:68`) and the indefinite label is "indefinitely" (`domain/model/PauseDuration.kt:24`). The single-person path words it correctly.

**Fixed.** Pause durations carry a snackbar phrase ("for 1 week", "for 1 month", "indefinitely") used by both paths. Pinned in `PauseDurationTest` and `BulkPauseUseCaseTest`.

### B14 · The app breaks its own design rules · **Confirmed**

`rules.md` Design 6 allows one phone icon per screen and Design 5 one accent element. Browse and Search put an accent phone icon on every row (visible in `vision/03-browse-list/actual-browse.png`). Settings shows two primary "Sync now" buttons (`ui/screens/settings/CallSyncStatusRow.kt:100`, used by both sync rows).

**Fixed in part.** Row phone icons in Browse and Search are muted instead of accent, and both "Sync now" buttons are Secondary. **Open, needs a product decision:** Design 6 allows one phone icon per screen, but the Browse and Search specs call for one on every row. Nothing was changed for that.

---

## Checked and not a bug

- **Rhythm strip colours** looked hardcoded; they are theme tokens in `ui/theme/Color.kt:52-55`.
- **The "Recently added" picker threshold** looked unused; the picker reads it (`ui/screens/picker/ContactPickerViewModel.kt:702`).

## Docs that disagree with the code

Listed separately in [flows.md, Docs vs code](flows.md#docs-vs-code).
