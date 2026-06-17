package app.orbit.ui.screens.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
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
 *   - LazyColumn of [ListPickerViewModel.UiState.ListRow] (name + Checkbox)
 *   - Sticky BatchCounter-like footer with locked CTA "Add to N list[s]"
 *   - No snackbar host — the screen pops on commit, so the result surfaces
 *     via [PickerCommitBus] on the app-level [PickerCommitSnackbarHost]
 *     (identical to the forward picker)
 *   - No filter chips, no search — list count is small
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
    OrbitScreen {
        val title = if (state.contactName.isNotBlank()) {
            "Add ${state.contactName} to lists"
        } else {
            "Add to lists"
        }
        OrbitAppBar(
            title = title,
            leading = {
                OrbitIconButton(
                    icon = "arrow-left",
                    onClick = onBack,
                    contentDescription = "Back",
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
                ListPickerViewModel.UiState.Phase.NotFound -> {
                    // C6: missing/malformed contactId nav arg — terminal empty state.
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Contact not found",
                            style = OrbitTheme.type.h3,
                            color = OrbitTheme.colors.fg,
                            modifier = Modifier.padding(horizontal = OrbitTheme.spacing.x6),
                        )
                    }
                }
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
            // commit CTA.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(OrbitTheme.spacing.x6),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "No lists yet",
                    style = OrbitTheme.type.h3,
                    color = OrbitTheme.colors.fg,
                )
                Text(
                    text = "Create one here and add this person in one more tap.",
                    style = OrbitTheme.type.body,
                    color = OrbitTheme.colors.fgMuted,
                    modifier = Modifier.padding(top = OrbitTheme.spacing.x2),
                )
                OrbitButton(
                    text = "New list",
                    onClick = onNewList,
                    modifier = Modifier.padding(top = OrbitTheme.spacing.x4),
                )
            }
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
    val rowBackground = if (isSelected) OrbitTheme.colors.accentTint else Color.Transparent
    val rowSemantics = Modifier.semantics {
        selected = isSelected
        if (isMember) contentDescription = "$name. Already in this list"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = OrbitTheme.spacing.tapMin)
            .background(rowBackground)
            .clickable(onClick = onToggle)
            .padding(
                horizontal = OrbitTheme.spacing.x4,
                vertical = OrbitTheme.spacing.x3,
            )
            .then(rowSemantics),
    ) {
        Text(
            text = name,
            style = OrbitTheme.type.body,
            color = OrbitTheme.colors.fg,
            modifier = Modifier.weight(1f),
        )
        if (isMember) {
            Text(
                text = "added",
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
        Checkbox(
            checked = isSelected,
            onCheckedChange = null,
        )
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

    val ctaCopy = if (selectionCount == 1) {
        "Add to 1 list"
    } else {
        "Add to $selectionCount lists"
    }

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
                text = "$selectionCount selected",
                style = OrbitTheme.type.body,
                color = OrbitTheme.colors.fg,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Clear",
                style = OrbitTheme.type.button,
                color = OrbitTheme.colors.fgMuted,
                modifier = Modifier
                    .clickable(enabled = !isCommitting, onClick = onClear)
                    .padding(horizontal = OrbitTheme.spacing.x2, vertical = OrbitTheme.spacing.x2),
            )
            OrbitButton(
                text = ctaCopy,
                onClick = onCommit,
                enabled = !isCommitting,
            )
        }
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
                text = "New list",
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
                        text = "Name this list",
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
                text = "Create",
                onClick = { commit() },
                variant = OrbitButtonVariant.Primary,
            )
        },
        dismissButton = {
            OrbitButton(
                text = "Cancel",
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
            onToggleListSelect = {},
            onClearSelection = {},
            onCreateList = {},
            onCommit = {},
        )
    }
}
