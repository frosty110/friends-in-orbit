# Owner review of the prototype, 2026-10-07

The owner walked the clickable prototype (`vision/flows/prototype/index.html`,
published as an artifact) and left 14 comments, in this order: Home, Card view,
Browse, List settings, Home again, then New list. This file records each one,
what we decided, and why. The app is the source of truth, so every decision
below lands in the app first and the prototype is re-derived from it.

Requirement IDs named here are defined in their feature specs in the commit that
implements them.

| # | Where | Asked for | Decision | IDs |
|---|---|---|---|---|
| 1 | Home, rhythm strip | Rims too thin; can't tell who called whom; pink weak; insulate with black | Done in `eb21eea`: 3dp pink (you) and teal (them) rims, a 1.5dp near-black ring between rim and fill, the same mark on the legend and the day sheet | HOME-8 |
| 2 | Home, "See this day" | A bigger chart of who I called and when, a week like a calendar, AM/PM, scroll back | A Week screen | HOME-13 |
| 3 | Card view, after "Call" | Feedback or notes after the call, with a count-up timer that stays visible | A post-call note page | NOTE-04, CARD-11 |
| 4 | Card view, Later and Sooner | Buttons should play the swipe; hints that fade in and out to teach the swipe | Button swipes and idle hints | CARD-08, CARD-09 |
| 5 | Card view, "Open details" line | Log a connection from the card ("had dinner yesterday") | A "Log a connection" action on the card | CARD-10 |
| 6 | Card view menu, "Browse people" | Expected the order people come up in, with the current person first | Browse is the sequence | BROWSE-07, BROWSE-09 |
| 7 | Browse, title | It is the sequenced list; let me sort it, knowing it is not permanent | Drag to reorder the sequence | BROWSE-08 |
| 8 | List settings, Name | Edit the name at the title instead of a separate field? | Rename from the title | LIST-26 |
| 9 | List settings, "Cadence" | Redundant with the slider below | One "How often" control | LIST-24 |
| 10 | List settings, "Always active" | Confusing; say which part of the day instead | Time-of-day choice | LIST-25 |
| 11 | List settings, "11 people" | Expected an Add people button there | Add people at the People header | LIST-27 |
| 12 | Home, post-call banner | A notification if possible; several unnoted calls stacked, each closable; cleaner | Notes waiting stack, and a post-call notification | NOTE-05, NOTIF-16, HOME-14 |
| 13 | New list, "Choose a template" | Fewer templates, Start from blank on top, colour-coded, sorted by frequency | A shorter, ordered set | LIST-29 |
| 14 | New list, "Name your list" | Not on this screen: Next, then name, then how often, then people, then Create | A step-by-step New list flow | LIST-28 |

## Decisions, one by one

### 2. A Week screen (HOME-13)

The strip answers "how was my week". The owner wants "when, exactly, and who".
A new screen, reached from the strip's "Last 7 days" line and from the day
sheet, draws the list's last seven days as seven columns on a time axis that
runs down the page from the start of the day to midnight, hour lines labelled
in the phone's clock format (AM and PM on a 12-hour phone, which is the
owner's; a 24-hour phone keeps 24-hour labels, as everywhere else in Orbit).
Each call is a block at its start time, as tall as it was long, filled with the
person's colour and wearing the same direction mark as the strip. Arrows and a
sideways swipe step back a week at a time through the history Orbit has; "This
week" comes back. A day is one TalkBack node that reads its calls in order. It
shows the same calls as the strip (the HOME-7 qualifying calls), so the two
never disagree.

### 3 and 12. Notes after a call (NOTE-04, NOTE-05, NOTIF-16, HOME-14, CARD-11)

Does the call return to Orbit? Usually: Orbit hands the number to the phone's
dialer, and when the call ends Android returns to the app that was in front,
which is Orbit. Some phones show their call log first. Either way Orbit reads
the finished call from the call log a moment later.

One place to write about a call: a **post-call note page**. It names the person
and the call ("You called Kai · 14 min · 4:30pm"), asks "How did it go? What do
you want to remember?" over one large writing field, and shows a timer counting
up from 0:00 in the top bar, so it stays in view however long the entry gets.
It is a nudge to keep it short, not a limit. Save writes an ordinary note;
"Not now" leaves without one, and leaving with words written asks first.

It opens from three places:
- **The card (CARD-11).** When a call placed from the card connected and lasted
  a minute or more, the page opens by itself once Orbit sees the call. A short
  or unanswered call keeps today's quiet "Called Kai" message with its
  "Add a note".
- **Home (NOTE-05, HOME-14).** Every call from the last 24 hours that lasted a
  minute or more, with someone on a list, that has no note written since and
  that you have not dismissed, waits on Home. One waits as a single card; two
  or more stack like cards in a pile ("3 calls to write about"), and a tap
  opens the pile into rows, each with "Add a note" and a dismiss control, plus
  "Dismiss all". This replaces the single "You just called" banner.
- **A notification (NOTIF-16).** Yes, it is possible, without any new
  permission: Orbit already reads the call log, and Android can wake Orbit when
  the call log changes even if Orbit was closed. When a qualifying call lands
  while Orbit is not on screen, a notification asks "How was your call with
  Kai?" and opens the note page. It has its own notification channel so it can
  be turned off on its own, the lock screen shows it without the name, and it
  goes away once you write the note or dismiss it on Home.

### 4. Later and Sooner teach the swipe (CARD-08, CARD-09)

Tapping Later or Sooner now plays the same fly-off the swipe does, in the same
direction, then moves on. When the card sits untouched for a few seconds, quiet
hints fade in at its edges ("Later" with a left arrow, "Sooner" with a right
arrow, each saying when that person would come up), hold, and fade out, coming
back a few times while nothing happens. They stop for good once you have
swiped a handful of times. With animations turned off in Android, nothing
flies or fades: the move just happens, and the hints sit still while they show.
TalkBack users have the labelled buttons, so the hints stay out of its way.

### 5. Log a connection from the card (CARD-10)

The card gains "Log a connection" beside "Open details". It opens the same sheet
Contact detail uses (a connection or an attempt, when, an optional note such as
"Had dinner yesterday"). Saving logs it like a call: the person goes back into
the rhythm and the deck moves on, with "Logged. Kai comes up again in 2 weeks."

### 6 and 7. Browse is the sequence (BROWSE-07, BROWSE-08, BROWSE-09)

Browse already numbered the people due now, then listed everyone else
alphabetically. Now it is one sequence: everyone in the order Orbit will bring
them up, each with when ("Up now", "Thursday", "In 2 weeks"), then the paused
people, then the ignored ones. Opened from the card, the person on the card is
first and marked "On your card". A drag handle on each row moves a person
earlier or later in the sequence (TalkBack gets "Move up" and "Move down").
That moves their next turn, so it lasts until the rhythm moves them again: a
call, Later or Sooner reshuffles as usual, which the screen says in one line
under the sequence. Each move has Undo.

### 8. Rename from the title (LIST-26)

The list's name is the screen's title. Tapping it, or the pencil beside it,
turns the title into a text field with Save and Cancel; a blank name keeps the
old one. The separate Name section goes away.

### 9. One "How often" control (LIST-24)

The owner is right that the rhythm choice repeats the slider. Under the hood,
Keep in touch, Late night and Energize are the same calculation with different
starting numbers: Late night starts at every 3 days, Energize at every day. So
List settings shows one control, "How often", for every list. A Late night or
Energize list shows its real starting interval, and moving the slider makes it
an ordinary list at the interval you chose. The Lists screen describes every
list the same way ("Every 3 days"). The same goes for Make your first list.

### 10. Time of day (LIST-25)

"Always active" with a start and end time is replaced by one choice: "Any time",
"Mornings" (7am to noon), "Afternoons" (noon to 5pm), "Evenings" (5pm to 9pm)
or "Nights" (9pm to 7am). It means what active hours meant: a nudge for this
list only comes in that part of the day, and when none of the list's nudge
times falls inside it, the nudge comes at the start of it. That is why
mornings start at 7am and not earlier: a part that began at 5am would mean a
5am nudge. A list that already has a window that
matches none of these shows it as "Custom", selected, until you pick another,
so no one's setting changes behind their back. One part of the day per list
keeps it simple; mornings and evenings together would need a new kind of
stored setting, and we can add that if it is missed.

### 11. Add people at the People header (LIST-27)

The People section's header ("11 people") gets an "Add people" button on its
right, so it is in view without scrolling past everyone. The row at the foot of
the list goes, so there is one way to do it. Smart lists fill themselves and
have neither.

### 13 and 14. New list, step by step (LIST-28, LIST-29)

New list becomes a short flow, one decision per screen, in the owner's order:

1. **Start with**: "Start from blank" first and full width, then three
   templates sorted from most to least often (Inner orbit, about weekly;
   Family, every couple of weeks; Drifted, about monthly), each tinted a step
   along one colour ramp so more often reads warmer, then, set apart, the list
   that fills itself ("Recently added, not called"). Mentors (every couple of
   months) goes: the slider on the next screens covers it. Next.
2. **Name**: filled in from the template, editable. Next.
3. **How often**: the same slider as List settings, set from the template.
   Next (or Create, for the list that fills itself).
4. **Add people**: the contact picker, then "Create". You can create it empty.

Back steps back without losing what you entered. Create makes the list and its
people in one go and returns you to where you started, with the new list there.

## Not changed

- The rhythm strip's tap-a-day sheet stays; the Week screen sits beside it.
- Contact detail keeps its own note field and its own "Log a connection".
