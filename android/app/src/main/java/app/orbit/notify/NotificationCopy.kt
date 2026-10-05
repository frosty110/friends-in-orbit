package app.orbit.notify

/**
 * Centralized notification copy for Orbit — NOTIF-05 / NOTIF-09.
 *
 * All strings produced by this object are voice-gated: sentence case, no shame
 * framing, no exclamation marks. A nudge names at most one person, the list's
 * next (NOTIF-14), and the lock-screen version names no one (NOTIF-13). The
 * companion [CopyAuditTest] enforces these invariants programmatically on every
 * CI run.
 *
 * This object has **zero Android imports** so it can be exercised in plain JVM
 * unit tests without Robolectric.
 *
 * ### Notification templates (D-18)
 * - List nudge title: [nudgeTitle] — raw list name, never truncated here
 * - List nudge body: [nudgeBody] — opportunity framing, name-free; singular/plural by due count
 * - Named nudge: [nudgeNamedBody] + [callActionLabel], built from [firstNameOf]
 * - Lock-screen version: [PUBLIC_TITLE] / [PUBLIC_BODY]
 *
 * ### Channel strings (D-16)
 * - [CHANNEL_LABEL_LIST_PROMPTS] / [CHANNEL_DESC_LIST_PROMPTS] — orbit.list_prompt channel
 *
 * ### Editor / UI copy (D-06, UI-SPEC Copywriting Contract)
 * - [LABEL_ADD_TIME], [LABEL_MUTED_BADGE], [SUMMARY_NO_DAYS], [SUMMARY_NO_TIME]
 * - [A11Y_ADD_TIME], [A11Y_DAY_SELECTED], [A11Y_DAY_UNSELECTED], [a11yRemoveTime]
 * - [scheduleSummary] — formats a NudgeSchedule into a human-readable summary line
 */
object NotificationCopy {

    // -------------------------------------------------------------------------
    // Notification title / body functions
    // -------------------------------------------------------------------------

    /**
     * Title for a list-nudge notification.
     *
     * Returns the raw list name verbatim — no suffix, no punctuation. If the
     * system tray truncates a long name that is acceptable Android behavior.
     */
    fun nudgeTitle(listName: String): String = listName

    /**
     * Body for a list-nudge notification.
     *
     * Opportunity framing, never a backlog count: it surfaces that someone is ready
     * to be reached, not that "N are due". Name-free (lock-screen safe) and sentence
     * case. [dueCount] only selects singular vs plural phrasing — the exact number is
     * deliberately never shown, so a large list never reads as a debt to clear. The
     * plural form offers "start with one" to remove the all-or-nothing pressure.
     * [dueCount] should be ≥ 1; the worker is responsible for not posting at 0.
     */
    fun nudgeBody(listName: String, dueCount: Int): String =
        if (dueCount <= 1) {
            "Someone in $listName is ready when you are. Want to call?"
        } else {
            "A few people in $listName are ready when you are. Start with one?"
        }

    /**
     * NOTIF-14: body of a nudge that hands over the list's next person by name.
     *
     * The same invitation as [nudgeBody], said about one person: who is ready
     * when you are, never how long it has been. No count, because a name is
     * the whole suggestion; the plural "start with one" framing has nothing to
     * add once the one is chosen.
     */
    fun nudgeNamedBody(firstName: String): String =
        "$firstName is ready when you are. Want to call?"

    /**
     * NOTIF-14: label of the nudge's call action. It opens the dialer with
     * the number filled in; the user places the call there (PRIV-05).
     */
    fun callActionLabel(firstName: String): String = "Call $firstName"

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
     * and no face: only that someone is ready.
     */
    const val PUBLIC_TITLE = "Someone is ready when you are"

    /** NOTIF-13: body of the lock-screen version. */
    const val PUBLIC_BODY = "Want to call?"

    // -------------------------------------------------------------------------
    // Channel labels + descriptions (D-16)
    // -------------------------------------------------------------------------

    /** Channel label shown in Android system notification settings (orbit.list_prompt). */
    const val CHANNEL_LABEL_LIST_PROMPTS = "List nudges"

    /** Channel description shown in Android system notification settings (orbit.list_prompt). */
    const val CHANNEL_DESC_LIST_PROMPTS = "A gentle nudge when you have someone to call."

    // -------------------------------------------------------------------------
    // Editor UI copy (UI-SPEC Copywriting Contract)
    // -------------------------------------------------------------------------

    /** Label for the "Add time" affordance in the NudgeScheduleSection editor. */
    const val LABEL_ADD_TIME = "Add time"

    /** Text for the "Muted" badge when nudges are paused (notificationsEnabled = false). */
    const val LABEL_MUTED_BADGE = "Muted — nudges paused"

    /** Summary line when no days are selected — uses micro/subtle style. */
    const val SUMMARY_NO_DAYS = "No days selected — nudges off"

    /** Summary line when no times are configured — uses micro/subtle style. */
    const val SUMMARY_NO_TIME = "No time set — tap 'Add time'"

    // -------------------------------------------------------------------------
    // Accessibility labels
    // -------------------------------------------------------------------------

    /** contentDescription for the "Add time" affordance. */
    const val A11Y_ADD_TIME = "Add a nudge time"

    /** contentDescription template for a selected day chip (e.g. "Monday — selected"). */
    const val A11Y_DAY_SELECTED = "selected"

    /** contentDescription template for an unselected day chip (e.g. "Monday — unselected"). */
    const val A11Y_DAY_UNSELECTED = "unselected"

    /**
     * contentDescription for the remove-time (X) button on a time chip.
     *
     * Usage: `a11yRemoveTime(formattedTime)` → "Remove 10:00 am"
     */
    fun a11yRemoveTime(formattedTime: String): String = "Remove $formattedTime"

    /**
     * contentDescription for a day chip in the NudgeScheduleSection editor.
     *
     * Usage: `a11yDayChip("Monday", selected = true)` → "Monday — selected"
     */
    fun a11yDayChip(fullDayName: String, selected: Boolean): String =
        "$fullDayName — ${if (selected) A11Y_DAY_SELECTED else A11Y_DAY_UNSELECTED}"

    // -------------------------------------------------------------------------
    // Schedule summary helpers (D-06 format table)
    // -------------------------------------------------------------------------

    /**
     * Formats a human-readable schedule summary from pre-formatted inputs.
     *
     * This helper is pure (no Android / Compose dependency) so it is JVM-testable.
     * The caller is responsible for pre-formatting [timeStrings] (e.g. "10am", "9:30pm")
     * and [dayGroupLabel] according to the D-06 table below.
     *
     * **D-06 format rules:**
     * | Schedule state | Result |
     * |---|---|
     * | All 7 days, one time | "Every day at {t}" |
     * | Mon–Fri, one time | "Weekdays at {t}" |
     * | Sat–Sun, one time | "Weekends at {t}" |
     * | All 7 days, two times | "Every day at {t1} and {t2}" |
     * | Other combination, one time | "{days} at {t}" |
     * | Any combination, multiple times | "{dayGroup} at {t1} and {t2}" |
     *
     * @param dayGroupLabel Pre-computed label such as "Every day", "Weekdays", "Weekends",
     *   or a comma-separated short-name list like "Mon, Wed, Fri".
     * @param timeStrings Non-empty list of pre-formatted time strings.
     * @return A sentence-case summary string with no trailing period.
     */
    fun scheduleSummary(dayGroupLabel: String, timeStrings: List<String>): String {
        val timePart = when (timeStrings.size) {
            1 -> timeStrings[0]
            2 -> "${timeStrings[0]} and ${timeStrings[1]}"
            else -> timeStrings.dropLast(1).joinToString(", ") + ", and ${timeStrings.last()}"
        }
        return "$dayGroupLabel at $timePart"
    }
}
