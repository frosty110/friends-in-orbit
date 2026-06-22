package app.orbit.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitDarkMode
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.OrbitThemeId
import app.orbit.ui.theme.OrbitThemes

/**
 * Settings → Appearance (THEMING 2026-06-22). Three controls:
 *   1. Theme swatches — the curated personality palettes.
 *   2. Light / Dark / System — independent of theme.
 *   3. Accent dial — fine-tune the primary color within accessible bounds.
 *
 * Stateless: reads the current selection, emits choices via callbacks. The
 * accent slider commits on release (onValueChangeFinished) so a drag does not
 * spam DataStore; the whole app retints when the committed value lands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSection(
    themeId: OrbitThemeId,
    darkMode: OrbitDarkMode,
    accentHue: Int?,
    onSelectTheme: (OrbitThemeId) -> Unit,
    onSelectDarkMode: (OrbitDarkMode) -> Unit,
    onAccentHue: (Int?) -> Unit,
) {
    val isDark = OrbitTheme.colors.isDark

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        // ---- Theme ----
        Text("Theme", style = OrbitTheme.type.body, color = OrbitTheme.colors.fg)
        Text(
            "Pick a color that feels like you",
            style = OrbitTheme.type.meta,
            color = OrbitTheme.colors.fgMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = OrbitTheme.spacing.x3),
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x4),
        ) {
            OrbitThemes.all.forEach { def ->
                val swatch = if (isDark) def.dark.accent else def.light.accent
                ThemeSwatch(
                    label = def.id.displayName,
                    color = swatch,
                    selected = def.id == themeId,
                    onClick = { onSelectTheme(def.id) },
                )
            }
        }

        Spacer(Modifier.size(OrbitTheme.spacing.x5))

        // ---- Light / Dark / System ----
        Text("Light & dark", style = OrbitTheme.type.body, color = OrbitTheme.colors.fg)
        Row(
            modifier = Modifier.padding(top = OrbitTheme.spacing.x2),
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
        ) {
            OrbitDarkMode.entries.forEach { mode ->
                FilterChip(
                    selected = darkMode == mode,
                    onClick = { onSelectDarkMode(mode) },
                    label = { Text(mode.displayName) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = OrbitTheme.colors.accentTint,
                        selectedLabelColor = OrbitTheme.colors.fg,
                    ),
                    modifier = Modifier.defaultMinSize(minHeight = OrbitTheme.spacing.tapMin),
                )
            }
        }

        Spacer(Modifier.size(OrbitTheme.spacing.x5))

        // ---- Accent dial ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Accent",
                style = OrbitTheme.type.body,
                color = OrbitTheme.colors.fg,
                modifier = Modifier.weight(1f),
            )
            if (accentHue != null) {
                Text(
                    "Match theme",
                    style = OrbitTheme.type.meta,
                    color = OrbitTheme.colors.accent,
                    modifier = Modifier
                        .clip(OrbitTheme.shapes.sm)
                        .clickable { onAccentHue(null) }
                        .padding(horizontal = OrbitTheme.spacing.x2, vertical = OrbitTheme.spacing.x1),
                )
            }
        }
        Text(
            if (accentHue == null) "Using the ${themeId.displayName} accent" else "Custom accent",
            style = OrbitTheme.type.meta,
            color = OrbitTheme.colors.fgMuted,
            modifier = Modifier.padding(top = 2.dp),
        )

        val seedHue = remember(accentHue, themeId) {
            (accentHue ?: OrbitThemes.defaultHueFor(themeId)).toFloat()
        }
        var liveHue by remember(accentHue, themeId) { mutableStateOf(seedHue) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = OrbitTheme.spacing.x2),
        ) {
            Slider(
                value = liveHue,
                onValueChange = { liveHue = it },
                onValueChangeFinished = { onAccentHue(liveHue.toInt()) },
                valueRange = 0f..359f,
                colors = SliderDefaults.colors(
                    thumbColor = OrbitTheme.colors.accent,
                    activeTrackColor = OrbitTheme.colors.accent,
                    inactiveTrackColor = OrbitTheme.colors.line,
                ),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Accent color hue" },
            )
            Spacer(Modifier.width(OrbitTheme.spacing.x3))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(OrbitThemes.previewAccent(liveHue.toInt(), isDark)),
            )
        }
    }
}

@Composable
private fun ThemeSwatch(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(56.dp)
            .clip(OrbitTheme.shapes.md)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$label theme${if (selected) ", selected" else ""}" }
            .padding(vertical = OrbitTheme.spacing.x1),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, OrbitTheme.colors.fg, CircleShape)
                    } else {
                        Modifier
                    },
                ),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Text(
            text = label,
            style = OrbitTheme.type.micro,
            color = if (selected) OrbitTheme.colors.fg else OrbitTheme.colors.fgMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = OrbitTheme.spacing.x1),
        )
    }
}

@PreviewLightDark
@Composable
private fun AppearanceSectionPreview() {
    OrbitTheme {
        AppearanceSection(
            themeId = OrbitThemeId.WARM,
            darkMode = OrbitDarkMode.SYSTEM,
            accentHue = null,
            onSelectTheme = {},
            onSelectDarkMode = {},
            onAccentHue = {},
        )
    }
}
