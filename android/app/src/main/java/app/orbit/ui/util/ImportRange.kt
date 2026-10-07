package app.orbit.ui.util

import app.orbit.R

/**
 * How far back Orbit reads the call log, offered on two screens: Settings'
 * import range row and onboarding's sync step, both through
 * `ui/components/ImportRangeChipGroup.kt`. One list and one wording so the
 * same setting looks and reads the same on both (voice.md glossary: one word
 * for one idea). Before 2026-10-06 each screen kept its own copy, and
 * they had drifted: onboarding offered three windows to Settings' four and
 * said "90 days" where Settings said "3 months" (onb-9).
 */
val IMPORT_DAY_OPTIONS: List<Int> = listOf(30, 90, 180, 365)

/**
 * The label for an import window: "1 month", "3 months", "6 months",
 * "1 year". Words, not "90d" (rubric D7). Any other count falls back to days,
 * so a stored value from an older build still reads as something.
 */
fun importRangeLabel(days: Int): UiText = when (days) {
    30 -> UiText.plural(R.plurals.settings_import_range_months, 1, 1)
    90 -> UiText.plural(R.plurals.settings_import_range_months, 3, 3)
    180 -> UiText.plural(R.plurals.settings_import_range_months, 6, 6)
    365 -> UiText.plural(R.plurals.settings_import_range_years, 1, 1)
    else -> UiText.plural(R.plurals.settings_import_range_days, days, days)
}
