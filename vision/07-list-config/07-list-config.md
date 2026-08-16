# List Configuration

> **Intent** — Where a list's *rhythm* is defined. This screen exists to translate a human intention ("check in with family every couple of weeks, but not late at night") into the cadence, hours, nudges, and membership the engine uses to decide who surfaces when. Its job is to make a fairly deep set of controls feel like setting a vibe, not programming a scheduler.

**Mission tie** — This is the dial that controls *what the loop surfaces*. Get it legible and it becomes the user's main lever over "who should I call?"; get it intimidating and people leave it on defaults forever.

---

## Today

<img src="./actual-list-config-cadence.png" width="300" alt="List Config top — Name field, Cadence templates (Keep in touch / Late night / Energize), and an interval slider 'Aim for every 2 days'" />
<img src="./actual-list-config-schedule.png" width="300" alt="List Config middle — Active hours with a day-bar, Notifications toggle, and Nudges with seven day circles and a 10am time" />
<img src="./actual-list-config-members.png" width="300" alt="List Config members preview — '27 people' with avatars and remove buttons" />

- **Name** field.
- **Cadence** templates (Keep in touch / Late night / Energize), each with a plain-language description.
- An **Interval** slider ("Aim for every — 2 days") on a linear 1d→2m scale. The scale is linear, not the non-linear one this doc long described (ADR 0010) — so the whole 1d–1w end of the range is squeezed into the first ~10% of the track, and the 2-day default sits ~1.7% along, visually flush with the `1d` tick.
- **Active hours**: an "Always active" toggle plus from/to time pickers and a visual day-bar.
- **Notifications** toggle ("Notify me when I should reach out").
- **Nudges**: seven day-of-week circles + one or more times + "Add time."
- A **Members preview** with add/remove.

It's powerful and well-built — but it's *dense*, and it asks the user to assemble the rhythm in their head from six separate controls.

---

## Where it's going

### `CONFIG-1` · One plain-language rhythm summary · **Now**
Add a single sentence at the top (or pinned) that translates every control into one readable line: *"You'll see each person about every 2 days, on weekdays 9am–5pm, with a nudge at 10am."* It turns six controls into one comprehensible outcome and lets someone confirm "yes, that's the vibe I wanted" without parsing each widget. Highest-value change on this screen.

### `CONFIG-2` · Tuck the advanced controls away · **Next**
For a first-time list, the full stack (nudge days, multiple nudge times, exact active hours) is intimidating. Lead with the essentials — name, cadence, interval — and collapse **Active hours** and **Nudges** under an "Advanced" reveal. The depth stays for power users; the first run feels like picking a vibe, not filling a form.

### `CONFIG-3` · Lighten the seven-circle nudge block · **Next**
Seven filled terracotta day-circles is a heavy, busy block that dominates the section. Replace the common case with a simple **"Every day / Weekdays / Custom"** control and only expose the per-day circles under Custom. Same capability, far calmer default.

### `CONFIG-4` · Live "who this surfaces" preview · **Later**
The ultimate legibility move: as the user tunes the rule, show a small live preview of *which people* it would surface and roughly how often. It closes the gap between abstract settings and concrete outcome — you'd *see* your rhythm, not just describe it.

### `CONFIG-5` · Give the short end of the interval scale room to breathe · **Next**
The interval scale is linear across 1–60 days, so the intervals people actually pick most — every 1 to 7 days — live in the first ~10% of the track, and the 2-day default renders flush against the `1d` tick. It reads as a bug (it has been reported as one twice) even though the number is correct. Curve the scale — log-ish, so 1d–1w takes roughly the first third and the sparse 1m–2m end compresses instead. The tick placement already derives from one shared fraction helper (`intervalLabelFraction`), so the thumb and the labels would follow the new curve together. Decided against fixing this by raising the minimum: see ADR 0010.
