package app.orbit.ui.screens.lists

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.R
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.PhIcon
import app.orbit.ui.components.SectionLabel
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
 *   - Eyebrow "Choose a template" + 2-column grid of [TemplateChoice.Catalog],
 *     one radio group: each tile announces as a radio button with its
 *     selected state, so TalkBack can say which template is picked
 *   - Selected tile shows accentTint background + an ink icon (cluster tier,
 *     rules.md §Design 5: Create is the sheet's one accent)
 *   - Name field auto-fills from the selected template's [defaultName]; user
 *     can override
 *   - Cancel (Ghost) + Create (Primary) actions; Create is disabled until a
 *     template is picked AND name is non-blank. The "Name your list" heading
 *     is the prompt; there is no error line (the acceptance criterion asks
 *     for a soft prompt, not an error-coloured one, and a disabled Create
 *     can never be "attempted")
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

// Internal, not private, so a semantics test can check the template grid's
// radio roles and selected state without a ModalBottomSheet host.
@Composable
internal fun CreateListContent(
    onCreate: (TemplateChoice, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<TemplateChoice?>(null) }
    var name by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Keyboard safety (2026-08-15 UAT): the sheet used to be a plain
            // fixed Column, so raising the IME left "Name your list" — and the
            // text being typed into it — underneath the keyboard. imePadding
            // shrinks the sheet to the visible area and verticalScroll lets the
            // focused field scroll up into it (Compose's TextField asks for
            // that on focus; it needs a scrollable parent to be able to obey).
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(
                start = OrbitTheme.spacing.x6,
                end = OrbitTheme.spacing.x6,
                top = OrbitTheme.spacing.x4,
                bottom = OrbitTheme.spacing.x6,
            ),
    ) {
        SectionLabel(
            text = stringResource(R.string.lists_create_choose_template),
            modifier = Modifier.padding(top = OrbitTheme.spacing.x1, bottom = OrbitTheme.spacing.x3),
        )

        // 2-column grid over the locked Catalog order. One selectableGroup
        // across the rows: the tiles are one choice, and TalkBack counts
        // radio buttons per group ("2 of 6").
        val rows = TemplateChoice.Catalog.chunked(2)
        Column(modifier = Modifier.selectableGroup()) {
            rows.forEachIndexed { idx, rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
                ) {
                    rowItems.forEach { template ->
                        val defaultName = template.defaultNameRes?.let { stringResource(it) }.orEmpty()
                        TemplateTile(
                            template = template,
                            selected = selected?.id == template.id,
                            onSelect = {
                                selected = template
                                // Pre-fill name on first selection or when user
                                // hasn't typed anything custom yet.
                                if (name.isBlank()) name = defaultName
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Pad short trailing rows (defensive; Catalog is currently 6).
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
                if (idx != rows.lastIndex) Spacer(Modifier.height(OrbitTheme.spacing.x3))
            }
        }

        Spacer(Modifier.height(OrbitTheme.spacing.x6))

        Text(
            text = stringResource(R.string.lists_create_name_label),
            style = OrbitTheme.type.h3,
            color = OrbitTheme.colors.fg,
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))

        TextField(
            value = name,
            onValueChange = { name = it },
            placeholder = {
                Text(
                    text = stringResource(selected?.displayNameRes ?: R.string.lists_create_name_placeholder),
                    style = OrbitTheme.type.body,
                    color = OrbitTheme.colors.fgSubtle,
                )
            },
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

        Spacer(Modifier.height(OrbitTheme.spacing.x6))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x4),
        ) {
            OrbitButton(
                text = stringResource(R.string.components_action_cancel),
                onClick = onDismiss,
                variant = OrbitButtonVariant.Ghost,
                modifier = Modifier.weight(1f),
            )
            OrbitButton(
                text = stringResource(R.string.lists_create_cta),
                onClick = {
                    // Both guards hold while the button is enabled; kept so a
                    // stale tap during recomposition can never create a list
                    // without a template or a name.
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

// Room for an icon, a name and a two-line subtitle at the default scale, so the
// six tiles line up as a grid instead of ragging by subtitle length. Layout-
// local: not a spacing token, because nothing else is this shape. A minimum,
// so the tile grows with its text at 200% (rules.md §Design 2).
private val TEMPLATE_TILE_MIN_HEIGHT = 96.dp

@Composable
private fun TemplateTile(
    template: TemplateChoice,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileBg = if (selected) OrbitTheme.colors.accentTint else OrbitTheme.colors.bgSubtle
    // Ink when selected, not the accent's pressed shade: the selected tile is
    // cluster tier (rules.md §Design 5), and Create is the sheet's one accent.
    val iconTint = if (selected) OrbitTheme.colors.fg else OrbitTheme.colors.fgMuted
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(OrbitTheme.shapes.md)
            .background(tileBg)
            // A radio button to TalkBack, with its selected state (WCAG 4.1.2):
            // the tint alone said nothing to a screen reader.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(
                vertical = OrbitTheme.spacing.x4,
                horizontal = OrbitTheme.spacing.x3,
            )
            .defaultMinSize(minHeight = TEMPLATE_TILE_MIN_HEIGHT),
    ) {
        PhIcon(
            name = template.iconName,
            size = OrbitTheme.spacing.x6,
            tint = iconTint,
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        Text(
            text = stringResource(template.displayNameRes),
            style = OrbitTheme.type.body.copy(fontWeight = FontWeight.Medium),
            color = OrbitTheme.colors.fg,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x1))
        Text(
            text = stringResource(template.subtitleRes),
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
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Create List · dark", showBackground = true, backgroundColor = 0xFF0E0F12)
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
