package app.orbit.widget.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import app.orbit.R
import app.orbit.data.WidgetSource
import app.orbit.ui.components.Avatar
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitSnackbarHost
import app.orbit.ui.components.PhIcon
import app.orbit.ui.screens.lists.SettingGroup
import app.orbit.ui.screens.lists.ToggleRow
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.theme.OrbitTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Widget settings (WIDGET-12, WIDGET-15): what one placed widget suggests
 * from, and whether it shows names and faces.
 *
 * Stateless: everything comes from [state], and every choice goes out through
 * a callback that saves it. The screen keeps no copy of a choice (rules.md
 * Code 7). One accent element, Done (Design 5); the selected list uses the
 * cluster tier.
 *
 * Under the privacy curtain (PRIV-03) list names read "List" and the preview
 * person "Contact", with no photo, as everywhere else in Orbit's window.
 */
@Composable
fun WidgetSettingsContent(
    state: WidgetSettingsUiState,
    snackbarEvents: Flow<SnackbarEvent>,
    onSelectSource: (WidgetSource) -> Unit,
    onShowNamesChange: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(snackbarEvents) {
        snackbarEvents.collect { event ->
            snackbarHostState.showSnackbar(
                message = event.message.asString(context),
                duration = SnackbarDuration.Short,
            )
        }
    }

    OrbitScreen {
        OrbitAppBar(
            title = stringResource(R.string.widget_settings_title),
            leading = {
                OrbitIconButton(
                    icon = "arrow-left",
                    onClick = onDone,
                    contentDescription = stringResource(R.string.components_action_back),
                )
            },
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state is WidgetSettingsUiState.Ready) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = OrbitTheme.spacing.x4),
                ) {
                    SettingGroup(title = stringResource(R.string.widget_settings_preview_heading)) {
                        PreviewRow(state.preview)
                    }
                    SettingGroup(title = stringResource(R.string.widget_settings_source_heading)) {
                        SourceRows(state, onSelectSource)
                    }
                    SettingGroup(title = stringResource(R.string.widget_settings_home_heading)) {
                        ToggleRow(
                            label = stringResource(R.string.widget_settings_show_names),
                            sub = if (state.namesHidden) {
                                stringResource(
                                    R.string.widget_settings_show_names_off_helper,
                                    stringResource(R.string.components_curtain_contact),
                                )
                            } else {
                                null
                            },
                            value = !state.namesHidden,
                            onChange = onShowNamesChange,
                        )
                    }
                }
            }
            OrbitSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        OrbitButton(
            text = stringResource(R.string.components_action_done),
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x3),
        )
    }
}

/**
 * Who the widget will lead with: a face and a name, no Call button. The
 * preview confirms who; it is not a place to call from (Design 6).
 */
@Composable
private fun PreviewRow(preview: WidgetSettingsPreview) {
    val curtain = LocalPrivacyCurtain.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = OrbitTheme.spacing.tapMin)
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.rowY),
    ) {
        when (preview) {
            is WidgetSettingsPreview.Person -> {
                val masked = preview.masked || curtain
                val name = if (masked) stringResource(R.string.components_curtain_contact) else preview.name
                Avatar(name = name, photoUri = if (masked) null else preview.photoUri)
                Text(
                    text = name,
                    style = OrbitTheme.type.body.copy(fontWeight = FontWeight.Medium),
                    color = OrbitTheme.colors.fg,
                )
            }
            WidgetSettingsPreview.Quiet -> Text(
                text = stringResource(R.string.widget_empty),
                style = OrbitTheme.type.body,
                color = OrbitTheme.colors.fgMuted,
            )
            WidgetSettingsPreview.SourceMissing -> Text(
                text = stringResource(R.string.widget_settings_source_missing),
                style = OrbitTheme.type.body,
                color = OrbitTheme.colors.fgMuted,
            )
        }
    }
}

/** "Every list", then each active list: one choice, one TalkBack group. */
@Composable
private fun SourceRows(state: WidgetSettingsUiState.Ready, onSelect: (WidgetSource) -> Unit) {
    val curtain = LocalPrivacyCurtain.current
    val maskedList = stringResource(R.string.components_curtain_list)
    Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
        SourceRow(
            label = stringResource(R.string.widget_settings_source_every_list),
            helper = stringResource(R.string.widget_settings_source_every_list_helper),
            selected = state.source == WidgetSource.EveryList,
            onSelect = { onSelect(WidgetSource.EveryList) },
        )
        state.lists.forEach { list ->
            SourceRow(
                label = if (curtain) maskedList else list.name,
                helper = null,
                selected = state.source == WidgetSource.OneList(list.id),
                onSelect = { onSelect(WidgetSource.OneList(list.id)) },
            )
        }
    }
    if (state.lists.isEmpty()) {
        Text(
            text = stringResource(R.string.widget_settings_no_lists),
            style = OrbitTheme.type.meta,
            color = OrbitTheme.colors.fgMuted,
            modifier = Modifier.padding(
                start = OrbitTheme.spacing.x4,
                end = OrbitTheme.spacing.x4,
                bottom = OrbitTheme.spacing.rowY,
            ),
        )
    }
}

@Composable
private fun SourceRow(label: String, helper: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = OrbitTheme.spacing.tapMin)
            // Cluster tier, never the accent: Done is this screen's one
            // accent element (Design 5).
            .background(if (selected) OrbitTheme.colors.accentTint else OrbitTheme.colors.surface)
            // A radio button to TalkBack, with its selected state; the tint
            // and the check are only what is drawn.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.rowY),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = OrbitTheme.type.body, color = OrbitTheme.colors.fg)
            if (helper != null) {
                Text(
                    text = helper,
                    style = OrbitTheme.type.meta,
                    color = OrbitTheme.colors.fgMuted,
                    modifier = Modifier.padding(top = OrbitTheme.spacing.hair),
                )
            }
        }
        if (selected) {
            PhIcon(name = "check", size = OrbitTheme.spacing.x5, tint = OrbitTheme.colors.fg)
        }
    }
}

// region Previews
// Fixture names from the gallery's list, so its curtain pass checks that the
// list names and the preview person are masked.

private val previewLists = listOf(
    ListOption(1L, "Inner orbit"),
    ListOption(2L, "People who ground me"),
)

@Composable
private fun WidgetSettingsPreviewHost(state: WidgetSettingsUiState) {
    OrbitTheme {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            WidgetSettingsContent(
                state = state,
                snackbarEvents = emptyFlow(),
                onSelectSource = {},
                onShowNamesChange = {},
                onDone = {},
            )
        }
    }
}

@PreviewLightDark
@Preview(name = "200%", fontScale = 2f)
@Composable
private fun WidgetSettingsEveryListPreview() {
    WidgetSettingsPreviewHost(
        WidgetSettingsUiState.Ready(
            lists = previewLists,
            source = WidgetSource.EveryList,
            namesHidden = false,
            preview = WidgetSettingsPreview.Person(name = "Kai Nakamura", photoUri = null, masked = false),
        ),
    )
}

@PreviewLightDark
@Composable
private fun WidgetSettingsOneListNamesHiddenPreview() {
    WidgetSettingsPreviewHost(
        WidgetSettingsUiState.Ready(
            lists = previewLists,
            source = WidgetSource.OneList(2L),
            namesHidden = true,
            preview = WidgetSettingsPreview.Person(name = "Priya Shah", photoUri = null, masked = true),
        ),
    )
}

@PreviewLightDark
@Composable
private fun WidgetSettingsSourceMissingPreview() {
    WidgetSettingsPreviewHost(
        WidgetSettingsUiState.Ready(
            lists = previewLists,
            source = WidgetSource.OneList(9L),
            namesHidden = false,
            preview = WidgetSettingsPreview.SourceMissing,
        ),
    )
}

@PreviewLightDark
@Composable
private fun WidgetSettingsNoListsPreview() {
    WidgetSettingsPreviewHost(
        WidgetSettingsUiState.Ready(
            lists = emptyList(),
            source = WidgetSource.EveryList,
            namesHidden = false,
            preview = WidgetSettingsPreview.Quiet,
        ),
    )
}

// endregion
