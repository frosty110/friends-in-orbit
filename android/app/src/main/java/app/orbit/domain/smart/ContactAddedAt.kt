package app.orbit.domain.smart

import app.orbit.data.entity.ContactEntity
import java.time.Instant

/**
 * When this person was added to the phone, as near as Orbit can tell
 * (SMART-08). The one definition of "added" for every "Recently added"
 * surface: the smart rule [SmartListRule.RecentlyAddedNotCalled] and the
 * picker's Recently added sort.
 *
 * Neither column is that instant, but both are upper bounds on it: Orbit
 * cannot see a contact before it exists, and the device cannot have last
 * updated a contact before creating it. So the earlier of the two is the
 * closest bound there is.
 *
 *  - [ContactEntity.firstSeenByAppAt] is close only for people added after
 *    Orbit's first contacts sync. For everyone already in the address book it
 *    is that sync's instant, one value on every row (a restore from backup
 *    does the same with the import instant). Used alone, every never-called
 *    contact read as "added in the last 7 days" for a week after a fresh
 *    install, so the smart list held the whole address book (2026-10-07).
 *  - [ContactEntity.deviceUpdatedAt] is the device's last-updated time,
 *    frozen at first sight, so for those people it is their last edit before
 *    Orbit came along: usually long ago.
 *
 * Taking the earlier can only make a person look older than either column
 * says, never newer. A device timestamp can move someone out of "Recently
 * added" but can never bring an old contact in, which is why trusting it here
 * is safe where trusting it alone would not be. With no device timestamp
 * (some ROMs report none; a row the next ingest has not yet backfilled) this
 * is first sight, as before.
 */
fun contactAddedAt(firstSeenByAppAt: Instant, deviceUpdatedAt: Instant?): Instant =
    if (deviceUpdatedAt == null) firstSeenByAppAt else minOf(firstSeenByAppAt, deviceUpdatedAt)

/** [contactAddedAt] for a stored contact. */
val ContactEntity.addedAt: Instant
    get() = contactAddedAt(firstSeenByAppAt, deviceUpdatedAt)
