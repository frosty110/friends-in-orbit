# Contact detail

**Route:** `contact/{contactId}`. Optional `focusNote=1` puts the cursor in the note field once the page settles (NOTE-02); optional `scrollToCallEventId={id}` scrolls to that call and offers "Add note to this call" under it (LOG-03)
**Group:** People
**Status:** active
**Last reviewed:** 2026-10-06
**Spec:** [contact-detail](../contact-detail/README.md): CONTACT-01 to CONTACT-08, CONTACT-09 (defined this round), NOTE-01, NOTE-02; LOG-03 and LOG-04 in [call-history](../call-history/README.md); PRIV-03, PRIV-05 and PRIV-07 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Card view: the card face or "Open details"; "Add a note" on the Called snackbar (note field focused)
- Browse: tap a row
- Search: tap a row
- Home: a row on the rhythm day sheet. (Until 2026-10-07 the post-call banner's "Add a note" opened this page with the note field focused; Home's waiting calls open the note page now, NOTE-04)
- Call history: tap a row, or "Open details" (scrolled to that call, with "Add note to this call" under it)

## What the user sees

- App bar: Back and "More actions for {name}"; no title (the hero carries the name, which is also the pane title TalkBack announces; a page with no person is announced by its message heading)
- When a timed pause has run out, a banner at the top: "{Name} is unpaused" / "They'll come up again on their lists.", with a dismiss control (CONTACT-05)
- The hero: photo or initials, the name, the number (tappable; TalkBack: "Call {number}"), and a status line where one applies: "Paused until 12 Oct", "Paused until you unpause", "Ignored", "Archived"
- "Call" and "Log a connection"
- "On these lists": a chip per list, or "Not on any list yet"; "Add to lists"
- "Stats": "Last call", "Total calls", "Average length", "Longest gap" and "Usually" (the part of the day you talk: "Mornings", "Evenings") with an info tip; a stat without enough history says "Not enough calls yet" (CONTACT-02)
- "Custom schedule", only for someone on two or more lists: "Follows the keep in touch rhythm from {list}." with "Set a schedule for this person"; once set, the rhythm picker, an "Aim for every 14 days" slider and "Reset to default" (CONTACT-03)
- "Notes": a field ("Add a note") with "Add", then every note, newest first, with when it was written (NOTE-01)
- "Recent calls": each with an icon for its kind, its length (or "Logged" / "Attempted"), and how long ago; "View all calls" in the overflow for the rest
- The one accent element: "Call" (rules.md Design 6)

## Actions and menus

- "Call", or tapping the number: opens the dialer with the number filled in; Orbit never places the call itself (PRIV-05)
- "Log a connection" opens a sheet for a call or visit Orbit couldn't see: "We connected" or "Couldn't reach them" ("A voicemail or no answer: you reached out but didn't connect."), "Today" / "Yesterday" / "Pick a date", "Add a note (optional)", then "Log connection" or "Log attempt"; confirmed by "Logged." or "Attempt logged."; the call row reads "Logged" or "Attempted", and an attempt stays out of the stats (CONTACT-09)
- "More actions for {name}", in order: "View all calls", "Pause" (or "Unpause" while paused), "Open in Contacts" (when the person is in your phone's contacts), then "Ignore" (or "Unignore" while ignored)
  - "Pause" opens "Pause for how long?" with "1 week", "1 month", "Until you unpause"; then "Paused {name} for 1 week", "Paused {name} for 1 month" or "Paused {name} until you unpause", with Undo (CONTACT-04). "Unpause": "Unpaused {name}"
  - "Ignore": "Ignored {name}" with Undo. "Unignore": "Unignored {name}" with Undo. Pause is not offered while the person is ignored
  - "Open in Contacts": hands them to your phone's contacts app
- The unpause banner's dismiss closes it
- "Add to lists": opens the Add to lists picker
- Notes: "Add" saves ("Note saved"). Each note's "More actions for this note" offers "Edit" and "Delete" ("Note deleted" with Undo); a long-press edits and a swipe deletes, as shortcuts to the same menu. Tapping a note's time switches between "3 days ago" and the date
- Arriving from Call history: "Add a note about this call" sits under that call with "Add note to this call", and the note is dated to the call (LOG-03)
- Custom schedule: "Set a schedule for this person", the rhythm picker and the slider save as you go; "Reset to default" returns to the list's rhythm
- The orphan banner: "Re-link" opens the Add people picker in Re-link mode; it returns here with "Re-linked to {phone contact}" and Undo (Undo splits them again), or "Couldn't save that" with nothing changed (CONTACT-07). "Archive": "Archived {name}" with Undo
- A write that fails says so: "Couldn't add note", "Couldn't log that", "Couldn't pause {name}", "Couldn't ignore {name}", "Couldn't archive {name}", "Couldn't undo"

## States

- Loading: a quiet placeholder shaped like the hero
- Not found: "This person isn't in Orbit anymore" / "They may have been removed from your phone's contacts." with "Go back" (the accent of that state)
- Error: "Couldn't load this person" / "Nothing is lost. Try again in a moment." with Try again, which re-subscribes every source (CONTACT-08)
- Orphaned (the phone contact was deleted, CONTACT-06): a banner "This contact was deleted from your phone" / "History stays here. Re-link to a phone contact, or archive to remove from lists." with "Re-link" and "Archive"; the name, number, lists, stats, calls and notes stay readable, and "View all calls" stays in the overflow; Log a connection, Add to lists and the custom schedule wait until the person is re-linked
- Paused: the status line, and "Unpause" in the overflow
- Ignored: the status line "Ignored", "Unignore" in the overflow, no Pause. Archived: the status line "Archived"
- Call log access off: a notice above the stats, "Orbit can't see your phone calls" with "Open settings"; "Never called", "Not enough calls yet" and "No calls yet." are not claimed, while "Total calls" and any calls or connections already recorded stay
- Empty parts: "Not on any list yet", "No notes yet.", "No calls yet."
- Privacy curtain: the name and title read "Contact", the number reads "Number hidden" and does not dial (PRIV-07), list names read "List" ("its list" in the schedule sentence), note bodies "Note hidden", and no photo is shown (PRIV-03)

## Leads to

- The dialer ("Call", the number)
- Call history for this person ("View all calls"); Back returns here (LOG-04)
- The Add to lists picker; it returns here with "Added to 2 lists" and Undo
- The Add people picker in Re-link mode; it returns here
- Settings ("Open settings" on the call log notice)
- Your phone's contacts app ("Open in Contacts")
- Back returns to whichever screen opened the page

## Tests that pin it

- `ContactDetailViewModelTest` (Ready, Error and recovery, Orphaned with its notes, the ignored and archived flags, pause and unpause, ignore and undo, archive, logging a connection and an attempt, notes, focusNote, scrollToCallEventId, the lapsed-pause banner, failure snackbars)
- `ContactOverflowMenuTest` (the overflow's order and its Pause/Unpause, Ignore/Unignore and Open in Contacts variants)
- `ContactDetailCurtainTest` (every name, the number and the pane title under the curtain)
- `ContactDetailScreenTest` (added this round: arriving scrolled to a call shows that row and "Add note to this call"; the pane title is the name, or the message heading when there is no person)
- `NotesMenuTest` (added this round), `OrphanBannerTest` and `UnpauseBannerTest` (on the JVM from this round), `RuleOverrideSectionTest`
- Use cases: `PauseContactUseCaseTest`, `IgnoreContactUseCaseTest`, `UnignoreContactUseCaseTest`, `ArchiveContactUseCaseTest`, `RelinkContactUseCaseTest`, `AddNoteUseCaseTest`, `AddRetroactiveNoteUseCaseTest`, `EditNoteUseCaseTest`, `DeleteNoteUseCaseTest`
- `OrbitNavHostTest` (added this round: "View all calls" opens this person's calls and back returns to the same entry, LOG-04; "Open settings" leads to Settings)
- Gallery previews: `ContactDetailContentPreview`, `ContactDetailNewPersonPreview`, `ContactDetailNotFoundPreview`, `ContactDetailErrorPreview`, `ContactDetailCurtainPreview`, `LogConnectionSheetLightPreview`, `UnpauseBannerLightPreview`, the Orphaned and call-log-denied previews added this round, with the curtain pass
