package app.orbit.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.theme.OrbitTheme

/**
 * Minimal static licenses surface. A dedicated screen (and
 * the OSS-licenses Gradle plugin that usually feeds one) would add a nav
 * route and a build dependency for a list this small; a dialog keeps the
 * row honest without either.
 *
 * The entries mirror the runtime dependencies in
 * `android/gradle/libs.versions.toml`. When a dependency is added or
 * removed there, this list is updated in the same commit (same drift rule
 * as the project's dependency catalog conventions).
 */
@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OrbitTheme.colors.surface,
        title = {
            Text(
                text = "Open source licenses",
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                items(LICENSE_ENTRIES, key = { it.name }) { entry ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = entry.name,
                            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                        )
                        Text(
                            text = entry.license,
                            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            OrbitButton(
                text = "Close",
                onClick = onDismiss,
                variant = OrbitButtonVariant.Ghost,
            )
        },
    )
}

@Immutable
private data class LicenseEntry(val name: String, val license: String)

// Runtime dependencies per android/gradle/libs.versions.toml (2026-06-09).
private val LICENSE_ENTRIES: List<LicenseEntry> = listOf(
    LicenseEntry("AndroidX (Compose, Room, DataStore, WorkManager, Navigation, Lifecycle, Glance, Core)", "Apache License 2.0"),
    LicenseEntry("Kotlin and kotlinx (coroutines, serialization)", "Apache License 2.0"),
    LicenseEntry("Dagger Hilt", "Apache License 2.0"),
    LicenseEntry("Coil", "Apache License 2.0"),
    LicenseEntry("SQLCipher for Android (Zetetic LLC)", "BSD-style license"),
    LicenseEntry("Timber", "Apache License 2.0"),
    LicenseEntry("libphonenumber", "Apache License 2.0"),
    LicenseEntry("Reorderable (sh.calvin.reorderable)", "Apache License 2.0"),
)

@PreviewLightDark
@Composable
private fun LicensesDialogPreview() {
    OrbitTheme {
        LicensesDialog(onDismiss = {})
    }
}
