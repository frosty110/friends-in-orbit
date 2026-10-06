# Card view

**Route:** `card/{listId}`
**Group:** Core loop
**Status:** active
**Last reviewed:** 2026-10-06
**Spec:** [card-view](../card-view/README.md): CARD-01, CARD-02, CARD-03, CARD-04, CARD-05, CARD-06, CARD-07 (defined this round), CORE-04; NOTE-02 in [contact-detail](../contact-detail/README.md); PRIV-03 and PRIV-05 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Home: tap a list card
- Lists: tap a row (LIST-23)
- A nudge: tapping it opens the deck of the list it came from
- A widget: tapping a person opens their list's deck
- The "Call next" launcher shortcut: opens the deck whose next person it names

## What the user sees

- App bar: Back, the list's name as the title once the list is known ("List" under the curtain; untitled while it loads or when it could not be read), and "More actions for {list}"
- When call log access is off, a quiet notice above the card: "Orbit can't see your calls, so cards won't move on by themselves." with "Open settings"
- One person at a time: a small label "Up now" or "Coming up" (a fact about the rhythm, never a deadline), their photo or initials, their name, and why now in human terms (CARD-04): when you last spoke ("You spoke today.", "You spoke yesterday.", "You spoke 3 weeks ago."), the pair's usual rhythm once there are four calls ("You usually talk about every 2 weeks."), and the last note you wrote about them in quotation marks with "Your note, 12 days ago"
- "Usually answers": when they tend to pick up, from past calls, with an info tip ("Based on when you usually answer or call this person."); "Not enough calls yet to see a pattern" until there is one
- Three stats, worded as everywhere else: "Last call", "Average length", "Total calls" ("Never called" and "Not enough calls yet" where there isn't the history)
- Below the card: "Later", the labelled "Call {first name}", and "Sooner"; then "Open details"
- In landscape on a phone the card sits on the left and its actions in a column beside it; with large text, Call takes its own row (CARD-06)
- The one accent element: the Call button, the only control that dials (CARD-01, rules.md Design 6)

## Actions and menus

- "Call {first name}": opens the dialer with the number filled in; Orbit never places the call itself (PRIV-05)
- Tap the card face, or "Open details": opens the person's page; the face never dials (CARD-01)
- "Later" (or a left swipe): moves them further out on this list; "{Name} will come up again {tomorrow / on Tuesday / in 2 weeks}." with Undo. "Sooner" (or a right swipe): brings them forward; "{Name} comes up {when}." with Undo. Each undo is its own, and a swipe that commits gives a haptic (CARD-02)
- A move that could not be saved says so: "Couldn't move {name} to later. Try again.", "Couldn't move {name} sooner. Try again.", "Couldn't undo that. Try again."
- After a call placed from the card, once the call log confirms it and the deck moves on by itself (CORE-04): "Called {first name}" with "Add a note", which opens the person with the note field focused (CARD-03, NOTE-02)
- "More actions for {list}", in order: "Browse people" (opens Browse for this list), "Add people" (opens the Add people picker; not offered on smart lists, nor on the Loading and Error decks, where the list's type is not yet known), "List settings"
- "Open settings" on the notice: opens Orbit's Settings, where the Call log row hosts the grant
- "Go home" on the empty, quiet and error decks: leaves the deck the same way Back does, to the screen that opened it; on the error deck for a list id that never parsed it is the one action

## States

- Loading: quiet chrome (the app bar, untitled until the list loads) until the list and its people are known; never a false empty deck
- No one on the list: "No one is in this list yet." / "Add a few people to start surfacing names." with "Add people" and "Go home". On a smart list: "No one matches this rule right now." with "List settings" and "Go home"
- All quiet (CARD-05): "All quiet for now." and who comes up next and when ("Sam comes up in 2 weeks.", or "No one needs a call right now."), with "Browse this list" and "Go home"; never "caught up"
- Error (CARD-07): "Something's off here." / "Nothing is lost. Try again in a moment." with Try again (the accent) and "Go home". A malformed list id is this error, never an empty deck, with "Go home" alone as the accent: there is nothing Try again could re-read. With no list name in the app bar, TalkBack announces the deck by its heading
- Call log access off: the notice above the card; the deck still works, but only Later, Sooner and your own undo move it on
- A paused person is skipped, and "All quiet for now" names when they come back; a pause until you unpause is not named
- Privacy curtain: the name reads "Contact" and the initials come from that word, the last note is not shown at all, the title reads "List" (PRIV-03)

## Leads to

- The dialer (Call)
- Contact detail (the face, "Open details"; "Add a note", with the note field focused)
- Browse people (menu; "Browse this list")
- The Add people picker (menu; the empty deck); it returns here with "Added 3 people to {list}" and Undo
- List settings (menu; the smart list's empty deck); Done or Back returns here
- Settings ("Open settings")
- "Go home" and Back both return to where the deck was opened from: Home, or Lists; and Home when the deck was opened by a nudge, a widget or the shortcut

## Tests that pin it

- `CardViewViewModelTest` (Ready before any empty state, Error on a failed read and recovery after Try again, the smart list's empty state, the pause hint, a malformed id and its unchanged state after Try again)
- `CardViewViewModelInteractionTest` (Later and Sooner with their undo, failure snackbars, the Called acknowledgement and what cancels it, the rhythm sentence)
- `CardListMenuTest` (added this round: menu order, Add people absent on smart lists and while the list's type is unknown)
- `CardViewScreenTest` (added this round: the face opens details and never dials; Call is on screen in landscape; a failed read offers Try again and Go home while a malformed id offers Go home alone; every Error deck has a pane title)
- `WhyLineVoiceTest` (added this round: the rendered why-now line, for one gap in every bucket, breaks no voice rule)
- `OrbitNavHostTest` (added this round: "Add a note" opens the person with the note field focused, NOTE-02; a nudge for the deck already open does not stack a second deck, while another list gets its own)
- Gallery previews: `CardViewContentPreview`, `CardViewContentAheadOfTodayPreview`, `CardViewContentLongNamesPreview`, `CardViewContentNoMembersPreview`, `CardViewContentNothingEligiblePreview`, `CardViewContentCallLogDeniedPreview`, and the Loading, Error and bad-link (`CardViewContentBadLinkPreview`) previews added this round, with the curtain pass
