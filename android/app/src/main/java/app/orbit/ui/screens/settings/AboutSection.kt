package app.orbit.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import app.orbit.BuildConfig
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme

private const val PRIVACY_POLICY_URL = "https://frosty110.github.io/friends-in-orbit/privacy"

/**
 * SET-06 / RELEASE-05 About rows:
 *
 *   - Version — static, from BuildConfig.
 *   - Send feedback — `mailto:hello@bearlumen.com` via ACTION_SENDTO.
 *     Fulfills the parked follow-up from the welcome-screen mailto removal;
 *     Settings → About is its decided home.
 *   - Privacy policy — opens [PRIVACY_POLICY_URL], the live hosted privacy
 *     policy, in the system browser via ACTION_VIEW.
 *   - Source code — ACTION_VIEW to the public repository URL.
 *   - Open source licenses — opens [LicensesDialog], a static list derived
 *     from `libs.versions.toml` (kept honest by hand; no plugin).
 */
@Composable
fun AboutSection(
    onSourceCode: () -> Unit,
) {
    val context = LocalContext.current
    var showLicenses by rememberSaveable { mutableStateOf(false) }

    Column {
        AboutRow(
            primary = "Orbit",
            secondary = "Version ${BuildConfig.VERSION_NAME}",
            onClick = null,
        )
        AboutRow(
            primary = "Send feedback",
            secondary = "hello@bearlumen.com",
            onClick = {
                val intent = Intent(
                    Intent.ACTION_SENDTO,
                    Uri.parse("mailto:hello@bearlumen.com"),
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
            },
        )
        AboutRow(
            primary = "Privacy policy",
            secondary = "Read how Orbit handles your data",
            onClick = {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(PRIVACY_POLICY_URL),
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
            },
        )
        AboutRow(
            primary = "Source code",
            secondary = "github.com/frosty110/friends-in-orbit",
            onClick = onSourceCode,
        )
        AboutRow(
            primary = "Open source licenses",
            secondary = "What we built on",
            onClick = { showLicenses = true },
        )
    }

    if (showLicenses) {
        LicensesDialog(onDismiss = { showLicenses = false })
    }
}

@Composable
private fun AboutRow(
    primary: String,
    secondary: String,
    onClick: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = primary,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            Text(
                text = secondary,
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (onClick != null) {
            PhIcon(name = "caret-right", size = 16.dp, tint = OrbitTheme.colors.fgSubtle)
        }
    }
}

@PreviewLightDark
@Composable
private fun AboutSectionPreview() {
    OrbitTheme {
        AboutSection(onSourceCode = {})
    }
}
