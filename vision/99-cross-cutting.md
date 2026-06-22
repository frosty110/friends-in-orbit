# Cross-cutting

> **Intent** — Moves that aren't tied to a single screen — system-wide patterns, consistency fixes, and principles that should hold everywhere. These exist so that the per-screen suggestions don't accidentally pull in different directions, and so the things that make the *whole* app feel coherent (theming, voice, naming, safety, a11y) get owned somewhere rather than falling through the cracks.

**Mission tie** — Coherence is part of the calm. An app that's quiet on one screen and jarring on the next breaks the unhurried feeling the mission depends on.

---

## Where it's going

### `X-1` · Warm-theme every dropdown menu · **Now**
Material's default **lavender** menu surface shows up on at least two screens (Lists Manager overflow, Card View list-actions) — the most visible break in the cream-and-terracotta system. Theme `DropdownMenu` (and any popup surfaces) to `surface` / `accentTint` once, centrally, so menus stop looking like they're from a different app. Cheap, high-visibility polish. (Referenced by `LISTS-2`.)

### `X-2` · A consistent "destructive / irreversible action" pattern · **Now**
Some actions can't be taken back — placing a call (`CARD-3`), deleting a list, ignoring at scale. Today the card makes its *entire surface* a one-tap dialer, which caused a real accidental call during this review. Establish one principle: irreversible actions live behind a clear, labelled control (or a light confirm), never behind a tap that could be a mis-hit. Apply it to the card first, then audit elsewhere.

### `X-3` · Display names / nicknames for messy contacts · **Next**
Real address books are full of operational cruft — *"Eric Henderkson? Sila," "Ben Saa 8:30am Meeting," "Gabriel L (Use This number)."* It surfaces everywhere (Card, Browse, Pickers, Call Log) and makes the app feel like a dump of your phone rather than *your people*. Let a person carry an Orbit-local **display name** (without touching the phone contact), shown primary, with the raw name secondary. Offer to set it at the natural moments — filing them in a picker (`PICK-3`), or on Contact Detail. This is one of the bigger "feels intentional vs feels like a contacts export" levers in the app.

### `X-4` · Voice consistency on every new string · **Ongoing**
Every string proposed across these files must clear the existing bar before it ships: sentence case, no exclamation marks, no gamification (streaks/achievements/XP), no shame framing ("you haven't called X in N days" is explicitly forbidden), no emoji in product copy, pattern language over performance language. When in doubt, check `README.md` → "Content fundamentals" and `features/_foundations/`. This isn't a feature — it's a gate on all of the above.

### `X-5` · One vocabulary for Later / Sooner / Skip · **Next**
The defer/advance language isn't consistent: Card View shows "Later"/"Sooner" on the arrows but "Skip" in the text row; onboarding teaches "Later/Sooner"; undo snackbars and widgets will need the same words. Pick one vocabulary for the two directions and use it identically across Card View, onboarding, widgets, and snackbars. A core gesture should have one name. (Referenced by `CARD-2`.)

### `X-6` · Hold the accessibility floor as features land · **Ongoing**
The app already respects a 16sp body minimum, 48dp touch targets, and font-scale scrolling on the card. Every suggestion here must preserve that — new chips, labels, and controls included. Context lines and quick-chips (`CARD-1`, `CONTACT-2`) especially must not shrink below the type floor to fit. Calm includes legible.

---

## How these relate to the per-screen files

| Cross-cutting move | Shows up in |
|---|---|
| `X-1` Warm dropdowns | `06-lists-manager` (`LISTS-2`), `01-card-view` |
| `X-2` Irreversible-action pattern | `01-card-view` (`CARD-3`) |
| `X-3` Display names | `03-browse-list` (`BROWSE-4`), `09-pickers` (`PICK-3`), `02-contact-detail` |
| `X-4` Voice gate | every file |
| `X-5` Later/Sooner vocabulary | `01-card-view` (`CARD-2`), `11-onboarding`, `12-widgets` |
| `X-6` Accessibility floor | `01-card-view`, `02-contact-detail` |
