package app.orbit.notify

/**
 * Collision-free notification ID helpers — RESEARCH Pitfall 5.
 *
 * The Android `NotificationManager` identifies notifications by an integer ID.
 * When an app posts multiple notification categories (list nudges + follow-ups),
 * the ID ranges must not overlap, or a nudge for list 42 could silently dismiss
 * a follow-up for contact 42.
 *
 * ### Base offsets (Pitfall 5 verbatim)
 * | Category | Base offset | Range |
 * |---|---|---|
 * | List nudge | 2_000_000 | 2_000_001 … 2_999_999 |
 * | Follow-up | 3_000_000 | 3_000_001 … 3_999_999 |
 *
 * The modulo 1_000_000 keeps the result within a safe Int range even for large
 * auto-increment Room IDs. In practice Orbit lists and contacts stay well below
 * 1_000_000 in any realistic lifetime, so the modulo is a safety cap, not a
 * collision risk.
 */
object NotificationIds {

    /**
     * Notification ID for the list-nudge notification of [listId].
     *
     * Base offset 2_000_000; maps list row ID → int in [2_000_001, 2_999_999].
     */
    fun listPrompt(listId: Long): Int = (2_000_000L + (listId % 1_000_000L)).toInt()

    /**
     * Notification ID for the incoming follow-up notification for [contactId].
     *
     * Base offset 3_000_000; maps contact row ID → int in [3_000_001, 3_999_999].
     */
    fun followUp(contactId: Long): Int = (3_000_000L + (contactId % 1_000_000L)).toInt()
}
