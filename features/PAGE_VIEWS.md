# Page views — user feature expectations

**Status:** active
**Last reviewed:** 2026-06-08
**Purpose:** One file per screen, each listing the things a user expects to be able to see or do on it. Expectation-framed (not an implementation spec). For the canonical per-feature PRD/TECH, see [`INDEX.md`](INDEX.md).

> Cross-cutting expectations that hold on every screen: warm, unhurried, sentence-case copy with no gamification; explicit empty states (never a blank screen); destructive/bulk actions are undoable via snackbar; names blur to "Contact" when the app loses focus (privacy curtain); denied permissions degrade gracefully instead of crashing.

Each page view lives in its own file under [`page-views/`](page-views/). When a screen is added, renamed, or removed, add/rename/remove its file and update this index in the same commit.

---

## Onboarding

| Page view | Route |
|---|---|
| [Welcome](page-views/onboarding-welcome.md) | `onboard/welcome` |
| [Permission — Contacts / Call log / Notifications](page-views/onboarding-permissions.md) | `onboard/permissions/*` |
| [Reading your call history](page-views/onboarding-sync.md) | `onboard/sync` |
| [Preview your first list](page-views/onboarding-preview.md) | `onboard/preview` |
| [Make your first list](page-views/onboarding-first-list.md) | `onboard/first-list/{listId}` |
| [Done](page-views/onboarding-done.md) | `onboard/done` |

## Core loop

| Page view | Route |
|---|---|
| [Home](page-views/home.md) | `home` |
| [Card view](page-views/card-view.md) | `card/{listId}` |
| [Up next / Queue](page-views/queue.md) | `queue/{listId}` |
| [Browse people](page-views/browse.md) | `browse/{listId}` |

## Contact

| Page view | Route |
|---|---|
| [Contact detail](page-views/contact-detail.md) | `contact/{contactId}` |

## Lists

| Page view | Route |
|---|---|
| [Lists manager](page-views/lists-manager.md) | `lists` |
| [Edit list / configuration](page-views/list-config.md) | `lists/{listId}/config` |
| [Add contacts / picker](page-views/picker-contacts.md) | `pick/contacts` |
| [Add to lists / reverse picker](page-views/picker-lists.md) | `pick/lists` |

## Settings & data

| Page view | Route |
|---|---|
| [Settings](page-views/settings.md) | `settings` |
| [Ignored contacts](page-views/ignored-contacts.md) | `settings/ignored` |
| [Call history](page-views/call-history.md) | `call-log` |
| [Search](page-views/search.md) | `search` |

---

## Not in this build
- **Home-screen widgets (2×2 / 4×2):** specced in `INDEX.md` but no `app/orbit/widget/` providers exist yet — no widget page view ships today.
