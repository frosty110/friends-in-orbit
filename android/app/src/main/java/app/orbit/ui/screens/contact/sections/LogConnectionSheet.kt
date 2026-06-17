package app.orbit.ui.screens.contact.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.PhIcon
import app.orbit.ui.screens.contact.LogConnectionWhen
import app.orbit.ui.theme.OrbitTheme
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * "Log a connection" — records a connection Orbit's call-log sync can't see
 * (another app, in person) so the contact stops surfacing as due.
 *
 * ModalBottomSheet launched from the Contact Detail hero action row. Three
 * radio-style "when" options (Today / Yesterday / Pick a date via the Material
 * date picker) + an optional one-line note + a single Primary confirm.
 *
 * On confirm the parent routes to `ContactDetailViewModel.onLogConnection`,
 * which writes a `CallEventEntity(source = MANUAL, durationSeconds = 0)`
 * through MarkCalledUseCase — same atomic nextDueAt-recompute path as the
 * call-log reconciler.
 *
 * Pitfall 1 dismissal pattern (PauseSheet convention): hide animates first,
 * then the parent visibility flag flips via [onDismiss].
 *
 * Time hygiene: this composable never reads "now" for display — the VM
 * resolves Today/Yesterday against its injected Clock. The single
 * `System.currentTimeMillis()` here only bounds the date picker so future
 * days are unselectable; the VM clamps `occurredAt` to now regardless.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogConnectionSheet(
    onConfirm: (whenChoice: LogConnectionWhen, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // 0 = today, 1 = yesterday, 2 = picked date. Plain Ints/Longs so
    // rememberSaveable needs no custom Saver for the sealed choice type.
    var selected by rememberSaveable { mutableStateOf(0) }
    var pickedDateMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    fun commitAndDismiss() {
        val choice = when (selected) {
            0 -> LogConnectionWhen.Today
            1 -> LogConnectionWhen.Yesterday
            else -> LogConnectionWhen.OnDate(pickedDateMillis ?: return)
        }
        onConfirm(choice, note.trim())
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OrbitTheme.colors.surface,
        shape = OrbitTheme.shapes.bottomSheet,
    ) {
        LogConnectionSheetContent(
            selected = selected,
            pickedDateLabel = pickedDateMillis?.let(::formatPickedDate),
            note = note,
            confirmEnabled = selected != 2 || pickedDateMillis != null,
            onSelect = { idx ->
                selected = idx
                if (idx == 2) showDatePicker = true
            },
            onNoteChange = { note = it },
            onConfirm = ::commitAndDismiss,
        )
    }

    if (showDatePicker) {
        // The picker hands back UTC midnight of the chosen calendar day; the
        // VM converts to a local-noon Instant. Future days are unselectable.
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = pickedDateMillis,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis <= System.currentTimeMillis()
            },
        )
        fun closePicker() {
            showDatePicker = false
            // Nothing picked and nothing previously picked — fall back to
            // Today so the confirm button never points at an empty date.
            if (pickedDateMillis == null) selected = 0
        }
        DatePickerDialog(
            onDismissRequest = ::closePicker,
            confirmButton = {
                OrbitButton(
                    text = "Done",
                    onClick = {
                        datePickerState.selectedDateMillis?.let { pickedDateMillis = it }
                        closePicker()
                    },
                    variant = OrbitButtonVariant.Ghost,
                    enabled = datePickerState.selectedDateMillis != null,
                )
            },
            dismissButton = {
                OrbitButton(
                    text = "Cancel",
                    onClick = ::closePicker,
                    variant = OrbitButtonVariant.Ghost,
                )
            },
            colors = DatePickerDefaults.colors(containerColor = OrbitTheme.colors.surface),
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

/**
 * Stateless sheet body — shared by the live ModalBottomSheet and the previews
 * (ModalBottomSheet is window-anchored and renders nothing inside @Preview).
 */
@Composable
private fun LogConnectionSheetContent(
    selected: Int,
    pickedDateLabel: String?,
    note: String,
    confirmEnabled: Boolean,
    onSelect: (Int) -> Unit,
    onNoteChange: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = OrbitTheme.spacing.x6,
                vertical = OrbitTheme.spacing.x4,
            ),
    ) {
        Text(
            text = "Log a connection",
            style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        Text(
            text = "For the calls Orbit can't see — another app, or in person.",
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x4))
        WhenOptionRow(
            label = "Today",
            selected = selected == 0,
            onSelect = { onSelect(0) },
        )
        WhenOptionRow(
            label = "Yesterday",
            selected = selected == 1,
            onSelect = { onSelect(1) },
        )
        WhenOptionRow(
            label = pickedDateLabel ?: "Pick a date",
            selected = selected == 2,
            onSelect = { onSelect(2) },
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x4))
        BasicTextField(
            value = note,
            onValueChange = onNoteChange,
            singleLine = true,
            textStyle = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            cursorBrush = SolidColor(OrbitTheme.colors.accent),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(OrbitTheme.shapes.md)
                        .background(OrbitTheme.colors.bgSubtle)
                        .padding(
                            horizontal = OrbitTheme.spacing.x4,
                            vertical = OrbitTheme.spacing.x3,
                        )
                        .defaultMinSize(minHeight = OrbitTheme.spacing.tapMin),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (note.isEmpty()) {
                        Text(
                            text = "Add a note (optional)",
                            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x4))
        OrbitButton(
            text = "Log connection",
            onClick = onConfirm,
            enabled = confirmEnabled,
            variant = OrbitButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x4))
    }
}

/** Radio-style "when" row — 48dp target, RadioButton semantics, quiet check mark. */
@Composable
private fun WhenOptionRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = OrbitTheme.spacing.tapMin)
            .clip(OrbitTheme.shapes.md)
            .background(if (selected) OrbitTheme.colors.bgSubtle else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = OrbitTheme.spacing.x4),
    ) {
        Text(
            text = label,
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            PhIcon(name = "check", size = 18.dp, tint = OrbitTheme.colors.fg)
        }
    }
}

/** "5 Jun 2026" — pure formatting of the user's pick; no clock read. */
private fun formatPickedDate(utcMidnightMillis: Long): String =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
        .format(Instant.ofEpochMilli(utcMidnightMillis).atZone(ZoneOffset.UTC).toLocalDate())

// region Previews — sheet content rendered directly (PauseSheet convention).

@Preview(name = "LogConnectionSheet — light", showBackground = true)
@Composable
private fun LogConnectionSheetLightPreview() {
    OrbitTheme(darkTheme = false) {
        Box(modifier = Modifier.background(OrbitTheme.colors.surface)) {
            LogConnectionSheetContent(
                selected = 0,
                pickedDateLabel = null,
                note = "",
                confirmEnabled = true,
                onSelect = {},
                onNoteChange = {},
                onConfirm = {},
            )
        }
    }
}

@Preview(name = "LogConnectionSheet — dark", showBackground = true)
@Composable
private fun LogConnectionSheetDarkPreview() {
    OrbitTheme(darkTheme = true) {
        Box(modifier = Modifier.background(OrbitTheme.colors.surface)) {
            LogConnectionSheetContent(
                selected = 2,
                pickedDateLabel = "5 Jun 2026",
                note = "Coffee after the meeting",
                confirmEnabled = true,
                onSelect = {},
                onNoteChange = {},
                onConfirm = {},
            )
        }
    }
}

// endregion
