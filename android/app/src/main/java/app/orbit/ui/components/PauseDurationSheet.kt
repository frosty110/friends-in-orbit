package app.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import app.orbit.R
import app.orbit.domain.model.PauseDuration
import app.orbit.ui.theme.OrbitTheme
import kotlinx.coroutines.launch

/**
 * The one "Pause for how long?" chooser: a bottom sheet with 1 week, 1 month
 * and "Until you unpause", for a person on Contact detail, a row on Browse
 * and Browse's Pause all. Before 2026-10-06 the same choice was a sheet on
 * Contact detail and an alert dialog on Browse, and the third option read
 * "Until you unpause" in one and "Indefinitely" in the other, with a third
 * phrasing in the snackbar (menus-4). Pause is a glossary word (voice.md):
 * one idea, one wording, so option, status line and snackbar now all say
 * "until you unpause".
 *
 * A sheet, not a dialog, because a choice offered from an overflow is a sheet
 * everywhere else in Orbit (ListSelectorSheet, LogConnectionSheet). The
 * title is a heading so TalkBack users land on the question; the options are
 * one selectable group of radio rows, so TalkBack says "1 week, radio button,
 * 1 of 3" rather than three unrelated buttons. Choosing commits and closes,
 * so no row is ever shown as selected and there is no Cancel: dragging or
 * tapping outside is the way out, as on every other sheet.
 *
 * Dismissal (the ListsManagerScreen pattern): the sheet's hide animation runs
 * first, then [onDismiss] flips the parent's visibility flag.
 *
 * [PauseDuration.Indefinite] maps to the use case's far-future sentinel; the
 * copy never mentions it and this file does not reference it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PauseDurationSheet(
    onSelect: (PauseDuration) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun commitAndDismiss(duration: PauseDuration) {
        onSelect(duration)
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OrbitTheme.colors.surface,
        // Token, not literal: the top-rounded sheet shape lives in Shape.kt.
        shape = OrbitTheme.shapes.bottomSheet,
    ) {
        PauseDurationSheetContent(onSelect = ::commitAndDismiss)
    }
}

/**
 * The sheet's content, also what the previews render: Material's
 * [ModalBottomSheet] is window-anchored and draws nothing inside `@Preview`,
 * and keeping one content composable means the preview cannot drift from the
 * live sheet. Internal so the semantics (heading, radio rows) can be tested
 * on the JVM without a sheet window.
 */
@Composable
internal fun PauseDurationSheetContent(
    onSelect: (PauseDuration) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x4),
    ) {
        Text(
            text = stringResource(R.string.components_pause_title),
            style = OrbitTheme.type.h3,
            color = OrbitTheme.colors.fg,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OrbitTheme.spacing.x3)
                .semantics { heading() },
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x3))
        Column(
            verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x1),
            modifier = Modifier.selectableGroup(),
        ) {
            PauseOptionRow(
                label = stringResource(R.string.components_pause_week),
                onSelect = { onSelect(PauseDuration.OneWeek) },
            )
            PauseOptionRow(
                label = stringResource(R.string.components_pause_month),
                onSelect = { onSelect(PauseDuration.OneMonth) },
            )
            PauseOptionRow(
                label = stringResource(R.string.components_pause_until_unpause),
                onSelect = { onSelect(PauseDuration.Indefinite) },
            )
        }
        Spacer(Modifier.height(OrbitTheme.spacing.x4))
    }
}

/** One option: a 48dp row with radio semantics (the LogConnectionSheet "when" row). */
@Composable
private fun PauseOptionRow(
    label: String,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = OrbitTheme.spacing.tapMin)
            .clip(OrbitTheme.shapes.md)
            // Never selected: choosing commits and closes the sheet.
            .selectable(selected = false, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = OrbitTheme.spacing.x3, vertical = OrbitTheme.spacing.x3),
    ) {
        Text(
            text = label,
            style = OrbitTheme.type.body,
            color = OrbitTheme.colors.fg,
        )
    }
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun PauseDurationSheetPreview() {
    OrbitTheme {
        Box(Modifier.background(OrbitTheme.colors.bg)) {
            PauseDurationSheetContent(
                onSelect = {},
                modifier = Modifier.background(OrbitTheme.colors.surface),
            )
        }
    }
}
