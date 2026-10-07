# ADR 0009 — Notifications are scheduled reminders the user owns, never event-driven pushes

**Status:** accepted
**Date:** 2026-07-03
**Amended:** 2026-10-07 (one event-driven notification, after a call; see §Amendment)
**Deciders:** the maintainer
**Supersedes:** none
**Refines:** mission principles 1 ("Reduce activation energy — notifications bring the
suggestion to the user") and 6 ("No shame-based nudges — notifications surface
opportunity, never absence"); `features/notifications/`

## Context

During onboarding dogfood, a **missed** call fired a notification — "{Name} called
you. Want to call back?" (`IncomingFollowUpWorker`) — and, worse, ran the call
through `MarkCalledUseCase` as if it were a connection, resetting the cadence
forward and burying the caller for a full interval. The founder's reaction:

> "we don't need a notification for that ... we should only have notifications
> defined by the user. and those are reminders."

This surfaced a rule that was implicit in the mission but never written down:
*which notifications is Orbit allowed to send at all?*

Orbit had three notification sources:

| Source | Shape |
|---|---|
| **List nudge** (`ListPromptWorker` / `NudgeScheduler`) | Scheduled per-list reminder — "Someone in {list} is ready when you are." |
| **Incoming-call follow-up** (`IncomingFollowUpWorker`) | Fired in reaction to a real call event. |
| **Daily digest** | Already removed before this decision — no worker; `SettingsUiState` notes "no daily-digest-hour field"; the legacy `orbit.daily_digest` work + `orbit.digest` channel are cleaned up on launch. |

The follow-up is categorically different from a nudge. It is a **push** — the app
pinging the user because an *event* happened (a call arrived) — rather than a
**pull** — a reminder firing on a *schedule the user configured*. Mission
principle 6 already forbids per-person, absence-driven notifications; an
event-driven "call {Name} back" is exactly that shape.

## Decision

**Orbit only sends notifications that are scheduled reminders the user owns. It
never sends a notification in reaction to an event.**

The bright line is pull vs. push:

- **Pull (allowed):** a reminder that fires on a schedule the user controls and
  can mute — today, only the per-list nudge. Opportunity-framed, name-free, gated
  by the per-list `notificationsEnabled` flag and an editable `NudgeSchedule`.
- **Push (disallowed):** any notification triggered by a real-world event — a call
  arriving or missed, a contact added, "you're behind." Events change what the
  user sees *inside* the app (surfacing), never earn an interruption.

Concretely:

1. `IncomingFollowUpWorker` and its supporting infra are removed (implemented
   2026-07-03). A missed inbound call now surfaces the contact in the deck ("due
   since they rang" — KeepInTouchEngine step 3c), an in-app pull, with no
   notification.
2. Any future notification MUST be a scheduled reminder the user can turn off. No
   event-driven notification may be added without superseding this ADR.

## Consequences

- The incoming-call follow-up is gone: channel `orbit.incoming_followup.v2` is
  deleted on next launch; the worker, dedup store, copy, and id helper are removed.
  See the 2026-07-03 `feat(notify)` commit.
- **List nudges remain default-ON, and that is consistent with this ADR.** A nudge
  is a scheduled reminder, not an event push; it is opportunity-framed (principle
  6) and per-list muteable, so the user *owns* it even though the default is on.
  Mission principle 1 explicitly wants notifications to bring the suggestion to the
  user, so default-on is intended, not a leak. "User-defined" here means
  user-*controlled* (owns the schedule, can mute), not opt-in-required.
- The daily digest, already removed, stays removed. There is no path to re-add a
  non-reminder notification without superseding this ADR.
- The notification surface is now a single kind (scheduled list nudges), which
  simplifies the Data Safety / permissions story: `POST_NOTIFICATIONS` is used only
  for reminders the user schedules.

## Non-consequences

- **No change to the nudge scheduler.** `ListPromptWorker` / `NudgeScheduler` keep
  firing on the per-list schedule, gated by quiet-hours (active-hours per ADR 0008)
  and `notificationsEnabled` as before.
- **Nudges are not made opt-in.** Default-on is retained (principle 1). Switching
  nudges to opt-in would be a separate decision, not implied here.
- **No change to in-app surfacing** beyond the missed-call "due since they rang"
  behavior already shipped.

## Amendment (2026-10-07): the notification after a call

**Status:** amended. The decision above stands for every notification but one;
its rationale is unchanged.

Reviewing the prototype, the owner asked whether Home's "Add a note while it's
fresh" banner could be a notification, and decided it should be
(`vision/flows/owner-review-2026-10-07.md`, decision 12). That notification
reacts to an event, a call ending, which the decision above forbids "without
superseding this ADR". This amendment allows exactly that one notification
(NOTIF-16, `features/notifications/README.md`) and nothing like it by
extension:

- **It is about a call the user had, never about absence.** Only a connected
  call of a minute or more with someone on a list, that ended in the last two
  hours, with nothing written about that person since and not dismissed on
  Home (NOTE-05). A missed or declined call still fires nothing, and the
  follow-up removed on 2026-07-03 stays removed.
- **It asks once and goes away by itself.** One per person per call, posted
  only while Orbit is not on screen, never for a first import or a resync, and
  cancelled as soon as the call stops waiting (a note saved, a dismissal, a day
  gone). No reminder chain, no repeat.
- **The user owns it.** It has its own channel, "After a call", so it can be
  turned off without touching the list nudges; it respects the same gates as a
  nudge (notifications allowed, Do Not Disturb), and its lock-screen version
  names no one (NOTIF-13's rule).

Any other event-driven notification still needs a new decision.

## Related

- `feat(notify): replace missed-call notification with in-app surfacing`
  (2026-07-03) — the implementing change.
- `features/life-right-now/README.md` — records the same principle; Quiet mode
  pauses scheduled nudges (there is no event-driven notification left to pause).
- Mission principles 1 and 6 (`features/_foundations/mission.md`).
