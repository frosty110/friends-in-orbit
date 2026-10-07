package app.orbit.ui.screens.lists.newlist

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import app.orbit.R
import app.orbit.ui.components.PhIcon
import app.orbit.ui.components.SectionLabel
import app.orbit.ui.screens.lists.TemplateChoice
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.rhythmTemplateTints

/**
 * LIST-29: New list's first step, "Start with". Top to bottom, every tile
 * full width (the owner found the old two-column grid "crowded and hard to
 * see which is what"):
 *
 *  - "Start from blank", first, on the plain surface.
 *  - The three rhythm templates, most frequent first, each tinted a step
 *    along one ramp ([rhythmTemplateTints]) so more often reads warmer. The
 *    tint is decoration: each subtitle says the rhythm in words.
 *  - Under a small "Smart list" label, set apart, the list that fills itself.
 *
 * One choice: the tiles are one radio group for TalkBack ("Family, Steady,
 * every couple of weeks., radio button, 3 of 5"), each a radio button with
 * its selected state, at least 48dp tall. The chosen tile wears an ink
 * outline and a check: cluster tier, never the accent (rules.md Design 5),
 * which on this step is Next's.
 *
 * Picking a tile hands over its name in the user's language, the new list's
 * starting name (empty for "Start from blank").
 */
@Composable
internal fun StartWithStep(
    selected: TemplateChoice?,
    onSelect: (template: TemplateChoice, defaultName: String) -> Unit,
) {
    val colors = OrbitTheme.colors
    val tints = remember(colors) { rhythmTemplateTints(colors) }
    val rhythm = TemplateChoice.Catalog.filter { it.group == TemplateChoice.Group.Rhythm }

    // One group across the label, so TalkBack counts the five as one choice.
    Column(
        verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
        modifier = Modifier.fillMaxWidth().selectableGroup(),
    ) {
        TemplateChoice.Catalog.forEach { template ->
            if (template.group == TemplateChoice.Group.Smart) {
                SectionLabel(
                    text = stringResource(R.string.lists_new_smart_label),
                    modifier = Modifier.padding(top = OrbitTheme.spacing.x4),
                )
            }
            val defaultName = template.defaultNameRes?.let { stringResource(it) }.orEmpty()
            TemplateTile(
                template = template,
                tint = when (template.group) {
                    TemplateChoice.Group.Rhythm -> tints[rhythm.indexOf(template).coerceIn(0, tints.lastIndex)]
                    TemplateChoice.Group.Blank, TemplateChoice.Group.Smart -> colors.surface
                },
                selected = selected?.id == template.id,
                onSelect = { onSelect(template, defaultName) },
            )
        }
    }
}

@Composable
private fun TemplateTile(
    template: TemplateChoice,
    tint: Color,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val shape = OrbitTheme.shapes.lg
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = OrbitTheme.spacing.tapMin)
            .clip(shape)
            .background(tint)
            .border(
                width = if (selected) OrbitTheme.spacing.hair else 1.dp,
                color = if (selected) OrbitTheme.colors.fg else OrbitTheme.colors.line,
                shape = shape,
            )
            // A radio button to TalkBack, with its selected state (WCAG
            // 4.1.2); the outline and the check are only what is drawn.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x3),
    ) {
        PhIcon(
            name = template.iconName,
            size = OrbitTheme.spacing.x6,
            tint = if (selected) OrbitTheme.colors.fg else OrbitTheme.colors.fgMuted,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(template.displayNameRes),
                style = OrbitTheme.type.body.copy(fontWeight = FontWeight.Medium),
                color = OrbitTheme.colors.fg,
            )
            Text(
                text = stringResource(template.subtitleRes),
                style = OrbitTheme.type.meta,
                color = OrbitTheme.colors.fgMuted,
            )
        }
        if (selected) {
            PhIcon(name = "check-circle", size = OrbitTheme.spacing.x6, tint = OrbitTheme.colors.fg)
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────
// The whole flow on its first step. Exempt from the gallery's curtain pass
// (PreviewGalleryTest's CURTAIN_EXEMPT): the tiles name templates ("Inner
// orbit"), which are copy, not anyone's data, and no name is entered yet.

@PreviewLightDark
@Preview(name = "200%", fontScale = 2f)
@Composable
private fun NewListStartWithPreview() {
    NewListPreviewHost(NewListUiState())
}

@PreviewLightDark
@Preview(name = "200%", fontScale = 2f)
@Composable
private fun NewListStartWithChosenPreview() {
    NewListPreviewHost(NewListUiState(template = TemplateChoice.Catalog.first { it.id == "family" }))
}

// The list that fills itself has three steps: "Step 1 of 3".
@Preview(name = "smart, light")
@Preview(name = "smart, dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NewListStartWithSmartPreview() {
    NewListPreviewHost(NewListUiState(template = TemplateChoice.Catalog.first { it.isSmart }))
}

// Leaving with a template picked asks first.
@PreviewLightDark
@Composable
private fun NewListStartWithDiscardPreview() {
    NewListPreviewHost(
        NewListUiState(template = TemplateChoice.Catalog.first { it.id == "inner_orbit" }),
        askingDiscard = true,
    )
}
