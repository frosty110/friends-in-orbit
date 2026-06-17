package app.orbit.ui.screens.contact.sections

// B3 — DOM-01 Clock-injection invariant. This file MUST NOT import the JVM
// time API and MUST NOT call any "current time" function. The relative and
// absolute timestamps are pre-formatted by the ViewModel mapper using the
// injected Clock; this composable only chooses which pre-formatted string to
// display. (Strings spelled out by hyphenation in this comment so the
// no-Instant-now grep gate stays clean.)

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.data.NoteRow
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme

/**
 * NOTE-01 — Notes journaling section on Contact Detail.
 *
 * Stateless composable: receives a list of [NoteRow] (newest-first; caller
 * pre-sorts), the current draft, and event callbacks. Renders an inline
 * input + Add button at the top, then each note as a swipe-to-dismiss row
 * with long-press-to-edit overlay.
 *
 * Layout note: this section uses [Column] (NOT a nested LazyColumn). The
 * parent screen already lives inside a LazyColumn — nesting another
 * LazyColumn here would trigger Compose's height-ambiguity crash. Per-row
 * keys via [key] preserve SwipeToDismissBox state across recomposition.
 *
 * Voice contract:
 *   - sentence case throughout
 *   - no exclamation marks
 *   - no gamification ("streak", "great job")
 *
 * B3 — Clock-free composable. The relative timestamp is read from
 * [NoteRow.relativeTimestamp] (pre-formatted by VM); tapping the timestamp
 * toggles the row to display [NoteRow.absoluteTimestamp] instead. This file
 * has zero JVM-time imports and zero "current-time" calls so the
 * DOM-01 Clock-injection invariant holds end-to-end.
 */
@Composable
fun NotesSection(
    notes: List<NoteRow>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (NoteRow) -> Unit,
    onEditCommit: (NoteRow, String) -> Unit,
    modifier: Modifier = Modifier,
    // NOTE-02 — optional FocusRequester for the input. When the parent screen
    // wires this and signals (via focusRequester.requestFocus()) the BasicTextField
    // claims focus and the IME opens. Defaults to null so non-deep-link consumers
    // pay no behavior cost.
    inputFocusRequester: FocusRequester? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Section eyebrow
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhIcon(
                name = "note-pencil",
                size = 14.dp,
                // fgMuted — the hero Call button is the screen's one
                // terracotta element (rules.md design rule 5).
                tint = OrbitTheme.colors.fgMuted,
            )
            Spacer(Modifier.width(OrbitTheme.spacing.x2))
            Text(
                text = "Notes",
                style = OrbitTheme.type.eyebrow.copy(color = OrbitTheme.colors.fgMuted),
            )
        }
        Spacer(Modifier.height(OrbitTheme.spacing.x3))

        // Input row + Add button
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                maxLines = 4,
                textStyle = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(OrbitTheme.colors.accent),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .clip(OrbitTheme.shapes.md)
                            .background(OrbitTheme.colors.bgSubtle)
                            .padding(
                                horizontal = OrbitTheme.spacing.x4,
                                vertical = OrbitTheme.spacing.x3,
                            )
                            .defaultMinSize(minHeight = OrbitTheme.spacing.tapMin),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (draft.isEmpty()) {
                            Text(
                                text = "Add a note",
                                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = OrbitTheme.spacing.x2)
                    .then(
                        if (inputFocusRequester != null) {
                            Modifier.focusRequester(inputFocusRequester)
                        } else {
                            Modifier
                        },
                    ),
            )
            OrbitButton(
                text = "Add",
                onClick = onAdd,
                enabled = draft.isNotBlank(),
                // Secondary — the hero Call button is the screen's one
                // terracotta element (rules.md design rule 5).
                variant = OrbitButtonVariant.Secondary,
            )
        }
        Spacer(Modifier.height(OrbitTheme.spacing.x4))

        // Notes list (Column — parent screen is the LazyColumn)
        if (notes.isEmpty()) {
            Text(
                text = "No notes yet.",
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(vertical = OrbitTheme.spacing.x3),
            )
        } else {
            notes.forEach { note ->
                key(note.id) {
                    NoteRowItem(
                        note = note,
                        onDelete = onDelete,
                        onEditCommit = onEditCommit,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun NoteRowItem(
    note: NoteRow,
    onDelete: (NoteRow) -> Unit,
    onEditCommit: (NoteRow, String) -> Unit,
) {
    var editing by remember(note.id) { mutableStateOf(false) }
    var draftEdit by remember(note.id) { mutableStateOf(note.body) }
    var showAbsolute by remember(note.id) { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            if (target == SwipeToDismissBoxValue.EndToStart) {
                onDelete(note)
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(OrbitTheme.colors.accentTint)
                    .padding(horizontal = OrbitTheme.spacing.x5),
                contentAlignment = Alignment.CenterEnd,
            ) {
                PhIcon(
                    name = "trash",
                    size = 18.dp,
                    tint = OrbitTheme.colors.bg,
                )
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(OrbitTheme.colors.surface)
                .padding(
                    horizontal = OrbitTheme.spacing.x3,
                    vertical = OrbitTheme.spacing.x3,
                ),
        ) {
            Text(
                // B3 — toggle between two VM-pre-formatted strings; no JVM-time call here.
                text = if (showAbsolute) note.absoluteTimestamp else note.relativeTimestamp,
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier
                    .clickable { showAbsolute = !showAbsolute }
                    .semantics {
                        contentDescription =
                            if (showAbsolute) "Tap to show relative date"
                            else "Tap to show absolute date"
                    },
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x1))
            if (editing) {
                BasicTextField(
                    value = draftEdit,
                    onValueChange = { draftEdit = it },
                    textStyle = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(OrbitTheme.colors.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(OrbitTheme.shapes.md)
                        .background(OrbitTheme.colors.bgSubtle)
                        .padding(OrbitTheme.spacing.x3),
                )
                Spacer(Modifier.height(OrbitTheme.spacing.x2))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OrbitButton(
                        text = "Cancel",
                        onClick = {
                            editing = false
                            draftEdit = note.body
                        },
                        variant = OrbitButtonVariant.Ghost,
                    )
                    Spacer(Modifier.width(OrbitTheme.spacing.x2))
                    OrbitButton(
                        text = "Save",
                        onClick = {
                            onEditCommit(note, draftEdit.trim())
                            editing = false
                        },
                        variant = OrbitButtonVariant.Primary,
                        enabled = draftEdit.isNotBlank() && draftEdit.trim() != note.body,
                    )
                }
            } else {
                Text(
                    text = note.body,
                    style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                    modifier = Modifier
                        .defaultMinSize(minHeight = OrbitTheme.spacing.tapMin)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { editing = true },
                        ),
                )
            }
        }
    }
}

// ============================================================================
// Previews — empty + populated, light + dark, plus a 200%-scale variant.
// ============================================================================

private fun previewNote(
    id: Long,
    body: String,
    relative: String = "14 days ago",
    absolute: String = "mar 14 · 2:14 pm",
): NoteRow = NoteRow(
    id = id,
    contactId = 1L,
    body = body,
    createdAtMs = 0L,
    relativeTimestamp = relative,
    absoluteTimestamp = absolute,
)

@Preview(name = "NotesSection — empty, light")
@Composable
private fun NotesSectionPreviewEmptyLight() {
    OrbitTheme(darkTheme = false) {
        Column(Modifier.padding(OrbitTheme.spacing.x4)) {
            NotesSection(
                notes = emptyList(),
                draft = "",
                onDraftChange = {},
                onAdd = {},
                onDelete = {},
                onEditCommit = { _, _ -> },
            )
        }
    }
}

@Preview(name = "NotesSection — populated, light")
@Composable
private fun NotesSectionPreviewPopulatedLight() {
    OrbitTheme(darkTheme = false) {
        Column(Modifier.padding(OrbitTheme.spacing.x4)) {
            NotesSection(
                notes = listOf(
                    previewNote(1L, "Met for coffee. He just moved into a new place.", "today", "today · 9:14 am"),
                    previewNote(2L, "Asked about the kids. Sounds steady.", "3 days ago"),
                    previewNote(3L, "Long catch-up call. Owes me a hike."),
                ),
                draft = "Followed up about the gig",
                onDraftChange = {},
                onAdd = {},
                onDelete = {},
                onEditCommit = { _, _ -> },
            )
        }
    }
}

@Preview(name = "NotesSection — populated, dark")
@Composable
private fun NotesSectionPreviewPopulatedDark() {
    OrbitTheme(darkTheme = true) {
        Column(Modifier.padding(OrbitTheme.spacing.x4)) {
            NotesSection(
                notes = listOf(
                    previewNote(1L, "Met for coffee. He just moved into a new place.", "today", "today · 9:14 am"),
                    previewNote(2L, "Asked about the kids. Sounds steady.", "3 days ago"),
                ),
                draft = "",
                onDraftChange = {},
                onAdd = {},
                onDelete = {},
                onEditCommit = { _, _ -> },
            )
        }
    }
}

@Preview(name = "NotesSection — populated, 200pct font", fontScale = 2.0f)
@Composable
private fun NotesSectionPreviewPopulated200() {
    OrbitTheme(darkTheme = false) {
        Column(Modifier.padding(OrbitTheme.spacing.x4)) {
            NotesSection(
                notes = listOf(
                    previewNote(1L, "Met for coffee. He just moved into a new place.", "today", "today · 9:14 am"),
                ),
                draft = "",
                onDraftChange = {},
                onAdd = {},
                onDelete = {},
                onEditCommit = { _, _ -> },
            )
        }
    }
}
