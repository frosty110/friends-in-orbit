package app.orbit.ui.screens.picker

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme

/**
 * Permission denied empty state.
 *
 * Centred informational empty + Primary "Open Settings" CTA. Same shape as
 * BrowseListScreen.EmptyShell but with a deep-link to the OS app-detail
 * Settings screen (ACTION_APPLICATION_DETAILS_SETTINGS) so the user can flip
 * READ_CONTACTS back on.
 *
 * Locked copy:
 *   - Heading: "Contacts access is off"
 *   - Body:    "You can turn it on in Settings to add people to your lists."
 *   - Primary: "Open Settings"
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(OrbitTheme.spacing.x6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PhIcon(
            name = "shield-check",
            size = OrbitTheme.spacing.x7,
            tint = OrbitTheme.colors.fgMuted,
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x3))
        Text(
            text = "Contacts access is off",
            style = OrbitTheme.type.h3,
            color = OrbitTheme.colors.fg,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        Text(
            text = "You can turn it on in Settings to add people to your lists.",
            style = OrbitTheme.type.body,
            color = OrbitTheme.colors.fgMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x4))
        OrbitButton(
            text = "Open Settings",
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        )
    }
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

@Preview(name = "PermissionDeniedEmpty — dark", showBackground = true)
@Composable
private fun PermissionDeniedEmptyPreviewDark() {
    OrbitTheme(darkTheme = true) {
        PermissionDeniedEmpty(onOpenSettings = {})
    }
}
