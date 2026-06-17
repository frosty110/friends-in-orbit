package app.orbit.ui.screens.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme

/**
 * Material3 ModalBottomSheet for the Create List authoring flow.
 *
 * Closes LIST-01 (in-app authoring) + SMART-02.
 *
 * SMART-02 wiring: the "Recently added, not called" entry in
 * [TemplateChoice.Catalog] carries [SmartListRule.RecentlyAddedNotCalled]
 * with `daysWindow = 30`. [ListsManagerViewModel.createList] encodes it into
 * `ListEntity.smartRuleJson`. (Verbatim copy — sentence case, no exclamation
 * marks, per the project's voice guidelines.)
 *
 * UX contract for the Create List bottom sheet:
 *   - Eyebrow "Choose a template" + 2-column grid of [TemplateChoice.Catalog]
 *   - Selected tile shows accentTint background + accentPress icon
 *   - Name field auto-fills from the selected template's [defaultName]; user
 *     can override
 *   - Inline validation "Give your list a name" appears in colors.danger when
 *     submission is attempted with an empty name
 *   - Cancel (Ghost) + Create (Primary) actions; Create is disabled until a
 *     template is picked AND name is non-blank
 *
 * Note: the parent screen wires sheet dismissal via
 *   `scope.launch { sheetState.hide() }.invokeOnCompletion { showSheet = false }`
 * — this composable only forwards the [onDismiss] / [onCreate] callbacks; the
 * launch-and-flip pattern lives in [ListsManagerScreen].
 *
 * Voice: sentence case, no exclamation marks (project voice guidelines).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateListBottomSheet(
    sheetState: SheetState,
    onCreate: (template: TemplateChoice, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = OrbitTheme.shapes.xl,
        containerColor = OrbitTheme.colors.surface,
    ) {
        CreateListContent(onCreate = onCreate, onDismiss = onDismiss)
    }
}

@Composable
private fun CreateListContent(
    onCreate: (TemplateChoice, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<TemplateChoice?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var attemptedSubmit by rememberSaveable { mutableStateOf(false) }
    val nameError = attemptedSubmit && name.trim().isEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = OrbitTheme.spacing.x6,
                end = OrbitTheme.spacing.x6,
                top = OrbitTheme.spacing.x4,
                bottom = OrbitTheme.spacing.x6,
            ),
    ) {
        Text(
            text = "Choose a template",
            style = OrbitTheme.type.eyebrow.copy(color = OrbitTheme.colors.fgMuted),
            modifier = Modifier.padding(top = OrbitTheme.spacing.x1, bottom = OrbitTheme.spacing.x3),
        )

        // 2-column grid over the locked Catalog order.
        val rows = TemplateChoice.Catalog.chunked(2)
        rows.forEachIndexed { idx, rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
            ) {
                rowItems.forEach { template ->
                    TemplateTile(
                        template = template,
                        selected = selected?.id == template.id,
                        onSelect = {
                            selected = template
                            // Pre-fill name on first selection or when user
                            // hasn't typed anything custom yet.
                            if (name.isBlank()) name = template.defaultName
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad short trailing rows (defensive — Catalog is currently 6).
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
            if (idx != rows.lastIndex) Spacer(Modifier.height(OrbitTheme.spacing.x3))
        }

        Spacer(Modifier.height(OrbitTheme.spacing.x6))

        Text(
            text = "Name your list",
            style = OrbitTheme.type.h3,
            color = OrbitTheme.colors.fg,
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))

        TextField(
            value = name,
            onValueChange = {
                name = it
                attemptedSubmit = false
            },
            placeholder = {
                Text(
                    text = selected?.displayName?.takeIf { it.isNotBlank() }
                        ?: "e.g. Inner orbit",
                    style = OrbitTheme.type.body,
                    color = OrbitTheme.colors.fgSubtle,
                )
            },
            isError = nameError,
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
                focusedTextColor = OrbitTheme.colors.fg,
                unfocusedTextColor = OrbitTheme.colors.fg,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clip(OrbitTheme.shapes.md)
                .background(OrbitTheme.colors.bgSubtle),
        )
        if (nameError) {
            Text(
                text = "Give your list a name",
                style = OrbitTheme.type.meta,
                color = OrbitTheme.colors.danger,
                modifier = Modifier.padding(
                    top = OrbitTheme.spacing.x1,
                    start = OrbitTheme.spacing.x1,
                ),
            )
        }

        Spacer(Modifier.height(OrbitTheme.spacing.x6))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x4),
        ) {
            OrbitButton(
                text = "Cancel",
                onClick = onDismiss,
                variant = OrbitButtonVariant.Ghost,
                modifier = Modifier.weight(1f),
            )
            OrbitButton(
                text = "Create",
                onClick = {
                    attemptedSubmit = true
                    val tpl = selected ?: return@OrbitButton
                    if (name.trim().isNotEmpty()) {
                        onCreate(tpl, name.trim())
                    }
                },
                enabled = name.trim().isNotEmpty() && selected != null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TemplateTile(
    template: TemplateChoice,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileBg = if (selected) OrbitTheme.colors.accentTint else OrbitTheme.colors.bgSubtle
    val iconTint = if (selected) OrbitTheme.colors.accentPress else OrbitTheme.colors.fgMuted
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(OrbitTheme.shapes.md)
            .background(tileBg)
            .clickable(onClick = onSelect)
            .padding(
                vertical = OrbitTheme.spacing.x4,
                horizontal = OrbitTheme.spacing.x3,
            )
            .defaultMinSize(minHeight = 96.dp),
    ) {
        PhIcon(
            name = template.iconName,
            size = OrbitTheme.spacing.x6,
            tint = iconTint,
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        Text(
            text = template.displayName,
            style = OrbitTheme.type.body.copy(fontWeight = FontWeight.Medium),
            color = OrbitTheme.colors.fg,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x1))
        Text(
            text = template.subtitle,
            style = OrbitTheme.type.meta,
            color = OrbitTheme.colors.fgMuted,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Create List · light", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun CreateListBottomSheetLightPreview() {
    // Preview the inner content surface — ModalBottomSheet itself doesn't render
    // off-device. CreateListContent is the visual contract reviewers check.
    OrbitTheme(darkTheme = false) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.background(OrbitTheme.colors.surface),
        ) {
            CreateListContent(onCreate = { _, _ -> }, onDismiss = {})
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Create List · dark", showBackground = true, backgroundColor = 0xFF0E0F12)
@Composable
private fun CreateListBottomSheetDarkPreview() {
    OrbitTheme(darkTheme = true) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.background(OrbitTheme.colors.surface),
        ) {
            CreateListContent(onCreate = { _, _ -> }, onDismiss = {})
        }
    }
}

// Keep the rememberModalBottomSheetState reference in the file scope so a
// downstream search lands here when looking for sheet-state usage examples.
@Suppress("unused")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun previewSheetState(): SheetState =
    rememberModalBottomSheetState(skipPartiallyExpanded = true)
