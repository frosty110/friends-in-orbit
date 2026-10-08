package app.orbit.ui.screens.contact.sections

// B3 — DOM-01 Clock-injection invariant. This file MUST NOT import the JVM
// time API and MUST NOT call any "current time" function. The relative and
// absolute timestamps are pre-formatted by the ViewModel mapper using the
// injected Clock; this composable only chooses which pre-formatted string to
// display. (Strings spelled out by hyphenation in this comment so the
// no-Instant-now grep gate stays clean.)

import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.R
import app.orbit.data.NoteRow
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitDropdownMenu
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitMenuAction
import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.components.OrbitTextField
import app.orbit.ui.components.PhIcon
import app.orbit.ui.components.SectionLabel
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString

/**
 * NOTE-01 — Notes journaling section on Contact Detail.
 *
 * Stateless composable: receives a list of [NoteRow] (newest-first; caller
 * pre-sorts), the current draft, and event callbacks. Renders an inline
 * input + Add button at the top, then each note as a swipe-to-dismiss row
 * with long-press-to-edit, both also reachable from the note's visible
 * "More" menu (Edit, Delete).
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
 *
 * [readOnly] is the orphaned page (CONTACT-06): the notes stay readable
 * ("History stays here") but the input, the swipe, the long press and the
 * per-note menu wait until the person is re-linked, like every other edit
 * affordance on that page. Until 2026-10-06 the notes vanished with the
 * phone contact.
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
    // wires this and signals (via focusRequester.requestFocus()) the note field
    // claims focus and the IME opens. Defaults to null so non-deep-link consumers
    // pay no behavior cost.
    inputFocusRequester: FocusRequester? = null,
    readOnly: Boolean = false
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Section eyebrow
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhIcon(
                name = "note-pencil",
                size = 14.dp,
                // fgMuted — the hero Call button is the screen's one
                // terracotta element (rules.md design rule 5).
                tint = OrbitTheme.colors.fgMuted
            )
            Spacer(Modifier.width(OrbitTheme.spacing.x2))
            SectionLabel(text = stringResource(R.string.contact_notes_title))
        }
        Spacer(Modifier.height(OrbitTheme.spacing.x3))

        // Input row + Add button
        if (!readOnly) {
            // Bottom-aligned, so Add stays by the last line as a note grows.
            Row(verticalAlignment = Alignment.Bottom) {
                // The placeholder is its name while empty; once typed into,
                // the same words still tell TalkBack what the field is for.
                val hint = stringResource(R.string.contact_notes_hint)
                OrbitTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    label = null,
                    contentDescription = hint,
                    placeholder = hint,
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = OrbitTheme.spacing.x2)
                        .then(
                            if (inputFocusRequester != null) {
                                Modifier.focusRequester(inputFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                )
                OrbitButton(
                    text = stringResource(R.string.contact_notes_add),
                    onClick = onAdd,
                    enabled = draft.isNotBlank(),
                    // Secondary: the hero Call button is the screen's one
                    // terracotta element (rules.md design rule 5).
                    variant = OrbitButtonVariant.Secondary
                )
            }
        }
        Spacer(Modifier.height(OrbitTheme.spacing.x4))

        // Notes list (Column — parent screen is the LazyColumn)
        if (notes.isEmpty()) {
            Text(
                text = stringResource(R.string.contact_notes_empty),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(vertical = OrbitTheme.spacing.x3)
            )
        } else {
            notes.forEach { note ->
                key(note.id) {
                    NoteRowItem(
                        note = note,
                        onDelete = onDelete,
                        onEditCommit = onEditCommit,
                        readOnly = readOnly
                    )
                }
            }
        }
    }
}

/**
 * One note. Swipe left still deletes and long-press still edits, but both now
 * have a visible path too: the trailing "More" button opens Edit and Delete.
 * Gesture-only actions are ones most people never find (rubric D5: every
 * gesture has a visible alternative).
 *
 * Under the privacy curtain the body reads "Note hidden": a note is as private
 * as a name (Card view hides the last note the same way), and it showed here
 * until 2026-10-05.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun NoteRowItem(
    note: NoteRow,
    onDelete: (NoteRow) -> Unit,
    onEditCommit: (NoteRow, String) -> Unit,
    readOnly: Boolean = false
) {
    val curtain = LocalPrivacyCurtain.current
    // No edit path at all under the curtain or on the orphaned page.
    val editable = !curtain && !readOnly
    var editing by remember(note.id) { mutableStateOf(false) }
    var draftEdit by remember(note.id) { mutableStateOf(note.body) }
    var showAbsolute by remember(note.id) { mutableStateOf(false) }
    var menuOpen by remember(note.id) { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            if (target == SwipeToDismissBoxValue.EndToStart) {
                onDelete(note)
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = !editing && !readOnly,
        backgroundContent = {
            // Delete, so it reads as delete: the danger tone the menus use,
            // on a quiet surface (it was a cream icon on terracotta tint).
            Box(
                Modifier
                    .fillMaxSize()
                    .background(OrbitTheme.colors.bgSubtle)
                    .padding(horizontal = OrbitTheme.spacing.x5),
                contentAlignment = Alignment.CenterEnd
            ) {
                PhIcon(
                    name = "trash",
                    size = 18.dp,
                    tint = OrbitTheme.colors.danger
                )
            }
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(OrbitTheme.colors.surface)
                .padding(
                    start = OrbitTheme.spacing.x3,
                    top = OrbitTheme.spacing.x1,
                    bottom = OrbitTheme.spacing.x3
                )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // B3: toggle between two VM-pre-formatted strings; no
                // JVM-time call here. A real 48dp button that keeps its text
                // for TalkBack (the old contentDescription replaced the date
                // with "Tap to show absolute date").
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = OrbitTheme.spacing.tapMin)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(
                                if (showAbsolute) R.string.contact_notes_show_relative else R.string.contact_notes_show_date
                            )
                        ) { showAbsolute = !showAbsolute }
                ) {
                    Text(
                        text = if (showAbsolute) note.absoluteTimestamp else note.relativeTimestamp?.asString().orEmpty(),
                        style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted)
                    )
                }
                if (!editing && editable) {
                    Box {
                        OrbitIconButton(
                            icon = "dots-three-vertical",
                            onClick = { menuOpen = true },
                            tint = OrbitTheme.colors.fgMuted,
                            contentDescription = stringResource(
                                R.string.contact_notes_more_actions
                            )
                        )
                        OrbitDropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            actions = noteMenuActions(
                                resources = LocalContext.current.resources,
                                onEdit = { editing = true },
                                onDelete = { onDelete(note) }
                            )
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(end = OrbitTheme.spacing.x3)) {
                if (editing) {
                    // Had no name at all for TalkBack, and an accent cursor on
                    // a screen whose one accent is Call (rules.md §Design 5, 7).
                    OrbitTextField(
                        value = draftEdit,
                        onValueChange = { draftEdit = it },
                        label = null,
                        contentDescription = stringResource(R.string.contact_notes_edit_note),
                        singleLine = false,
                    )
                    Spacer(Modifier.height(OrbitTheme.spacing.x2))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OrbitButton(
                            text = stringResource(R.string.components_action_cancel),
                            onClick = {
                                editing = false
                                draftEdit = note.body
                            },
                            variant = OrbitButtonVariant.Ghost
                        )
                        Spacer(Modifier.width(OrbitTheme.spacing.x2))
                        OrbitButton(
                            text = stringResource(R.string.components_action_save),
                            onClick = {
                                onEditCommit(note, draftEdit.trim())
                                editing = false
                            },
                            // Secondary: the hero Call button is the screen's
                            // one accent element (rules.md §Design 5).
                            variant = OrbitButtonVariant.Secondary,
                            enabled = draftEdit.isNotBlank() && draftEdit.trim() != note.body
                        )
                    }
                } else {
                    val editNoteLabel = stringResource(R.string.contact_notes_edit_note)
                    Text(
                        text = if (curtain) {
                            stringResource(
                                R.string.contact_notes_hidden
                            )
                        } else {
                            note.body
                        },
                        style = OrbitTheme.type.body.copy(
                            color = if (curtain) OrbitTheme.colors.fgMuted else OrbitTheme.colors.fg
                        ),
                        // Long press is a shortcut to Edit, which the note's
                        // menu offers visibly. It used to be a combinedClickable
                        // with an empty onClick, so TalkBack announced a tap
                        // that did nothing, on a 19dp-tall target. Now there is
                        // no tap, only the long press, which TalkBack offers as
                        // its own action ("double-tap and hold to edit note").
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (!editable) {
                                    Modifier
                                } else {
                                    Modifier
                                        .pointerInput(
                                            note.id
                                        ) { detectTapGestures(onLongPress = { editing = true }) }
                                        .semantics {
                                            onLongClick(label = editNoteLabel) {
                                                editing = true
                                                true
                                            }
                                        }
                                }
                            )
                    )
                }
            }
        }
    }
}

/**
 * A note's "More actions" menu, in the shared [OrbitDropdownMenu] contract:
 * Edit, then Delete in danger below the rule, both with an icon (all or
 * none). `internal` so the labels, order and tone are pinned on the JVM
 * (NotesMenuTest); takes [Resources] because [OrbitMenuAction] carries
 * resolved text (the `contactOverflowActions` precedent).
 */
internal fun noteMenuActions(
    resources: Resources,
    onEdit: () -> Unit,
    onDelete: () -> Unit
): List<OrbitMenuAction> = listOf(
    OrbitMenuAction(
        label = resources.getString(R.string.contact_notes_edit),
        onClick = onEdit,
        icon = "pencil-simple"
    ),
    OrbitMenuAction(
        label = resources.getString(R.string.components_action_delete),
        onClick = onDelete,
        icon = "trash",
        tone = OrbitMenuTone.Destructive
    )
)

// ============================================================================
// Previews — empty + populated, light + dark, plus a 200%-scale variant.
// ============================================================================

private fun previewNote(
    id: Long,
    body: String,
    relative: UiText = UiText.plural(R.plurals.time_ago_days, 14, 14),
    absolute: String = "mar 14 · 2:14 pm"
): NoteRow = NoteRow(
    id = id,
    contactId = 1L,
    body = body,
    createdAtMs = 0L,
    relativeTimestamp = relative,
    absoluteTimestamp = absolute
)

private val PREVIEW_TODAY: UiText = UiText.res(R.string.time_ago_today)

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
                onEditCommit = { _, _ -> }
            )
        }
    }
}

@Preview(name = "NotesSection — populated, light")
@Composable
private fun NotesSectionPreviewPopulatedLight() {
    OrbitTheme(darkTheme = false) {
        // Scrolls like the contact page's column, so a short landscape window
        // does not squeeze the last note's controls under 48dp.
        Column(Modifier.padding(OrbitTheme.spacing.x4).verticalScroll(rememberScrollState())) {
            NotesSection(
                notes = listOf(
                    previewNote(
                        1L,
                        "Met for coffee. He just moved into a new place.",
                        PREVIEW_TODAY,
                        "today · 9:14 am"
                    ),
                    previewNote(
                        2L,
                        "Asked about the kids. Sounds steady.",
                        UiText.plural(R.plurals.time_ago_days, 3, 3)
                    ),
                    previewNote(3L, "Long catch-up call. Owes me a hike.")
                ),
                draft = "Followed up about the gig",
                onDraftChange = {},
                onAdd = {},
                onDelete = {},
                onEditCommit = { _, _ -> }
            )
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "NotesSection — populated, dark")
@Composable
private fun NotesSectionPreviewPopulatedDark() {
    OrbitTheme(darkTheme = true) {
        Column(Modifier.padding(OrbitTheme.spacing.x4).verticalScroll(rememberScrollState())) {
            NotesSection(
                notes = listOf(
                    previewNote(
                        1L,
                        "Met for coffee. He just moved into a new place.",
                        PREVIEW_TODAY,
                        "today · 9:14 am"
                    ),
                    previewNote(
                        2L,
                        "Asked about the kids. Sounds steady.",
                        UiText.plural(R.plurals.time_ago_days, 3, 3)
                    )
                ),
                draft = "",
                onDraftChange = {},
                onAdd = {},
                onDelete = {},
                onEditCommit = { _, _ -> }
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
                    previewNote(
                        1L,
                        "Met for coffee. He just moved into a new place.",
                        PREVIEW_TODAY,
                        "today · 9:14 am"
                    )
                ),
                draft = "",
                onDraftChange = {},
                onAdd = {},
                onDelete = {},
                onEditCommit = { _, _ -> }
            )
        }
    }
}
