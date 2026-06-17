package app.orbit.ui.screens.onboarding

import androidx.compose.runtime.Immutable

/**
 * OnboardingPermissions state contract (ARCH-02). Sealed interface;
 * the single [Ready] variant is `@Immutable` for Compose skipping.
 *
 * The VM only tracks what it can observe via
 * [androidx.core.content.ContextCompat.checkSelfPermission]; granted/denied
 * transitions are refreshed explicitly from the Screen on `ON_RESUME`. No
 * `PermissionSource` interface is authored.
 *
 * On-device verification covers permission-flag correctness. JVM tests verify
 * only that the StateFlow emits the right shape under `onRefresh()`.
 */
sealed interface OnboardingPermissionsUiState {

    @Immutable
    data class Ready(
        val hasCallLog: Boolean,
        val hasContacts: Boolean,
        val hasNotifications: Boolean,
        // Per-permission "we have asked the OS at least once" flags. Drives
        // [OnboardingPermScreen.isPermanentlyDenied] short-circuit on first
        // launch so the system permission dialog appears on first tap rather
        // than the deep-link "Open settings" CTA.
        val hasAskedCallLog: Boolean = false,
        val hasAskedContacts: Boolean = false,
        val hasAskedNotifications: Boolean = false,
    ) : OnboardingPermissionsUiState
}
