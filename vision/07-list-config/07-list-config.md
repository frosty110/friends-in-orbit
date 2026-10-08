# List Configuration

> **Intent**: Where a list's *rhythm* is defined. This screen exists to translate a human intention ("check in with family every couple of weeks, but not late at night") into the rhythm, hours, nudges and membership the engine uses to decide who surfaces when. Its job is to make a fairly deep set of controls feel like setting a vibe, not programming a scheduler.

**Mission tie**: This is the dial that controls *what the loop surfaces*. Get it legible and it becomes the user's main lever over "who should I call?"; get it intimidating and people leave it on defaults forever.

---

## Today (as of 2026-10-06)

<img src="./actual-list-config-cadence.png" width="300" alt="List settings for Inner orbit, top: a back arrow, the list name and Done in the accent; a Name field with a pencil; Rhythm with three radio rows, Keep in touch (selected), Late night, Energize, each with a one-line description; How often with Aim for every 2 days, a slider and ticks 1 day, 2 weeks, 1 month, 2 months; Active hours with an Always active switch; the start of Nudges." />
<img src="./actual-list-config-schedule.png" width="300" alt="List settings, nudge schedule: seven tinted day circles S M T W T F S, a 10am time row with a remove control, an Add time row, and the summary Every day at 10am." />
<img src="./actual-list-config-members.png" width="300" alt="List settings, members preview: 3 people, Alex Rivera, Sam Patel and Jordan Lee, each with an initial circle and a remove control, then Add people." />

*JVM gallery render (`PreviewGalleryTest`, Robolectric, light, 411dp, font scale 1.0) at `783a964`, 2026-10-05, not a device capture (`ListConfigScreen.ListConfigContentPreview`, cropped in three). The 2026-10-06 lists package reworded three lines after this render ("Aim for every 2 days" as one sentence, "A gentle nudge", "People" over the members); the bullets describe the merged build, and the [List settings page view](../../features/page-views/list-config.md) is canonical.*

- App bar: the list's name and **Done**, the screen's one accent (LIST-21). Changes save as you go; Done only closes.
- **Name**: an inline field with a pencil.
- **Rhythm**: Keep in touch / Late night / Energize as radio rows in ink, each with a plain description.
- **How often**: "Aim for every 2 days" (one sentence, the value its argument) on a slider whose ticks read 1 day, 2 weeks, 1 month, 2 months, and which tells TalkBack "Every 2 days". The scale is linear, so the 1 to 7 day end still sits in the first tenth of the track (see `CONFIG-5`).
- **Active hours**: an "Always active" switch, else from/to pickers and a day bar whose ticks follow the phone's 12 or 24 hour setting.
- **Nudges**: a **Send nudges** switch ("A gentle nudge when someone here is worth a call."), then **When to nudge**: seven day chips (tinted when selected, ink, never the accent), one or more times with Change time and remove, **Add time**, and a one-line summary ("Every day at 10am", "No days selected: nudges off"). A "Nudges paused" badge shows while nudges are off.
- For a smart list, a **Smart rule** editor and **Make this a regular list** with a confirmation that names how many people stay.
- **People** (it was "Members preview": the section adds and removes people) with the count, remove (Undo) and **Add people**.
- "Orbit couldn't load this list" with Try again if reading fails (LIST-22).

Powerful and well built, and far calmer than in June (one accent, plain words). It still asks the user to assemble the rhythm in their head from several controls.

---

## Where it's going

### `CONFIG-1` · One plain-language rhythm summary · **Now**
Add a single sentence at the top (or pinned) that translates every control into one readable line: *"You'll see each person about every 2 days, with a nudge on weekdays at 10am."* It turns six controls into one comprehensible outcome and lets someone confirm "yes, that's the vibe I wanted" without parsing each widget. The nudge schedule already has its one-line summary ("Every day at 10am"); this extends the idea to the whole screen. Highest-value change here.

### `CONFIG-2` · Tuck the advanced controls away · **Next**
For a first-time list, the full stack (nudge days, multiple nudge times) is intimidating. Lead with the essentials (name, how often) and collapse **When to nudge** under an "Advanced" reveal. (Active hours, later Time of day, is gone since 2026-10-08: a list's nudge timing is its days and times alone, LIST-25.) The depth stays for power users; the first run feels like picking a vibe, not filling a form.

### `CONFIG-3` · Lighten the seven-circle nudge block · **Retired 2026-10-05**
The premise was seven *filled terracotta* day circles dominating the section. The chips are now tinted and selected in ink, not the accent (LIST-21), and a one-line summary names the common case ("Every day at 10am", "Weekdays at 10am"), so the block no longer shouts. The "Every day / Weekdays / Custom" control proposed here was not built; if the block still reads as busy once `CONFIG-2` lands, revisit it there.

### `CONFIG-4` · Live "who this surfaces" preview · **Later**
The ultimate legibility move: as the user tunes the rule, show a small live preview of *which people* it would surface and roughly how often. It closes the gap between abstract settings and concrete outcome; you would *see* your rhythm, not just describe it.

### `CONFIG-5` · Give the short end of the interval scale room to breathe · **Next**
The interval scale is linear across 1 to 60 days, so the intervals people actually pick most, every 1 to 7 days, live in the first tenth of the track, and the 2-day default renders flush against the "1 day" tick. It reads as a bug (it has been reported as one twice) even though the number is correct. Curve the scale, log-ish, so 1 day to 1 week takes roughly the first third and the sparse 1 to 2 month end compresses instead. The tick placement already derives from one shared fraction helper (`intervalLabelFraction`), so the thumb and the labels would follow the new curve together. Decided against fixing this by raising the minimum: see ADR 0010.
