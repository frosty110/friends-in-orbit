# Add contacts / picker

**Route:** `pick/contacts`
**Group:** Lists
**Status:** active
**Last reviewed:** 2026-10-05
**Index:** [Page views](../PAGE_VIEWS.md)

What a user expects to see or do here:

- Pick people from the address book into a list (Add / Move / Copy — title reflects mode)
- Search by name
- Sort: Alphabetical / Most called / Recently saved
- Filter by call frequency, recency, list membership; applied filters sit in their own always-visible row
- Filters with no matches are disabled and pushed to the end
- "Select all matching" for the current filter set
- A docked bottom bar shows the count and commits ("Add N to {list}"); never hides the last row
- "Skip for now" during onboarding
- Re-link mode (from an orphaned contact's Re-link, `pick/contacts?mode=relink&relinkContactId={id}`): title "Re-link contact"; pick exactly one person (a new pick replaces the old); no "Select all matching"; only other contacts mirrored from the phone that are not orphaned, ignored or archived are listed; the button reads "Re-link {orphan's name}"; "Contact not found" if the route's contact is missing
- After a re-link (the pick merges into the orphan, CONTACT-07 in [contact-detail](../contact-detail/README.md)): "Re-linked to {phone contact name}" with Undo (Undo splits the two contacts back), or "Couldn't save that" if the merge was refused, with nothing changed
