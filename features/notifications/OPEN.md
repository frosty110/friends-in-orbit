# OPEN — Onboarding promises nudges that do not exist

**Raised:** 2026-06-09 (quality review #14; readiness audit H-4)
**Owner:** the maintainer
**Blocks:** first external user / Play Store submission

## The gap

Onboarding's notifications screen asks for `POST_NOTIFICATIONS` with the
promise "One quiet nudge per list per day. You can change this any time."
At the time, nothing delivered on it: `DailyDigestWorker` was cancelled in
`OrbitApp.onCreate` (retired 2026-04-28, SET-08) and the per-list nudges were
unshipped. A user who granted the permission and waited for a nudge waited
forever — acute trust damage for the primary user, who depends on the nudges
to remember to call people.

## Resolution paths (pick one)

1. **Ship the per-list nudges before any external user.** The promise
   becomes true. Largest scope; the WorkManager + Hilt worker substrate
   already exists.
2. **Soften the onboarding copy until nudges ship.** Reword the screen to
   what is true today (e.g. ask for the permission "so Orbit can nudge you
   when reminders arrive" only once they exist, or defer the permission ask
   entirely). Smallest scope; honest immediately.

## Decision

**2026-06-09 — Path 1: ship the per-list nudges.** The maintainer chose to make
the promise true rather than soften the copy. Notifications became the next
work item; the onboarding copy stays as-is and the nudges land before any
external user. Recorded from chat directive "Build notifications."

**2026-06-10 — SHIPPED.** The notifications work closed (8/8 plans,
PASS-WITH-DEFERRED-UAT). Per-list nudges are default-on (all days at 10:00,
due-gated) so the promise "one quiet nudge per list per day" is now literally
true; incoming-call follow-ups, the List Config nudge editor, and the
DailyDigestWorker deletion landed alongside. Device UAT items remain tracked
separately. This item is closed.
