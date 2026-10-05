package app.orbit.notify

import app.orbit.data.entity.ContactEntity

/**
 * Who a list's nudge names, if anyone (NOTIF-14, NOTIF-15).
 *
 * A nudge stays one per list per scheduled slot (ADR 0009). What changed is
 * that it hands over the suggestion itself: [head], the person the list's
 * Card view shows first (`SurfaceNextUseCase`), so the nudge and the deck it
 * opens agree.
 *
 * NOTIF-15: a person is named once. Mission principle 6 forbids per-person
 * nags, and a scheduled nudge that kept naming the same person every evening
 * until they were called would be exactly that: ignoring a nudge is not a
 * "no", but it must not earn a repeat either. So when the previous nudge for
 * this list already named [head], this one goes out without a name (the
 * established "Someone in {list}" copy, no face, no Call action). A name
 * returns as soon as someone else is next: after a call, a Later or a Sooner
 * moves the deck on.
 *
 * Rejected: naming the second person in the queue instead. The nudge's body
 * opens the list's deck, which would then show a different person on top
 * from the one the nudge named.
 *
 * A contact with a blank display name gets the name-free copy too: "Call "
 * with nothing after it is worse than no name.
 */
fun nudgeSubject(head: ContactEntity?, lastNamedContactId: Long?): ContactEntity? =
    head?.takeIf { it.id != lastNamedContactId && it.displayName.isNotBlank() }
