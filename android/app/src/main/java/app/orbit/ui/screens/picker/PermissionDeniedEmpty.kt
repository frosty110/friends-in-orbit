package app.orbit.ui.screens.picker

import android.content.res.Configuration
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.R
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.theme.OrbitTheme

/**
 * Permission denied empty state.
 *
 * The shared [OrbitScreenMessage] with a Primary "Open phone settings" that
 * deep-links to the OS app-detail Settings screen
 * (ACTION_APPLICATION_DETAILS_SETTINGS) so the user can flip READ_CONTACTS
 * back on.
 *
 * Copy:
 *   - Heading: "Contacts access is off"
 *   - Body:    "Turn it on in your phone's settings to add people to your
 *              lists. Your contacts stay on this device."
 *   - Primary: "Open phone settings"
 *
 * The launcher logic mirrors `SettingsScreen.kt:113-121`.
 * The `[onOpenSettings]` callback is wired via the screen-level `LocalContext`
 * so previews can pass a no-op without touching system Intents.
 */
@Composable
fun PermissionDeniedEmpty(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The shared people-screen message (2026-10-05). The button is the shared
    // "Open phone settings" (strings_components.xml, 2026-10-06): one wording
    // for the phone's page for Orbit everywhere (voice.md glossary; five
    // screens had five), and it says which settings: the phone's, not Orbit's.
    OrbitScreenMessage(
        icon = "shield-check",
        title = stringResource(R.string.picker_denied_title),
        body = stringResource(R.string.picker_denied_body),
        actionLabel = stringResource(R.string.components_action_open_phone_settings),
        onAction = onOpenSettings,
        // The only way forward from here, so it takes the screen's accent.
        actionVariant = OrbitButtonVariant.Primary,
        modifier = modifier,
    )
}

/**
 * Convenience helper — builds the standard Intent the picker screen passes to
 * [PermissionDeniedEmpty.onOpenSettings]. Lives at file scope so the screen
 * caller can hoist intent construction without duplicating the
 * `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` literal.
 */
internal fun buildOpenAppSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
    }

@Preview(name = "PermissionDeniedEmpty — light", showBackground = true)
@Composable
private fun PermissionDeniedEmptyPreviewLight() {
    OrbitTheme(darkTheme = false) {
        PermissionDeniedEmpty(onOpenSettings = {})
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "PermissionDeniedEmpty — dark", showBackground = true)
@Composable
private fun PermissionDeniedEmptyPreviewDark() {
    OrbitTheme(darkTheme = true) {
        PermissionDeniedEmpty(onOpenSettings = {})
    }
}
