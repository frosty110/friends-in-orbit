package app.orbit.ui.screens.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.R
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitCheckbox
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.orbitCardShadow

/**
 * Reverse picker (BULK-06).
 *
 * Sibling of [ContactPickerScreen]: same two-layer shape, much simpler body.
 * Given a contact, the user multi-selects which lists to add them to.
 *
 * Layout ("list of lists is small, < 20 typically"):
 *   - AppBar: "Add to lists" (or "Add {contactName} to lists" once loaded)
 *   - LazyColumn of [ListPickerViewModel.UiState.ListRow] (name + [OrbitCheckbox];
 *     the row is the checkbox for TalkBack)
 *   - Sticky BatchCounter-like footer with locked CTA "Add to N list[s]"
 *   - No snackbar host — the screen pops on commit, so the result surfaces
 *     via [PickerCommitBus] on the app-level [PickerCommitSnackbarHost]
 *     (identical to the forward picker)
 *   - No filter chips, no search — list count is small
 *   - Privacy curtain: the person's name leaves the title and list names read
 *     "List" (ListContextChip's word), as everywhere else (2026-10-05)
 *   - States (PICK-09): a failed read shows Retry; no lists yet offers to make
 *     one; every message is the shared [OrbitScreenMessage]
 *
 * Pitfalls:
 *   - IME overlap: root carries `Modifier.imePadding()`.
 *   - LazyColumn keys are stable (`key = { it.listId }`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListPickerScreen(
    onBack: () -> Unit,
    onCommit: () -> Unit,
    vm: ListPickerViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    ListPickerContent(
        state = state,
        onBack = onBack,
        onRetry = vm::onRetry,
        onToggleListSelect = vm::onToggleListSelect,
        onClearSelection = vm::onClearSelection,
        onCreateList = vm::onCreateList,
        onCommit = {
            vm.onCommit()
            onCommit()
        },
    )
}

@Composable
private fun ListPickerContent(
    state: ListPickerViewModel.UiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleListSelect: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onCreateList: (String) -> Unit,
    onCommit: () -> Unit,
) {
    // Inline create. rememberSaveable so a rotation mid-prompt doesn't drop
    // the dialog.
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    if (showCreateDialog) {
        CreateListNameDialog(
            onCreate = { name ->
                onCreateList(name)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
    val curtain = LocalPrivacyCurtain.current
    OrbitScreen {
        val title = if (state.contactName.isNotBlank() && !curtain) {
            stringResource(R.string.picker_lists_title_named, state.contactName)
        } else {
            stringResource(R.string.picker_lists_title)
        }
        OrbitAppBar(
            title = title,
            leading = {
                OrbitIconButton(
                    icon = "arrow-left",
                    onClick = onBack,
                    contentDescription = stringResource(R.string.components_action_back),
                )
            },
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            when (state.phase) {
                ListPickerViewModel.UiState.Phase.Loading -> {
                    // Silent — combine() emits Ready within ms.
                }
                // C6: missing/malformed contactId nav arg: terminal empty state.
                ListPickerViewModel.UiState.Phase.NotFound -> OrbitScreenMessage(
                    title = stringResource(R.string.picker_contact_not_found),
                    body = stringResource(R.string.picker_lists_contact_not_found_body),
                )
                ListPickerViewModel.UiState.Phase.Error -> OrbitScreenMessage(
                    icon = "warning-circle",
                    title = stringResource(R.string.picker_lists_error_title),
                    body = stringResource(R.string.picker_error_body),
                    actionLabel = stringResource(R.string.picker_try_again),
                    onAction = onRetry,
                    actionVariant = OrbitButtonVariant.Primary,
                )
                ListPickerViewModel.UiState.Phase.Ready,
                ListPickerViewModel.UiState.Phase.Committing -> ReadyContent(
                    state = state,
                    onToggleListSelect = onToggleListSelect,
                    onClearSelection = onClearSelection,
                    onNewList = { showCreateDialog = true },
                    onCommit = onCommit,
                )
            }
        }
    }
}

@Composable
private fun ReadyContent(
    state: ListPickerViewModel.UiState,
    onToggleListSelect: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onNewList: () -> Unit,
    onCommit: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (state.lists.isEmpty()) {
            // "Create a list first, then come back." was a dead-end. The list
            // is created right here; it appears selected so the next tap is the
            // commit CTA. The only action here, so it takes the accent.
            OrbitScreenMessage(
                icon = "list-bullets",
                title = stringResource(R.string.picker_lists_empty_title),
                body = stringResource(R.string.picker_lists_empty_body),
                actionLabel = stringResource(R.string.picker_lists_new_list),
                onAction = onNewList,
                actionVariant = OrbitButtonVariant.Primary,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = OrbitTheme.spacing.x10),
            ) {
                items(
                    items = state.lists,
                    key = { it.listId },
                ) { row ->
                    val isSelected = row.listId in state.selectedListIds
                    ListPickerRow(
                        name = row.name,
                        isSelected = isSelected,
                        isMember = row.isMember,
                        onToggle = { onToggleListSelect(row.listId) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        ListPickerFooter(
            selectionCount = state.selectionCount,
            isCommitting = state.phase == ListPickerViewModel.UiState.Phase.Committing,
            onClear = onClearSelection,
            onCommit = onCommit,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(OrbitTheme.spacing.x4),
        )
    }
}

@Composable
private fun ListPickerRow(
    name: String,
    isSelected: Boolean,
    isMember: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val curtain = LocalPrivacyCurtain.current
    val rowBackground = if (isSelected) OrbitTheme.colors.accentTint else Color.Transparent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = OrbitTheme.spacing.tapMin)
            .background(rowBackground)
            // The row is the checkbox (one target, one writer); the mark is
            // display-only. TalkBack: "Inner orbit, Already added, checkbox,
            // checked".
            .toggleable(value = isSelected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(
                horizontal = OrbitTheme.spacing.x4,
                vertical = OrbitTheme.spacing.x3,
            ),
    ) {
        Text(
            // List names are masked under the privacy curtain (ListContextChip).
            text = if (curtain) stringResource(R.string.components_curtain_list) else name,
            style = OrbitTheme.type.body,
            color = OrbitTheme.colors.fg,
            modifier = Modifier.weight(1f),
        )
        if (isMember) {
            // "added" read as "just added" as easily as "already in" (vision
            // PICK-1), and was lowercase.
            Text(
                text = stringResource(R.string.picker_lists_already_added),
                style = OrbitTheme.type.meta,
                color = OrbitTheme.colors.fgMuted,
                modifier = Modifier
                    .clip(OrbitTheme.shapes.sm)
                    .background(OrbitTheme.colors.bgSubtle)
                    .padding(
                        horizontal = OrbitTheme.spacing.x2,
                        vertical = OrbitTheme.spacing.x1,
                    ),
            )
        }
        OrbitCheckbox(checked = isSelected)
    }
}

@Composable
private fun ListPickerFooter(
    selectionCount: Int,
    isCommitting: Boolean,
    onClear: () -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selectionCount == 0) return

    val ctaCopy = pluralStringResource(R.plurals.picker_lists_commit, selectionCount, selectionCount)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .orbitCardShadow(OrbitTheme.shapes.lg, OrbitTheme.colors.isDark)
            .clip(OrbitTheme.shapes.lg)
            .background(OrbitTheme.colors.surface)
            .padding(
                horizontal = OrbitTheme.spacing.x4,
                vertical = OrbitTheme.spacing.x3,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = pluralStringResource(R.plurals.picker_selected_count, selectionCount, selectionCount),
                style = OrbitTheme.type.body,
                color = OrbitTheme.colors.fg,
                modifier = Modifier.weight(1f),
            )
            // A quiet text action, now with the 48dp floor (rules.md §Design 3;
            // it was about 38dp) and a button role.
            ClearSelectionAction(enabled = !isCommitting, onClear = onClear)
            OrbitButton(
                text = ctaCopy,
                onClick = onCommit,
                enabled = !isCommitting,
            )
        }
    }
}

/**
 * "Clear" for a selection footer: quiet text with a 48dp target. Shared with
 * [BatchCounter], whose Clear had the same 38dp miss.
 */
@Composable
internal fun ClearSelectionAction(enabled: Boolean, onClear: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minWidth = OrbitTheme.spacing.tapMin, minHeight = OrbitTheme.spacing.tapMin)
            .clip(OrbitTheme.shapes.md)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = stringResource(R.string.picker_clear_selection),
                onClick = onClear,
            )
            .padding(horizontal = OrbitTheme.spacing.x2),
    ) {
        Text(
            text = stringResource(R.string.picker_clear),
            style = OrbitTheme.type.button,
            color = OrbitTheme.colors.fgMuted,
        )
    }
}

/**
 * Minimal inline-create prompt. Pattern precedent:
 * [app.orbit.ui.screens.lists.RenameListDialog] (Material3 [AlertDialog] shell,
 * auto-focused single-line field, IME Done = commit, blank input cancels).
 * The heavier template-picker bottom sheet stays a Lists Manager concern —
 * here the user is mid-task adding a person; a name is all that's needed and
 * cadence defaults to keep-in-touch (editable later in List Configuration).
 */
@Composable
private fun CreateListNameDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var nameText by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    fun commit() {
        val trimmed = nameText.trim()
        if (trimmed.isNotEmpty()) {
            onCreate(trimmed)
        } else {
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OrbitTheme.colors.surface,
        title = {
            Text(
                text = stringResource(R.string.picker_lists_new_list),
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            )
        },
        text = {
            OutlinedTextField(
                value = nameText,
                onValueChange = { nameText = it },
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(OrbitTheme.type.body),
                placeholder = {
                    Text(
                        text = stringResource(R.string.picker_lists_name_hint),
                        style = OrbitTheme.type.body,
                        color = OrbitTheme.colors.fgMuted,
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    commit()
                    focusManager.clearFocus()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            OrbitButton(
                text = stringResource(R.string.picker_lists_create),
                onClick = { commit() },
                variant = OrbitButtonVariant.Primary,
            )
        },
        dismissButton = {
            OrbitButton(
                text = stringResource(R.string.components_action_cancel),
                onClick = onDismiss,
                variant = OrbitButtonVariant.Ghost,
            )
        },
    )
}

@Preview(name = "CreateListNameDialog — light", showBackground = true)
@Composable
private fun CreateListNameDialogPreview() {
    OrbitTheme(darkTheme = false) {
        CreateListNameDialog(onCreate = {}, onDismiss = {})
    }
}

// ─── Previews ──────────────────────────────────────────────────────────────────

@Preview(name = "ListPicker — Ready light", showBackground = true)
@Composable
private fun ListPickerReadyPreviewLight() {
    OrbitTheme(darkTheme = false) {
        ListPickerContent(
            state = previewState(selectionCount = 2),
            onBack = {},
            onRetry = {},
            onToggleListSelect = {},
            onClearSelection = {},
            onCreateList = {},
            onCommit = {},
        )
    }
}

@Preview(name = "ListPicker — Ready dark", showBackground = true)
@Composable
private fun ListPickerReadyPreviewDark() {
    OrbitTheme(darkTheme = true) {
        ListPickerContent(
            state = previewState(selectionCount = 1),
            onBack = {},
            onRetry = {},
            onToggleListSelect = {},
            onClearSelection = {},
            onCreateList = {},
            onCommit = {},
        )
    }
}

private fun previewState(selectionCount: Int): ListPickerViewModel.UiState {
    val rows = listOf(
        ListPickerViewModel.UiState.ListRow(listId = 1L, name = "Inner orbit", isMember = false),
        ListPickerViewModel.UiState.ListRow(listId = 2L, name = "Late night", isMember = true),
        ListPickerViewModel.UiState.ListRow(listId = 3L, name = "People who ground me", isMember = false),
        ListPickerViewModel.UiState.ListRow(listId = 4L, name = "Family", isMember = true),
    )
    val selected: Set<Long> = when (selectionCount) {
        0 -> emptySet()
        1 -> setOf(1L)
        else -> setOf(1L, 3L)
    }
    return ListPickerViewModel.UiState(
        phase = ListPickerViewModel.UiState.Phase.Ready,
        contactName = "Sarah Levin",
        lists = rows,
        selectedListIds = selected,
    )
}

// Combined preview for the stateless ListPickerContent
// (THEME-04 / THEME-05 — D-06). Reuses the existing previewState fixture.
@PreviewLightDark
@PreviewFontScale
@Composable
private fun ListPickerContentPreview() {
    OrbitTheme {
        ListPickerContent(
            state = previewState(selectionCount = 2),
            onBack = {},
            onRetry = {},
            onToggleListSelect = {},
            onClearSelection = {},
            onCreateList = {},
            onCommit = {},
        )
    }
}
