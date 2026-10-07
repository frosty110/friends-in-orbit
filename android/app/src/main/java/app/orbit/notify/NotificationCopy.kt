package app.orbit.notify

import app.orbit.R
import app.orbit.ui.util.UiText

/**
 * Centralized notification copy for Orbit, NOTIF-05 / NOTIF-09.
 *
 * The one place that decides what a nudge says: which sentence, with which
 * name. The words themselves live in `strings_notify.xml` (UX rubric 3.4), so
 * every function here returns [UiText] and the builder ([NudgeNotification],
 * [OrbitNotifications]) resolves it with its Context.
 *
 * All copy is voice-gated: sentence case, no shame framing, no exclamation
 * marks. A nudge names at most one person, the list's next (NOTIF-14), and
 * the lock-screen version names no one (NOTIF-13). The companion
 * [CopyAuditTest] resolves every string and enforces these invariants on
 * every CI run.
 *
 * ### Notification templates (D-18)
 * - List nudge title: [nudgeTitle]: raw list name, never truncated here
 * - List nudge body: [nudgeBody]: opportunity framing, name-free; one person or "a few" by due count
 * - Named nudge: [nudgeNamedBody] + [callActionLabel], built from [firstNameOf]
 * - Lock-screen version: [PUBLIC_TITLE] / [PUBLIC_BODY]
 * - After a call (NOTIF-16, not a nudge): [postCallTitle] / [POST_CALL_BODY],
 *   and the lock-screen [POST_CALL_PUBLIC_TITLE]
 *
 * ### Channel strings (D-16)
 * - [CHANNEL_LABEL_LIST_PROMPTS] / [CHANNEL_DESC_LIST_PROMPTS]: orbit.list_prompt channel
 * - [CHANNEL_LABEL_AFTER_CALL] / [CHANNEL_DESC_AFTER_CALL]: orbit.after_call channel
 *
 * The nudge schedule editor's words (List settings: "Add time", the summary
 * line, the paused badge) used to live here too; they are List settings copy
 * and moved to `strings_lists.xml` (`lists_nudge_*`) on 2026-10-05.
 */
object NotificationCopy {

    // -------------------------------------------------------------------------
    // Notification title / body functions
    // -------------------------------------------------------------------------

    /**
     * Title for a list-nudge notification.
     *
     * Returns the raw list name verbatim, with no suffix, no punctuation. If the
     * system tray truncates a long name that is acceptable Android behavior.
     * A name the user typed is data, not copy, so it stays a String.
     */
    fun nudgeTitle(listName: String): String = listName

    /**
     * Body for a list-nudge notification.
     *
     * Opportunity framing, never a backlog count: it surfaces that someone is ready
     * to be reached, not that "N are due". Name-free (lock-screen safe) and sentence
     * case. [dueCount] only selects the one-person or the "a few people" sentence:
     * the exact number is deliberately never shown, so a large list never reads as
     * a debt to clear (which is why this is a branch, not a `<plurals>`). The
     * "a few" form offers "start with one" to remove the all-or-nothing pressure.
     * [dueCount] should be ≥ 1; the worker is responsible for not posting at 0.
     */
    fun nudgeBody(listName: String, dueCount: Int): UiText =
        if (dueCount <= 1) {
            UiText.res(R.string.notify_nudge_body_one, listName)
        } else {
            UiText.res(R.string.notify_nudge_body_few, listName)
        }

    /**
     * NOTIF-14: body of a nudge that hands over the list's next person by name.
     *
     * The same invitation as [nudgeBody], said about one person: who is ready
     * when you are, never how long it has been. No count, because a name is
     * the whole suggestion; the plural "start with one" framing has nothing to
     * add once the one is chosen.
     */
    fun nudgeNamedBody(firstName: String): UiText = UiText.res(R.string.notify_nudge_named_body, firstName)

    /**
     * NOTIF-14: label of the nudge's call action. It opens the dialer with
     * the number filled in; the user places the call there (PRIV-05).
     */
    fun callActionLabel(firstName: String): UiText = UiText.res(R.string.notify_call_action, firstName)

    /**
     * The name a nudge uses for a person: the first word of their display
     * name, as Card view's "Called {first name}" does. A one-word name ("Mom")
     * is used whole.
     */
    fun firstNameOf(displayName: String): String = displayName.trim().substringBefore(' ')

    /**
     * NOTIF-13: the lock-screen version of every nudge. Android shows this in
     * place of the nudge on a secure lock screen whenever the user's settings
     * hide sensitive content, so it carries no person, no list name, no note
     * and no face: only that someone is ready. Fixed resources with no
     * arguments, so nothing a caller passes can reach it.
     */
    val PUBLIC_TITLE: UiText = UiText.res(R.string.notify_public_title)

    /** NOTIF-13: body of the lock-screen version. */
    val PUBLIC_BODY: UiText = UiText.res(R.string.notify_public_body)

    // -------------------------------------------------------------------------
    // Channel labels + descriptions (D-16)
    // -------------------------------------------------------------------------

    /** Channel label shown in Android system notification settings (orbit.list_prompt). */
    val CHANNEL_LABEL_LIST_PROMPTS: UiText = UiText.res(R.string.notify_channel_list_nudges)

    /** Channel description shown in Android system notification settings (orbit.list_prompt). */
    val CHANNEL_DESC_LIST_PROMPTS: UiText = UiText.res(R.string.notify_channel_list_nudges_description)

    // -------------------------------------------------------------------------
    // After a call (NOTIF-16)
    // -------------------------------------------------------------------------

    /**
     * NOTIF-16: the title of the notification after a call, by first name.
     * An invitation about a call that just happened, never a count and never
     * how long ago: "How was your call with Kai?"
     */
    fun postCallTitle(firstName: String): UiText = UiText.res(R.string.notify_post_call_title, firstName)

    /** NOTIF-16: its body, the same words Home's old banner used. */
    val POST_CALL_BODY: UiText = UiText.res(R.string.notify_post_call_body)

    /**
     * NOTIF-16: the lock-screen version's title, with no name (NOTIF-13's
     * rule for every notification Orbit posts). Its body is [POST_CALL_BODY],
     * which names no one either.
     */
    val POST_CALL_PUBLIC_TITLE: UiText = UiText.res(R.string.notify_post_call_public_title)

    /** NOTIF-16: the "After a call" channel's name and description in Android's settings. */
    val CHANNEL_LABEL_AFTER_CALL: UiText = UiText.res(R.string.notify_channel_after_call)
    val CHANNEL_DESC_AFTER_CALL: UiText = UiText.res(R.string.notify_channel_after_call_description)
}
