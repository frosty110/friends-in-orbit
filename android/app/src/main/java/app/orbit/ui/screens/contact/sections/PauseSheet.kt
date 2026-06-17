package app.orbit.ui.screens.contact.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.domain.model.PauseDuration
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.theme.OrbitTheme
import kotlinx.coroutines.launch

/**
 * CONTACT-04 — Pause duration picker. ModalBottomSheet from ContactDetail
 * overflow → "Pause". Three full-width Secondary OrbitButtons stacked
 * (8dp gap):
 *   - 1 week
 *   - 1 month
 *   - Indefinite — until you unpause
 *
 * Voice locked per the copywriting contract (PauseSheet table). The
 * "Indefinite — until you unpause" copy is verbatim — NOT "Indefinitely"
 * (which is the bulk-pause dialog copy at
 * `app.orbit.ui.screens.browse.PauseDurationDialog`).
 *
 * Dismissal pattern (`ListsManagerScreen.kt`):
 * `scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }`
 * — sheet hide animates first, then parent visibility flag flips.
 *
 * `PauseDuration.Indefinite` maps to
 * `PauseContactUseCase.INDEFINITE_PAUSE_SENTINEL` (a far-future Instant
 * sentinel declared in the use case) — the user-facing copy NEVER mentions
 * the sentinel value, and this UI file does not reference it. Single-source
 * invariant: the literal sentinel string lives only in PauseContactUseCase.kt.
 *
 * `shape = OrbitTheme.shapes.bottomSheet` — uses the design-system token. No
 * RoundedCornerShape literal in this file. The token encodes top-rounded
 * (24dp) + flat-bottom (0dp) and is reusable across all ModalBottomSheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PauseSheet(
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
        // Token, not literal. RoundedCornerShape lives only in Shape.kt.
        shape = OrbitTheme.shapes.bottomSheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = OrbitTheme.spacing.x6,
                    vertical = OrbitTheme.spacing.x4,
                ),
            verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
        ) {
            Text(
                text = "Pause for how long?",
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x3))
            OrbitButton(
                text = "1 week",
                onClick = { commitAndDismiss(PauseDuration.OneWeek) },
                variant = OrbitButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
            OrbitButton(
                text = "1 month",
                onClick = { commitAndDismiss(PauseDuration.OneMonth) },
                variant = OrbitButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
            OrbitButton(
                text = "Indefinite — until you unpause",
                onClick = { commitAndDismiss(PauseDuration.Indefinite) },
                variant = OrbitButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x4))
        }
    }
}

// region Previews — sheet content rendered as a plain column for IDE preview.

/**
 * Preview helper — Material3 [ModalBottomSheet] is a window-anchored composable
 * and renders nothing inside `@Preview`; the inner column shape exactly matches
 * the live sheet content so designers can review the duration buttons + title
 * spacing without a device.
 */
@Composable
private fun PauseSheetPreviewSurface() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OrbitTheme.colors.surface)
            .padding(
                horizontal = OrbitTheme.spacing.x6,
                vertical = OrbitTheme.spacing.x4,
            ),
        verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
    ) {
        Text(
            text = "Pause for how long?",
            style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x3))
        OrbitButton(
            text = "1 week",
            onClick = {},
            variant = OrbitButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        OrbitButton(
            text = "1 month",
            onClick = {},
            variant = OrbitButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        OrbitButton(
            text = "Indefinite — until you unpause",
            onClick = {},
            variant = OrbitButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x4))
    }
}

@Preview(name = "PauseSheet — light", showBackground = true)
@Composable
private fun PauseSheetLightPreview() {
    OrbitTheme(darkTheme = false) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            PauseSheetPreviewSurface()
        }
    }
}

@Preview(name = "PauseSheet — dark", showBackground = true)
@Composable
private fun PauseSheetDarkPreview() {
    OrbitTheme(darkTheme = true) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            PauseSheetPreviewSurface()
        }
    }
}

// endregion
