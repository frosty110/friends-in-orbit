# Your call (the post-call note page)

**Route:** `note/{contactId}?callEventId={callEventId}`. `contactId` is the person; the optional `callEventId` is the call the page describes. Without it the page describes the person's latest connected call
**Group:** People
**Status:** active
**Last reviewed:** 2026-10-07
**Spec:** [contact-detail](../contact-detail/README.md): NOTE-04 (added 2026-10-07), NOTE-05; HOME-14 in [home](../home/README.md); NOTIF-16 in [notifications](../notifications/README.md); PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Home: "Add a note" on a call waiting for a note, as the single card or a row of the open pile (HOME-14)
- The notification after a call, "How was your call with Kai?" (NOTIF-16): a tap opens this page over Home, with Orbit open or closed. However the page is reached, opening it withdraws that person's notification
- Card view: "Add a note" on the "Called {first name}" snackbar after a call placed from the card (CARD-03). Card view will also open it by itself after a call of a minute or more placed from the card (CARD-11, not yet built)

## What the user sees

- App bar: "Not now" (it leaves), the title "Your call with Kai", and a timer counting up from 0:00 from the moment the page opened ("2:14", then "1:02:07" past an hour). The timer is in the bar, so it stays in view however long the entry gets; it is a nudge to keep the entry short, not a limit. Turning the phone or the app being closed in the background does not restart it
- The call in one line: "You called Kai · 14 min · Today at 4:30pm", or "Kai called you · 14 min · Yesterday at 9pm"; no line when the person has no connected call to describe
- One large writing field filling the page, which scrolls inside itself as the entry grows, with "How did it go? What do you want to remember?" as its placeholder; the cursor is in it and the keyboard up when the page opens
- "Save note" at the bottom, above the keyboard: the one accent element (rules.md Design 5), and disabled while the field is blank
- TalkBack hears the timer as "Writing for less than a minute", then "Writing for 2 minutes", words that change once a minute; it is never announced by itself. The field's label is its placeholder while it is empty

## Actions and menus

- Type in the field: the words are kept if the app is closed in the background and reopened
- "Save note": writes an ordinary note about the person (the same kind their page's note field writes, timestamped now) and returns to where the page was opened from, which shows "Note saved". The call stops waiting on Home and its notification goes away. If the note cannot be written: "Couldn't save your note. Try again.", and every word stays
- "Not now" or Back with nothing written: leaves at once
- "Not now" or Back with words written: asks "Discard this note?" with "Keep writing" (stays, words kept) and "Discard" (leaves, the words are gone, also from a later restore)
- There is no menu

## States

- Loading: quiet chrome, the bar with "Your call" and the timer, until the person is read; never a false "isn't in Orbit"
- Ready: as above
- The person is no longer in Orbit: "This person isn't in Orbit anymore" / "They may have been removed from your phone's contacts." with "Go back" (the same message as their own page); a back arrow in the bar
- A failed read: "Couldn't load this person" / "Nothing is lost. Try again in a moment." with Try again
- Privacy curtain: the title reads "Your call", the call line "You called · 14 min · Today at 4:30pm" with no name, and the words written are drawn as "Note hidden" (what is typed is kept, only its drawing is masked) (PRIV-03)

## Leads to

- Back to the screen that opened it: Home, Card view, or Home when it was opened by the notification (the page opens over Home, so Back lands there), after "Save note", "Not now", "Discard" or Back
- "Go back" on the not-found message, the same way

## Tests that pin it

- `PostCallNoteViewModelTest` (added 2026-10-07: the timer's start survives recreation; the named call, or else the latest connected call, never a logged connection; NotFound for a missing person or a malformed id; Error and Try again; the draft survives recreation and Discard forgets it; Save writes one note, says "Note saved" and leaves; Save twice writes once; a failed save says so and keeps the words)
- `PostCallNoteContentTest` (added 2026-10-07: the title and the call line; the timer in m:ss and h:mm:ss, spoken by the minute with no live region; Save waits for words; "Not now" with nothing written leaves, with words asks; Keep writing and Discard; Back with words asks; the curtain; the not-found message)
- `OrbitNavHostTest` (added 2026-10-07: Home's and Card view's "Add a note" open this page; the notification's route opens it over Home; leaving pops once, even when asked twice)
- `RoutesTest` (the route with and without a call)
- Gallery previews: `PostCallNoteContentEmptyPreview`, `PostCallNoteContentWritingPreview`, `PostCallNoteContentIncomingPreview`, `PostCallNoteContentCurtainPreview`, `PostCallNoteContentDiscardPreview`, `PostCallNoteContentLoadingPreview`, `PostCallNoteContentNotFoundPreview`, `PostCallNoteContentErrorPreview`, with the curtain pass
