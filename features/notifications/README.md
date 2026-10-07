# notifications

**Status:** shipped
**Last reviewed:** 2026-10-07 (NOTIF-16, the notification after a call; time of day, LIST-25; on 2026-10-06, acceptance boxes tied to the tests that pin them)
**Ground truth:**
- The notification after a call (NOTIF-16, 2026-10-07): `notify/PostCallNotifier.kt` (when it posts and when it goes away), `notify/PostCallNotification.kt` (what it says, its lock-screen version, its tap), `notify/AppForeground.kt` (whether Orbit is on screen), the hook in `calllog/CallLogSyncWorker.kt`, and the call-log trigger that wakes Orbit for it (`calllog/CallLogTriggerWorker.kt`, armed by `ContentObserverController`; `features/call-detection/README.md`). It reads NOTE-05's `data/repository/WaitingCalls.kt`, as Home does. Tests: `PostCallNotifierTest` (Room in memory, a real DataStore, Robolectric's notification manager), `CallLogSyncWorkerTest` (the hook), `CallLogTriggerWorkerTest`, `CopyAuditTest`, `NotificationIdsTest`.
- Code: `android/app/src/main/java/app/orbit/notify/`, the full notification system. `OrbitNotifications` registers the `orbit.list_prompt` channel ("List nudges") and, since 2026-10-07, `orbit.after_call` ("After a call", NOTIF-16), both DEFAULT importance, on startup; the retired `orbit.digest`, `orbit.incoming_followup`, and `orbit.incoming_followup.v2` channels are deleted on every cold start. `DailyDigestWorker` and `IncomingFollowUpWorker` were both deleted (ADR 0009: notifications are pull, never push; amended 2026-10-07 for NOTIF-16). One notification worker remains: `ListPromptWorker`. The notification after a call has no worker of its own: the call-log sync posts it through `PostCallNotifier`.
- `NudgeSchedule`: `@Serializable` per-list schedule model (days of week × times of day); stored as JSON in `ListEntity.nudgeScheduleJson` (schema v12 / `MIGRATION_11_12` backfill). `NudgeScheduler` (@Singleton) enqueues self-re-enqueueing `OneTimeWork` per list via `setInitialDelay` + `ExistingWorkPolicy.REPLACE`. `ListPromptWorker` (@HiltWorker) implements a 5-gate `doWork`: notifications-enabled check, DND check, list-muted check, due-count check, active-hours check; then posts the nudge built by `NudgeNotification`. Title = list name. Body = the list's next person by first name ("Kai is ready when you are. Want to call?", `NotificationCopy.nudgeNamedBody`) with their face and a Call action (NOTIF-14), or, when no one can be named or the name was just said (NOTIF-15, `nudgeSubject`), the name-free `NotificationCopy.nudgeBody` ("Someone in {list} is ready when you are. Want to call?" / "A few people in {list} are ready when you are. Start with one?"; the exact due count is deliberately never shown). Every nudge carries a name-free lock-screen version (NOTIF-13). Re-enqueues in `finally` block.
- The face: `ui/components/AvatarBitmaps.kt` draws the app's avatar as a bitmap (address-book photo, else the monogram from `avatarInitials` on the palette colour `OrbitTones.avatarPalette` gives the name). Who was last named: `AppPrefs.nudgeLastNamedContactId` (an id per list, never a name).
- **Missed / incoming calls fire NO notification** (ADR 0009, 2026-07-03). A missed inbound call instead surfaces the contact in the deck: the rule engine sets `nextDueAt` to the moment they rang (`KeepInTouchEngine` step 3c), so a call-back rises up the deck the longer it waits. `IncomingFollowUpWorker` and `FollowUpDedupStore` were deleted.
- Tap navigation: the nudge's body carries a `NAVIGATE_TO` extra (`nav/AppLinks.kt`) read in `MainActivity` (cold start in `onCreate`, warm in `onNewIntent`); `OrbitNavHost` routes the tap to card view.
- List lifecycle hooks: deleting or archiving a list cancels its nudge chain (`ListsManagerViewModel`); unarchiving re-enqueues. Cold-start `OrbitApp.reAnchorAll()` re-anchors all chains.
- Tests: `CopyAuditTest` (voice gate, golden strings incl. the named nudge and the lock-screen copy), `NotificationIdsTest`, `NudgeScheduleTest`, `NudgeScheduleNextSlotTest`, `NudgeSchedulerEffectiveSlotsTest`, `NudgeSubjectTest`, `ListPromptWorkerTest` (gates, plus NOTIF-13/14/15 against a real `SurfaceNextUseCase` and `AppPrefs`). Renders of the named, name-free and lock-screen nudge in light and dark: `PlatformSurfacesGalleryTest` (`-Pscreenshots`). `Migration11To12Test` / `Migration12To13Test` (androidTest; device-run deferred per phase precedent).

---

## Product

### Why it exists

The app's job is to reduce activation energy. Notifications are one of two vectors that bring the suggestion to the user (the other is the widget). Without them, the user must remember to open the app — which defeats the point.

**Non-negotiable:** never per-person nags. Notifications surface opportunity, never absence. And notifications are **pull, never push**: scheduled reminders the user owns (the per-list nudge), never a ping in reaction to an event; a real-world event (a missed call, a new contact) changes in-app surfacing, never fires a notification. Principle-level per `features/_foundations/mission.md` (principles 1 + 6), `features/_foundations/voice.md`, and **ADR 0009** (`0009-notifications-pull-not-push.md`). A nudge may hand over one person, its list's suggestion; that keeps it a per-list reminder, and it never repeats a name (NOTIF-14, NOTIF-15).

**The one exception: after a call (NOTIF-16, 2026-10-07).** The owner asked whether Home's "Add a note while it's fresh" banner could be a notification (`vision/flows/owner-review-2026-10-07.md`, decision 12), and decided it should be. It reacts to an event, so ADR 0009 is amended to allow exactly this one: it is about a call the user just had, never about absence or a call they missed; it asks once per person per call and goes away by itself; and it has its own channel, so it can be turned off without touching the nudges. Missed calls still fire nothing.

### User story

As a user, my lists nudge me on the schedule I set. A nudge hands me the person the list would show me first, "Kai is ready when you are", with their face and a Call button, so one tap takes me to the call. If I let it pass, the next nudge does not say Kai's name again. Nothing pings me because of an event: a missed call surfaces that person inside the app, not as a notification. And my lock screen says only that someone is ready, never who or from which list.

### Behavior

**Current state (2026-07-03).** Per-list nudge schedules are fully shipped; incoming-call follow-up notifications were removed (ADR 0009). The onboarding promise ("one quiet nudge per list per day") is honored: `ListPromptWorker` fires once per configured day × time slot when at least one list member is due. `DailyDigestWorker` and `IncomingFollowUpWorker` are both deleted; their legacy work names / channels are cancelled and deleted on every cold start to clean up older installs.

**Daily digest.** RETIRED 2026-04-28 (whole-app review). The legacy `DailyDigestWorker` was deleted. The `orbit.daily_digest` unique work name is cancelled on every `OrbitApp.onCreate` to clean up any remaining scheduled instances on existing installs.

**Active hours and nudge days** (2026-10-05). A list's nudge fires on the days and times chosen for it, one notification per list per slot, never per contact. Optional active hours only gate when that nudge may post: they never add days or a second daily nudge. The window start is added as an extra nudge time only when every chosen time falls outside the window (otherwise the list could never nudge), and only on the chosen days. "No days selected" (nudges off) stays off with a window set. Changing active hours reschedules the list's nudge immediately. Before, a window merged all seven days into the schedule and added a second daily slot, so weekday nudges fired every day and "nudges off" kept nudging. As built: `NudgeScheduler.effectiveSchedule`, sharing `isInActiveWindow` with the worker's active-hours gate; pinned by `NudgeSchedulerEffectiveSlotsTest` and `ListConfigViewModelTest`.

**Time of day** (2026-10-07, LIST-25 in `features/orbit-lists/README.md`). List settings no longer edits active hours as "Always active" with a start and an end time: the user picks one part of the day, "Any time", "Mornings" (7am to noon), "Afternoons" (noon to 5pm), "Evenings" (5pm to 9pm) or "Nights" (9pm to 7am), and the part is stored as the same window on the same two columns, so the gate, the slot at the window's start and everything above are unchanged. The starts are chosen for that slot: when none of a list's nudge times falls inside its part, the nudge comes at the part's start, so mornings start at 7am rather than earlier. Nights crosses midnight, which `isInActiveWindow` already handles (NOTIF-03); `TimeOfDayMappingTest` pins each part's window, the gate on both sides of midnight and the added slot. A window set before this that matches no part shows as "Custom" and keeps working exactly as stored until the user picks a part.

**Smart lists nudge too** (2026-10-05). A smart list's rule matches are stored as members with a due count (`SmartListMembershipSync`, see `features/orbit-lists/README.md`), so the due-count gate treats it like a static list.

**The nudge hands over the person** (2026-10-05, UX rubric plan item 3.1). Until now a nudge said "Someone in Family is ready when you are" and nothing more, so acting on it meant opening Orbit to find out who: the friction the app exists to remove. The rubric asks for the person's name, face and a direct Call (D9), and one tap from a notification to that person's call (D1). The shape, decided from the principles rather than copied from per-person reminder apps:

- *Still one nudge per list per slot.* Nothing about when a nudge fires changes (ADR 0009 holds: pull, scheduled, muteable). The nudge is the list's reminder; what it now carries is the list's suggestion, the person Card view would show first (`SurfaceNextUseCase`), so the nudge and the deck it opens agree. Mission principle 1 already says notifications "bring the suggestion to the user", and the suggestion is a person.
- *Opportunity, never absence.* "Kai is ready when you are. Want to call?" uses the same invitation as the name-free nudge; it never says how long it has been.
- *A name is said once.* Naming the same person nudge after nudge until they are called would be the per-person nag principle 6 forbids. So the nudge after one that named Kai goes out without a name while Kai is still next, and names whoever is next once the deck moves (a call, a Later, a Sooner). Ignoring a nudge is not a "no", but it never earns a repeat. (Naming the second person instead was rejected: the body tap opens the deck, which would then lead with someone else.)
- *Only Call dials.* The Call action opens the dialer with the number filled in; the user places the call there (PRIV-05, CARD-01). The body tap opens the list's deck, as before.

**Lock screen** (2026-10-05, UX rubric gate G6). Every nudge is private and carries a public version with fixed words: "Someone is ready when you are", "Want to call?". No person, no list name, no note, no face, no action. Android shows the public version on a secure lock screen whenever the user's lock-screen setting hides sensitive content (globally, or for Orbit's "List nudges" channel). Where the user lets Android show all notification content, the full nudge shows: that is the user's system choice, and Orbit marks the content sensitive so the system can honour it. See `features/privacy-and-lock/README.md`, "Surfaces outside the app".

**Opening from a nudge** (2026-10-05). Tapping a nudge while Orbit was closed now opens the list's deck. Before, `MainActivity` read the tap's route in a property initialiser, which runs before Android attaches the launch intent, so the route was always null on a cold start and the tap opened Home; only taps that reached an already-running Orbit navigated. (Found by reading the code, and confirmed with a throwaway Robolectric activity: a property initialiser sees no intent, `onCreate` sees the route. Not reproduced on a device.)

**After a call** (NOTIF-16, 2026-10-07). When a call worth a note ends while Orbit is not on screen, one notification per person asks "How was your call with Kai?" / "Add a note while it's fresh." and a tap opens the page for writing about that call (NOTE-04 in `features/contact-detail/README.md`), over Home, so Back returns there.

- *Which calls.* Each call a call-log sync newly inserts that waits for a note under NOTE-05 (connected, a minute or more, either direction, someone on a list who is not ignored, no note since, not dismissed on Home) and ended within the last 2 hours. Not for a first import or a full resync, and an old row is never fresh, so neither floods the shade. One per person: their latest waiting call, and a newer call replaces the older one's notification.
- *When.* Only while Orbit is not in the foreground (on screen, Home's stack says the same thing), and only when the gates a nudge respects pass: notifications allowed for Orbit, this channel on, Do Not Disturb off (NOTIF-01, NOTIF-06).
- *Waking Orbit.* No new permission: Orbit already reads the call log. A WorkManager content URI trigger on the call log (`CallLogTriggerWorker`) starts Orbit when the log changes even if its process was killed during the call, and runs the ordinary sync; `features/call-detection/README.md` has why both it and the content observer exist.
- *Lock screen.* `VISIBILITY_PRIVATE` with a public version from fixed words: "How was your call?" / "Add a note while it's fresh.", no name (NOTIF-13's rule). The importance is the nudge channel's (DEFAULT).
- *Going away.* As soon as the note page for that person opens, however it was reached (the card after a call, Home's stack, the notification itself), because the page is the question the notification asks (`PostCallNotification.cancel`). When a note is saved for that person (on the note page or their own page) or the call is dismissed on Home, because `PostCallNotifier` watches the waiting calls for the life of the process and cancels what no longer waits. A day after the call started it times out by itself: the day passing changes nothing that watch sees, so the notification carries its own timeout. A tap dismisses it too.
- *Turning it off.* Settings has no list of notification kinds with their own switches (it has the Notifications permission row, SET-14), so there is no in-app toggle: the "After a call" channel ("A prompt to write a note after a call with someone on your lists.") is the switch, in Android's settings for Orbit.

**Incoming / missed calls — no notification** (ADR 0009). A missed inbound call surfaces the contact in the deck (due since they rang — `KeepInTouchEngine` step 3c), not as a ping. Orbit never notifies you because of an event; the miss shows up in-app, silently.

**DND respected.** Android system DND suppresses all app notifications.

**Per-list opt-out.** Each list has its own notification toggle in list config.

**Voice enforcement.** Every notification body passes through a central formatter that applies voice rules (sentence case, no exclamation, no emoji, no "haven't called" framing).

**Never:** per-person nags, streak reminders, shame framing, re-engagement prompts.

### Requirements

Defined 2026-10-05; NOTIF-01 to NOTIF-12 record what the code already cites, NOTIF-13 to NOTIF-15 are new.

- **NOTIF-01: Permission is checked when a nudge fires.** `POST_NOTIFICATIONS` is asked for on Android 13+ before the first schedule; at fire time the worker checks the system permission and Orbit's app-level switch (`areNotificationsEnabled`) and skips, still re-enqueuing, when either is off.
- **NOTIF-03: Active hours bound when a nudge may post.** A window that spans midnight (22:00 to 02:00) counts both sides; outside the window the slot is skipped and the next one enqueued.
- **NOTIF-05: No shame framing.** Every notification string comes from `NotificationCopy`, whose words live in `res/values/strings_notify.xml` (since 2026-10-05, so a nudge can be translated; `NotificationCopy` picks the sentence and returns a `UiText` the builder resolves with its Context), and passes `CopyAuditTest`'s forbidden-pattern audit ("haven't called", "overdue", "streak", "due", "caught up" and others, and now the em dash). The nudge schedule editor's words ("Add time", "Nudges paused") moved to List settings' `strings_lists.xml` and are audited with them.
- **NOTIF-06: Do Not Disturb is respected.** A nudge posts only when the system allows all interruptions.
- **NOTIF-08: No daily digest.** The digest worker is gone and its legacy unique work is cancelled on every cold start.
- **NOTIF-09: Notification copy is pinned.** `CopyAuditTest` holds golden strings for every nudge text, resolved against the real English resources under Robolectric.
- **NOTIF-10: A list's nudge schedule is stored with the list.** Days of the week by times of day, as JSON on the list row, round-tripping exactly.
- **NOTIF-11: A list's nudge lives and dies with the list.** Archiving or deleting a list cancels its nudge chain; unarchiving re-enqueues it.
- **NOTIF-12: One self-re-enqueueing chain per list.** Each list's nudge is a one-time work request that enqueues the next slot when it finishes, gate or no gate (ADR 0004).
- **NOTIF-13: Nothing private on the lock screen.** Every notification Orbit posts is `VISIBILITY_PRIVATE` with a public version built from constants only: "Someone is ready when you are" and "Want to call?", with no person name, list name, note, face or action. `NudgeNotification.publicVersion`; pinned by `ListPromptWorkerTest`.
- **NOTIF-14: The nudge hands over the list's next person.** When the list's deck has someone on top, the nudge names them by first name ("Kai is ready when you are. Want to call?"), shows their face as the large icon (address-book photo, else the app's initials avatar, a circle), and offers "Call {first name}", which opens the dialer (`ACTION_DIAL`, `FLAG_IMMUTABLE`, no `CALL_PHONE`). The action is left off on a device with no dialer. The body tap opens that list's deck, where the same person is on top.
- **NOTIF-15: A name is said once.** If the previous nudge for a list named the person who is still next, this nudge goes out without a name, a face or a Call action. A name returns when someone else is next. Orbit keeps one contact id per list to know (`AppPrefs.nudgeLastNamedContactId`).
- **NOTIF-16: The notification after a call.** After a call-log sync that is not a first import or a full resync, for each newly inserted call that waits for a note (NOTE-05) and ended within the last 2 hours, and only while Orbit is not in the foreground, Orbit posts one notification per person: "How was your call with {first name}?" / "Add a note while it's fresh.", opening the post-call note page for that call (NOTE-04). Its own channel, "After a call" (`orbit.after_call`, DEFAULT importance, the nudge's), so it can be turned off alone; the nudge's gates (notifications allowed, channel on, Do Not Disturb off); a name-free lock-screen version ("How was your call?"). It is cancelled once that person has no call waiting: a note saved anywhere, the call dismissed on Home, or a day gone. A WorkManager content URI trigger on the call log wakes Orbit for it with no new permission. The one event-driven notification, by the 2026-10-07 amendment to ADR 0009.

### Acceptance criteria

- [ ] A list nudge fires within ±15 minutes of its scheduled slot (doze-compatible tolerance per ADR 0004).
- [x] Content passes voice rules automatically via formatter (`CopyAuditTest.copy_hasNoForbiddenPatterns`).
- [x] Per-list opt-out suppresses that list's nudge without affecting other lists (`ListPromptWorkerTest.worker_returnsSuccess_whenListMuted_andReEnqueues`).
- [x] The nudge's route wins over any action on the launch intent and is handed to the nav host as is (`AppLinksTest.landingFor_extraWinsOverAction`, with `landingFor_callNext_beforeOnboarding_isNothing`, `landingFor_search_opensGlobalSearch` and `landingFor_unrelatedAction_isNothing` for the other launch shapes).
- [ ] The nudge tap deep-links to that list's card view, with Orbit open or closed (device check: the cold-start timing, `onCreate` reading the intent, is not pinned on the JVM).
- [ ] No notification fires in reaction to an event — missed/incoming calls surface in-app only (ADR 0009).
- [ ] `POST_NOTIFICATIONS` requested on Android 13+ before first schedule.
- [ ] DND respected — manually verify by toggling system DND.
- [x] Deleting a list cancels its scheduled work (`ListsManagerViewModelTest.deleteList_defers_the_purge_until_commit_then_cancels_nudge_chain`, which asserts the cancel on commit).
- [ ] With "hide sensitive content" on, a locked phone shows only "Someone is ready when you are" (NOTIF-13). Manual, on a device.
- [ ] A named nudge's Call opens the dialer with the number and places no call (NOTIF-14). Manual, on a device.
- [x] Two nudges in a row for an unchanged list name the person once (NOTIF-15; `ListPromptWorkerTest.nextNudge_goesOutWithoutAName_whenTheSamePersonIsStillNext`).
- [x] A fresh call worth a note, synced while Orbit is closed, posts one notification per person by first name, whose tap opens the note page for that call and whose lock-screen version names no one; nothing posts in the foreground, for a call that ended more than 2 hours ago, under a minute, with an ignored or list-less person, for a first import or full resync, for a call already there before the pass, already dismissed, or with the channel off, notifications off or Do Not Disturb on; saving a note or dismissing on Home cancels it, the shade follows the waiting calls by itself, and it times out a day after the call started (NOTIF-16; `PostCallNotifierTest`). The sync hands it the new calls and a failure there never fails the sync (`CallLogSyncWorkerTest`).
- [ ] With Orbit's process killed during a call, the notification appears after hang-up (NOTIF-16). Manual, on a device: the content URI trigger's wake-up is the platform's part and is not run on the JVM.

### Not in scope

- Per-contact notifications. Forbidden by mission principles. (A nudge that names its list's next person is still one per-list reminder: NOTIF-14.) The notification after a call (NOTIF-16) is about one person's call, not about a person; it is the one exception, recorded in ADR 0009's amendment.
- Reminder chains ("you still have 3 people due"). No escalation.
- Push notifications from a server. Fully local.
- Inline reply, and Later or Sooner from the notification. The one action is Call. Later from the shade would change the deck with no visible Undo, which Card view always offers (UX rubric D5).

### Open product questions

- Should list nudges become opt-in rather than default-ON? Default-ON is retained per mission principle 1 and ADR 0009 (a nudge is a muteable scheduled reminder, not an event push), but the "user-defined" reading is loosest here — revisit if default nudging feels pushy on dogfood.
- **Lock-screen default.** NOTIF-13 relies on Android's lock-screen setting. Many phones ship set to show all notification content (reasoned from the platform's defaults, not checked on a device), and then a locked phone shows the list name and the person, as it showed the list name before. Options: keep it (the user's system choice decides, as for messaging apps); make nudges `VISIBILITY_SECRET` (nothing on a secure lock screen at all); or point to the system switch from Settings. Owner's call; the privacy spec's promise is written to match what is built.
- Should a nudge offer Later, with an Undo on the notification itself? Not built (see Not in scope).

_(Resolved: the daily digest and the incoming-call follow-up notification were both removed — see ADR 0009. Missed/incoming calls now surface in-app, never as a notification.)_

---

## Technical

### Architecture

As built: `OrbitNotifications` registers two channels (`orbit.list_prompt` for nudges, `orbit.after_call` for NOTIF-16) and deletes the retired channels (`orbit.digest`, `orbit.incoming_followup`, `orbit.incoming_followup.v2`) on every cold start. The self-re-enqueueing `OneTimeWork` pattern (ADR 0004 amendment) drives the nudge worker: `setInitialDelay` + `ExistingWorkPolicy.REPLACE`, no `BOOT_COMPLETED` receiver, doze-tolerant. `NudgeScheduler` is `@Singleton`-injected; `ListPromptWorker` is a `@HiltWorker` routed through `HiltWorkerFactory`. Tap navigation uses a `NAVIGATE_TO` String extra on the `PendingIntent` (`FLAG_IMMUTABLE`), built by `AppLinks.openRoute` and read in `MainActivity` and dispatched via `OrbitNavHost`.

The post (2026-10-05): the worker asks `SurfaceNextUseCase` for the list's head, `nudgeSubject` decides whether to name them against `AppPrefs.nudgeLastNamedContactId`, and `NudgeNotification.build` assembles the nudge: category `REMINDER`, the theme's accent as the notification colour, `VISIBILITY_PRIVATE` plus `publicVersion`, and for a named nudge the large icon (`AvatarBitmaps.photo`, else `AvatarBitmaps.initials` in the palette the app's theme gives the name, in the mode the app is showing) and the Call action. The small icon is the Orbit glyph (`ic_notification`), the same shape as the themed launcher icon.

NOTIF-16 (2026-10-07): `CallLogSyncWorker` reads `PostCallNotifier.markBeforeSync()` (the highest call event id) before it reconciles and, after a pass that inserted rows, calls `onSyncFinished(insertedAfterId, bulkPass)`; ids above the marker are this pass's rows, which needs no change to the reconciler. The notifier reads `WaitingCalls` (NOTE-05), keeps the calls above the marker that ended within 2 hours, and posts `PostCallNotification` per person (id `NotificationIds.postCall(contactId)`, 3_000_000 range; the person's id in an extra). `PostCallNotifier.startShadeReconciliation`, started in `OrbitApp.onCreate`, collects `WaitingCalls.observe()` and on every emission cancels the notifications whose person no longer waits; it lists the shade before it reads the waiting calls, so a notification posted a moment earlier is never mistaken for a stale one. The 24 hour window closing writes nothing, so that watch never sees it: each notification is posted with `setTimeoutAfter` set to the time left until its call is 24 hours old. Whether Orbit is on screen is `AppForeground`, set from `MainActivity`'s ON_START / ON_STOP; a process WorkManager woke has no activity, so it is not in the foreground. The notification's colour comes from `resolveNotificationTheme`, shared with the nudge.

### Data model

No dedicated Room entity. Due counts derive from existing data via rule-engine. Scheduled work metadata lives in WorkManager's own DB. One DataStore long per list (`nudge_last_named_{listId}`) holds the contact id the last nudge named (NOTIF-15); `AppPrefs.resetAll` clears it with everything else.

### Permissions / integrations

- **Manifest:** `android.permission.POST_NOTIFICATIONS` (runtime on API 33+).
- **Manifest (conditional):** `android.permission.READ_PHONE_STATE` — only if `PHONE_STATE` broadcast proves necessary; prefer inferring from call-detection polling.
- `READ_CONTACTS` (already held) to read a person's photo for the large icon; without it the initials avatar is used.
- The Call action resolves `ACTION_DIAL` through the `<queries>` entry in the manifest, as `ui/util/Dialer.kt` does.
- Integrates with call-detection: a missed inbound call surfaces the contact in-app (no notification — ADR 0009).
- Integrates with orbit-lists for per-list toggle state.

### Known gotchas

- WorkManager on doze devices may fire up to ~15 minutes late. Acceptable per app's unhurried pacing (ADR 0004).
- Users can disable individual channels in system settings; code must not crash when posting to a disabled channel — wrap in try/catch.
- Boot completion re-schedules are handled by WorkManager automatically; do not add a custom `BOOT_COMPLETED` receiver for digest.
- `VISIBILITY_PRIVATE` redacts only where the user's lock-screen setting hides sensitive content; it is a request to the system, not a guarantee (see Open product questions). Orbit does not set the channel's `lockscreenVisibility`: Android documents it as the user's setting, and whether an app-set value would force redaction was not checked on a device.
- The Call action leaves the nudge in the shade. Since Android 12 an app cannot dismiss a notification from an action that starts another app's activity (notification trampolines are blocked), and the dialer is not Orbit. The next slot replaces it.
- `NotificationCompat.Action` needs an icon even though Android 7+ templates do not draw it; it is the Phosphor phone.

### Not in scope (technical)

- `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`. Rejected in ADR 0004.
- Custom notification sounds. Use channel default.

### Open technical questions

- Per-list `NotificationChannel` (granular user control via system settings) vs shared "list prompts" channel? Shared is simpler; granular is more respectful. Leaning shared for v1.
