# Call history

**Route:** `call-log` (everyone's calls) · `call-log?contactId={contactId}` (one person's)
**Group:** Settings & data
**Status:** active
**Last reviewed:** 2026-10-05
**Index:** [Page views](../PAGE_VIEWS.md)

What a user expects to see or do here:

- Scroll all calls chronologically with direction, duration, list context
- From a person's page, "View all calls" shows only calls with them, titled "Calls with {name}", and back returns to them (LOG-04 in [call-history](../call-history/README.md))
- Filter by All / Incoming / Outgoing
- Ignored people are visibly greyed and labelled
- "Show 200 more" to page further back
- Tap a row to open the contact
- "No calls yet" only when there truly are none; "Orbit can't see your calls" with a way to Settings when call log access is off; a notice above the rows when access is off but history exists; "Couldn't load your calls" with Try again when reading fails (LOG-05)
