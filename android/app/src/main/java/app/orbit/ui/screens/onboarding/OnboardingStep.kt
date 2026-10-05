package app.orbit.ui.screens.onboarding

import androidx.compose.runtime.Immutable

/**
 * Single source of truth for the onboarding flow's counted steps.
 *
 * 5 counted steps. Welcome and Done are framing screens that bracket the flow
 * but are NOT counted — `OnboardingScaffold(step = null)` skips the dot row on
 * those.
 *
 * Order puts the most-impactful permission first, so a half-bail still leaves
 * Orbit functional:
 *   1. Permissions · Contacts
 *   2. Permissions · Call log
 *   3. Permissions · Notifications  (auto-skipped on API <33; see
 *      OnboardingPermNotificationsScreen)
 *   4. Reading your call history    (blocking sync gate, ONB-16/17/18)
 *   5. Make your first list         (production List Configuration screen
 *      reused via shared ListConfigBody, ONB-20)
 *
 * Adding/removing a step here updates every progress indicator without
 * touching individual screens. Total advances in lockstep.
 */
@Immutable
enum class OnboardingStep(val ordinal1: Int, val total: Int) {
    PermContacts(1, 4),
    PermCallLog(2, 4),
    // Retired from the flow by ONB-30 (asked on the Done screen instead);
    // kept so a saved resume step still parses. Shares Sync's position.
    PermNotifications(3, 4),
    Sync(3, 4),
    FirstList(4, 4),
}
