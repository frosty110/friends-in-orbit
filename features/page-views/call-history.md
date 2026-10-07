# Call history

**Route:** `call-log` (everyone's calls); `call-log?contactId={contactId}` (one person's calls, LOG-04)
**Group:** Settings & data
**Status:** active
**Last reviewed:** 2026-10-07
**Spec:** [call-history](../call-history/README.md): LOG-01, LIST-24 in [orbit-lists](../orbit-lists/README.md), LOG-03 (defined this round), LOG-04, LOG-05; IGNORE-09 in [settings](../settings/README.md) (defined this round); PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Settings: the "Call history" row
- Contact detail: "View all calls" in the overflow (one person's calls)

## What the user sees

- App bar: Back, and "Call history" or "Calls with {name}" ("Call history" when the name is not known)
- Filter chips "All", "Incoming", "Outgoing", one always chosen; the row scrolls sideways rather than breaking a label at large text
- When call log access is off but calls were recorded earlier, a notice above them: "Orbit can't see new calls, so some may be missing here." with "Open settings"
- Rows grouped under sticky day headers: "Today", "Yesterday", then "Wednesday 3 June"
- Each row: face, name (greyed, with "(ignored)", for someone you ignore), "from {list}" (their newest list that isn't archived; LIST-24), the length, an icon and word for its kind ("Outgoing", "Incoming", "Logged" for a connection you logged by hand, "Attempted" for a call that did not connect; the last two show no length), the time ("4:30pm"), and a "More actions for {name}" button
- In one person's log the rows say what happened instead: "You called", "{Name} called", "You logged a connection", "You tried to reach them"
- A footer, "Show 200 more" (n is how many come next), until everything is shown
- No control on the page is in the accent; the denied state's "Open settings" and the error's Try again carry their own

## Actions and menus

- Tap a row: opens the person's page scrolled to that call, with "Add note to this call" under it (LOG-03)
- "More actions for {name}" (also a long-press, with a haptic; TalkBack: "Quick actions"), in order: "Call again" (opens the dialer), "Open details"
- The filter chips narrow by direction; a connection you logged and an attempt count as reaching out, so they show under "All" and "Outgoing" and not under "Incoming"
- "Show n more" loads the next page
- "Open settings": opens Orbit's Settings, where the Call log row hosts the grant; when the log was opened from Settings it goes back there instead of stacking a second Settings

## States

- Loading: a quiet skeleton until both the calls and the permission are known (LOG-05)
- Empty: "No calls yet" / "Calls with the people in your contacts show up here."; for one person: "No calls with {first name} yet" / "When you and {first name} talk, the call shows up here."
- Call log access off and nothing recorded: "Orbit can't see your calls" / "Call history comes from your phone's call log, and Orbit doesn't have access to it. Turn it on in Settings and your calls will appear here. They stay on this device." with "Open settings"
- Access off with history: the notice above the rows
- A filter that matches nothing: the chips stay, with "No incoming calls yet." or "No outgoing calls yet."
- Error: "Couldn't load your calls" / "Nothing is lost. Try again in a moment." with Try again, which recovers (LOG-05)
- Ignored people stay in the log, greyed and labelled, and their rows still open (IGNORE-09)
- Privacy curtain: names read "Contact" (rows and the title), no photos are shown, and the "from {list}" context is left out (PRIV-03)

## Leads to

- Contact detail, scrolled to the call with the note field focused (tap a row; "Open details")
- The dialer ("Call again")
- Settings ("Open settings"); back to it when the log was opened from there
- Back returns to Settings, or to the person when opened from their page

## Tests that pin it

- `CallLogViewModelTest` (an archived list is never the "from {list}"; day grouping, one-person mode, the Logged and Attempted kinds with no length, the filters, ignored rows, denied and unknown permission, Error and recovery, paging)
- `CallLogRowMenuTest` (added this round: "Call again", "Open details", neither destructive)
- `CallEventDaoLogTest`
- `OrbitNavHostTest` (added this round: "Open settings" pops back when Call history came from Settings and pushes Settings otherwise, LOG-05; "View all calls" and back return to the same person, LOG-04)
- Gallery previews: `CallLogContentPreview`, `CallLogPersonPreview`, `CallLogLoadingPreview`, `CallLogEmptyPreview`, `CallLogPermissionDeniedPreview`, `CallLogDeniedWithHistoryPreview`, `CallLogErrorPreview`, the filtered-empty, ignored-row and one-person-error previews added this round, with the curtain pass
