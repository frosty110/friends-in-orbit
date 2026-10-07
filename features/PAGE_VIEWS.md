# Page views: what each screen owes the user

**Status:** active
**Last reviewed:** 2026-10-06
**Purpose:** One file per screen, each saying what a user expects to see and do there: how the screen is reached, what is on it, every action and menu, every state, where it leads, and the tests that pin it. Expectation-framed, not an implementation spec. For the canonical per-feature PRD/TECH, see [`INDEX.md`](INDEX.md).

> Cross-cutting expectations that hold on every screen: warm, unhurried, sentence-case copy with no gamification; explicit empty states (never a blank screen, and never a false one while data loads: quiet chrome where the data is already cached, a quiet skeleton where the first read takes time); a failed read says so and offers Try again; destructive and bulk actions are undoable from a snackbar, and a write that fails says so; names, photos, list names, numbers and notes are masked when the app loses focus (the privacy curtain, PRIV-03); denied permissions degrade gracefully and never make a false claim ("Never called" is not said about someone Orbit cannot see); every gesture has a visible path; one accent element per screen (rules.md Design 5).

Each page view lives in its own file under [`page-views/`](page-views/). When a screen is added, renamed or removed, add, rename or remove its file and update this index in the same commit.

---

## Onboarding

| Page view | Route |
|---|---|
| [Welcome](page-views/onboarding-welcome.md) | `onboard/welcome` |
| [Permission: Contacts / Call log](page-views/onboarding-permissions.md) | `onboard/permissions/contacts`, `onboard/permissions/call-log` (and the legacy `onboard/permissions/notifications`, resume only) |
| [Reading your call history](page-views/onboarding-sync.md) | `onboard/sync` |
| [Preview your first list](page-views/onboarding-preview.md) | `onboard/preview` |
| [Make your first list](page-views/onboarding-first-list.md) | `onboard/first-list/{listId}` |
| [Done](page-views/onboarding-done.md) | `onboard/done` |

## Core loop

| Page view | Route |
|---|---|
| [Home](page-views/home.md) | `home` |
| [Card view](page-views/card-view.md) | `card/{listId}` |
| [Browse people](page-views/browse.md) | `browse/{listId}` (the numbered queue lives here, under "Next up") |

## People

| Page view | Route |
|---|---|
| [Contact detail](page-views/contact-detail.md) | `contact/{contactId}`, with optional `focusNote` and `scrollToCallEventId` |
| [Search](page-views/search.md) | `search` |
| [Add to lists](page-views/picker-lists.md) | `pick/lists?contactId={contactId}` |

## Lists

| Page view | Route |
|---|---|
| [Lists](page-views/lists-manager.md) | `lists`, `lists?openCreate=true` |
| [List settings](page-views/list-config.md) | `lists/{listId}/config` |
| [Add people](page-views/picker-contacts.md) | `pick/contacts?targetListId={listId}`; Re-link: `pick/contacts?mode=relink&relinkContactId={contactId}` |

## Settings & data

| Page view | Route |
|---|---|
| [Settings](page-views/settings.md) | `settings` |
| [Ignored](page-views/ignored-contacts.md) | `settings/ignored` |
| [Call history](page-views/call-history.md) | `call-log`, `call-log?contactId={contactId}` |

---

## Page view schema

Every page view follows this schema, the way every feature README follows the one in [`INDEX.md`](INDEX.md#feature-readme-schema). The heading set is what `scripts/check-conventions.py` asserts, so a page view cannot quietly drop a section again.

```
# <Screen name>

**Route:** every route pattern, each optional argument and what it changes
**Group:** Onboarding | Core loop | People | Lists | Settings & data
**Status:** active | legacy (resume only) | retired
**Last reviewed:** YYYY-MM-DD
**Spec:** the feature README, and the requirement IDs this screen carries

---

## Reached from
## What the user sees
## Actions and menus
## States
## Leads to
## Tests that pin it
```

- **Reached from:** every screen and control that opens this one; deep links (a nudge, a widget, a launcher shortcut); onboarding resume.
- **What the user sees:** the app bar (title, back, actions), the body top to bottom, and the screen's one accent element (rules.md Design 5).
- **Actions and menus:** every control with its exact words, its effect, its undo, and the dialog or sheet that confirms it (title, body, buttons); menus item by item, in order; selection bars; snackbars and their Undo.
- **States:** loading, each distinct empty state, error (with Try again), permission denied, the privacy curtain; exact copy.
- **Leads to:** every outgoing destination and where back lands, including after a commit.
- **Tests that pin it:** only tests that exist, or that a package in the current round adds (say "added this round"), by name; gallery previews by name.

There is no `Code:` field: code paths live in the feature README's Ground truth, and a page view stays expectation-framed. It quotes copy as the user reads it and names destination screens; route strings appear only in the Route header. Section order is load-bearing: grepping `## States` or `## Leads to` across `page-views/` must return one hit per screen.

---

## Surfaces outside the app

- **Home-screen widgets, nudges and launcher shortcuts** ship, but they are not screens of the app, so they have no page view. What each owes the user is in [`widgets/README.md`](widgets/README.md) and [`notifications/README.md`](notifications/README.md). Where each one lands (Card view, Search, Home) is recorded under "Reached from" on the screen it opens.
