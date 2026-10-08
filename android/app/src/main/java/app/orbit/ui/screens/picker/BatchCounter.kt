package app.orbit.ui.screens.picker

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import app.orbit.R
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.theme.OrbitTheme

/**
 * The commit bar both pickers dock under their list (PICK-06): "{N} selected",
 * a quiet Clear and the Primary CTA, which is the screen's one accent. It is
 * rendered as the last sibling of the picker Column with the list in
 * `weight(1f)`, so nothing is hidden underneath. It was a card floating over
 * the rows, and the list picker kept its own floating copy until 2026-10-06
 * (its bottom padding was 16dp short of the card, so the last row's checkbox
 * sat under it); one component for the job now (DESIGN.md).
 *
 * [ctaLabel] is the button's visible words, a bare verb ("Add"): the count
 * already sits to its left, and "Add 3 people to Inner orbit" beside "3
 * selected" said the number twice and squeezed the bar until 2026-10-07.
 * [ctaDescription], when given, is what TalkBack says instead: the whole
 * sentence, since a TalkBack user does not see the count and the list at a
 * glance. It begins with the visible verb, so a voice-control user who says
 * "Tap Add" still reaches it. Hidden entirely when [selectionCount] == 0.
 * Disabled (greyed) while [isCommitting], so a second tap does nothing extra
 * during an in-flight write.
 */
@Composable
fun BatchCounter(
    selectionCount: Int,
    ctaLabel: String,
    isCommitting: Boolean,
    onClear: () -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    ctaDescription: String? = null
) {
    if (selectionCount == 0) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OrbitTheme.colors.surface)
    ) {
        // Top hairline separates the bar from the list it docks beneath.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(OrbitTheme.colors.lineSoft)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = OrbitTheme.spacing.x4,
                    vertical = OrbitTheme.spacing.x3
                )
        ) {
            // Measured at its own width, with the spacer taking the slack: when
            // the bar is tight (200% font scale under a long CTA) the button
            // wraps its label instead of this count breaking letter by letter,
            // which it did while it carried the weight (rules.md §Design 2).
            Text(
                text = pluralStringResource(R.plurals.picker_selected_count, selectionCount, selectionCount),
                style = OrbitTheme.type.body,
                color = OrbitTheme.colors.fg
            )
            Spacer(Modifier.weight(1f))
            // 48dp target and a button role (it was about 38dp; rules.md §Design 3).
            ClearSelectionAction(enabled = !isCommitting, onClear = onClear)
            OrbitButton(
                text = ctaLabel,
                onClick = onCommit,
                enabled = !isCommitting,
                modifier = if (ctaDescription != null) {
                    Modifier.semantics { contentDescription = ctaDescription }
                } else {
                    Modifier
                }
            )
        }
    }
}

/**
 * The contact picker's bar: the button says the mode's verb, and TalkBack
 * hears the verb with the count and the target.
 *
 * CTA copy, shown / spoken (the plurals say the noun, "Add 1 person to Inner
 * orbit", "Add 3 people to Inner orbit", as the snackbars do):
 *   - [PickerMode.Add]  → "Add" / "Add {N} people to {targetListName}"
 *   - [PickerMode.Move] → "Move" / "Move {N} people to {targetListName}"
 *   - [PickerMode.Copy] → "Copy" / "Copy {N} people to {targetListName}"
 *   - [PickerMode.Relink] → "Re-link {orphan name}" both ways (CONTACT-07;
 *     the picker passes the orphan's name as [targetListName], and N is
 *     always 1, so there is no count to repeat and the name is the point)
 *   - [PickerMode.Collect] → "Add" / "Add {N} people" (LIST-28: New list's
 *     People step; the list is made on "Create list", so neither names one)
 *
 * PRIV-03: under the privacy curtain the name reads "List" for a list and
 * "Contact" for the person a Re-link merges into. It read "Re-link List" for
 * a person until 2026-10-06, because the mask was chosen without the mode.
 */
@Composable
fun BatchCounter(
    selectionCount: Int,
    targetListName: String,
    mode: PickerMode,
    isCommitting: Boolean,
    onClear: () -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selectionCount == 0) return

    val curtain = LocalPrivacyCurtain.current
    val name = when {
        !curtain -> targetListName
        mode == PickerMode.Relink -> stringResource(R.string.components_curtain_contact)
        else -> stringResource(R.string.components_curtain_list)
    }
    val ctaSentence: String = when (mode) {
        PickerMode.Add -> pluralStringResource(R.plurals.picker_commit_add, selectionCount, selectionCount, name)
        PickerMode.Move -> pluralStringResource(R.plurals.picker_commit_move, selectionCount, selectionCount, name)
        PickerMode.Copy -> pluralStringResource(R.plurals.picker_commit_copy, selectionCount, selectionCount, name)
        PickerMode.Relink -> stringResource(R.string.picker_commit_relink, name)
        PickerMode.Collect -> pluralStringResource(R.plurals.picker_commit_collect, selectionCount, selectionCount)
    }
    val ctaVerb: String? = when (mode) {
        PickerMode.Add -> stringResource(R.string.picker_commit_add_short)
        PickerMode.Move -> stringResource(R.string.picker_commit_move_short)
        PickerMode.Copy -> stringResource(R.string.picker_commit_copy_short)
        PickerMode.Relink -> null
        PickerMode.Collect -> stringResource(R.string.picker_commit_add_short)
    }

    BatchCounter(
        selectionCount = selectionCount,
        ctaLabel = ctaVerb ?: ctaSentence,
        ctaDescription = if (ctaVerb != null) ctaSentence else null,
        isCommitting = isCommitting,
        onClear = onClear,
        onCommit = onCommit,
        modifier = modifier
    )
}

@Preview(name = "BatchCounter — Add light", showBackground = true)
@Composable
private fun BatchCounterAddPreviewLight() {
    OrbitTheme(darkTheme = false) {
        BatchCounter(
            selectionCount = 12,
            targetListName = "Inner orbit",
            mode = PickerMode.Add,
            isCommitting = false,
            onClear = {},
            onCommit = {}
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "BatchCounter — Move dark", showBackground = true)
@Composable
private fun BatchCounterMovePreviewDark() {
    OrbitTheme(darkTheme = true) {
        BatchCounter(
            selectionCount = 7,
            targetListName = "Late night",
            mode = PickerMode.Move,
            isCommitting = false,
            onClear = {},
            onCommit = {}
        )
    }
}

@Preview(name = "BatchCounter — Copy single light", showBackground = true)
@Composable
private fun BatchCounterCopyPreviewLight() {
    OrbitTheme(darkTheme = false) {
        BatchCounter(
            selectionCount = 1,
            targetListName = "People who ground me",
            mode = PickerMode.Copy,
            isCommitting = false,
            onClear = {},
            onCommit = {}
        )
    }
}

// Re-link's target is a person, so the gallery's curtain pass checks that the
// bar masks it as "Contact", not "List".
@PreviewLightDark
@Composable
private fun BatchCounterRelinkPreview() {
    OrbitTheme {
        BatchCounter(
            selectionCount = 1,
            targetListName = "Sarah Levin",
            mode = PickerMode.Relink,
            isCommitting = false,
            onClear = {},
            onCommit = {}
        )
    }
}

// The list picker's words on the same bar.
@PreviewLightDark
@Composable
private fun BatchCounterListsPreview() {
    OrbitTheme {
        BatchCounter(
            selectionCount = 2,
            ctaLabel = stringResource(R.string.picker_commit_add_short),
            ctaDescription = pluralStringResource(R.plurals.picker_lists_commit, 2, 2),
            isCommitting = false,
            onClear = {},
            onCommit = {}
        )
    }
}
