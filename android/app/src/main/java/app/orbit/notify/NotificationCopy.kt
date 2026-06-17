package app.orbit.notify

/**
 * Centralized notification copy for Orbit — NOTIF-05 / NOTIF-09.
 *
 * All strings produced by this object are voice-gated: sentence case, no shame
 * framing, no exclamation marks, no contact names in nudge bodies. The companion
 * [CopyAuditTest] enforces these invariants programmatically on every CI run.
 *
 * This object has **zero Android imports** so it can be exercised in plain JVM
 * unit tests without Robolectric.
 *
 * ### Notification templates (D-18)
 * - List nudge title: [nudgeTitle] — raw list name, never truncated here
 * - List nudge body: [nudgeBody] — "{N} due in {list name}."
 * - Follow-up title: [followUpTitle] — "{Name} called you." (the one name-bearing notification)
 * - Follow-up body: [followUpBody] — "Want to call back?"
 *
 * ### Channel strings (D-16)
 * - [CHANNEL_LABEL_LIST_PROMPTS] / [CHANNEL_DESC_LIST_PROMPTS] — orbit.list_prompt channel
 * - [CHANNEL_LABEL_FOLLOW_UPS] / [CHANNEL_DESC_FOLLOW_UPS] — orbit.incoming_followup.v2 channel
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
     * Format: "{dueCount} due in {listName}." — period terminates, sentence case.
     * [dueCount] should be ≥ 1; the worker is responsible for not posting at 0.
     */
    fun nudgeBody(listName: String, dueCount: Int): String =
        "$dueCount due in $listName."

    /**
     * Title for an incoming-call follow-up notification (D-14).
     *
     * This is the ONE notification template that may include a contact name.
     * Justification: the user just received a call from this person — the name
     * is directly tied to a concrete, user-initiated event.
     *
     * Format: "{contactName} called you." — period terminates.
     */
    fun followUpTitle(contactName: String): String = "$contactName called you."

    /**
     * Body for an incoming-call follow-up notification.
     *
     * Returns the fixed string "Want to call back?" — question form, no exclamation.
     */
    fun followUpBody(): String = "Want to call back?"

    // -------------------------------------------------------------------------
    // Channel labels + descriptions (D-16)
    // -------------------------------------------------------------------------

    /** Channel label shown in Android system notification settings (orbit.list_prompt). */
    const val CHANNEL_LABEL_LIST_PROMPTS = "List nudges"

    /** Channel description shown in Android system notification settings (orbit.list_prompt). */
    const val CHANNEL_DESC_LIST_PROMPTS = "A gentle nudge when you have someone to call."

    /** Channel label shown in Android system notification settings (orbit.incoming_followup.v2). */
    const val CHANNEL_LABEL_FOLLOW_UPS = "Incoming call follow-ups"

    /** Channel description shown in Android system notification settings (orbit.incoming_followup.v2). */
    const val CHANNEL_DESC_FOLLOW_UPS = "Ask to call back after an incoming call."

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
