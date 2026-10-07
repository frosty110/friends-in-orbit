package app.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import app.orbit.R
import app.orbit.data.ChipTone
import app.orbit.data.Contact
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString

/**
 * Reusable row used by Browse + Global Search.
 *
 * Visual contract (BROWSE-01 + queue-order merge):
 *   - Position-number column (24dp min-width) → Avatar (44dp) → Column(name h3 +
 *     #19 due-dot/status word + meta last-call) → trailing phone icon
 *   - Position number renders blank when [queuePosition] is null (GlobalSearch
 *     consumers + Browse's "Other members" section); default preserves the legacy
 *     call site. The queue head ([isHead]) reads in fg, the rest in fgMuted.
 *     It was accent until 2026-10-05; with the due dots that put two kinds of
 *     accent element on Browse (rules.md §Design 5). The due dot is now the
 *     screen's one accent element: it marks who is ready, which is what Browse
 *     is for.
 *   - The position column is independent of the #19 due-dot — a row can show a
 *     queue position AND a due dot at once.
 *   - Row min-height = 48dp (tap target floor — rules.md §Design 3)
 *   - Hairline divider via parent (BrowseListScreen draws between rows)
 *
 * 2026-06-09 #19 — orientation additions (all default-valued so the Global
 * Search call site is untouched):
 *   - [due] renders the spec'd quiet due dot next to the name
 *     (features/browse/README.md:23,34 — accent token, dot not badge).
 *   - [statusLabel] ("Paused" / "Ignored") renders a small muted status word
 *     after the name and mutes the name itself.
 *   - [showCallMeta] = false suppresses the last-call line entirely — when
 *     READ_CALL_LOG is denied, "Never called" would be a false claim.
 *
 * Curtain (PRIV-03 / CORE-08): contact name reads "Contact" when
 * [LocalPrivacyCurtain] `.current` is true.
 *
 * Accessibility (UI-SPEC §BROWSE-05): two CustomAccessibilityActions —
 * "Call {FirstName}" and "Open details". Row body tap opens detail; trailing
 * phone icon tap dials. Two distinct hit areas, each ≥ `spacing.tapMin`.
 *
 * [onTap] = null when the CALLER owns the row gesture (Browse wraps each row
 * in a `combinedClickable` for tap, long-press quick actions and
 * multi-select). The row then adds no clickable of its own: a clickable here
 * consumes the press before the parent sees it, which is how Browse rows used
 * to ignore taps and long-presses entirely. "Open details" is left to the
 * caller's click semantics in that case.
 *
 * Browse's sequence (2026-10-07), all default-valued so Search is untouched:
 *   - [leadingCell] swaps the 24dp number column and its 20dp inset for one
 *     48dp cell: a grip and the number. With [dragHandle] the cell is the
 *     drag handle (the caller's `draggableHandle()` modifier, BROWSE-08),
 *     named [dragHandleLabel] for TalkBack, the Lists row's precedent; without
 *     it the grip is blank, so a paused row's avatar lines up with a draggable
 *     one. The cell replaces the 20dp inset and the 24dp number column
 *     rather than adding to them, so the name loses 4dp, not 48, at 200%.
 *   - [whenLabel] opens the second line: "Up now · Last call: 3 days ago"
 *     (BROWSE-07), or "Until 12 Oct" on a paused row; alone when the call
 *     line is hidden.
 *   - [marker] sits over the name as a quiet chip: "On your card" (BROWSE-09).
 *     Stone, not the accent: the due dot stays the screen's one accent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowseRow(
    contact: Contact,
    onTap: (() -> Unit)?,
    onDial: () -> Unit,
    modifier: Modifier = Modifier,
    due: Boolean = contact.due,
    statusLabel: String? = null,
    showCallMeta: Boolean = true,
    queuePosition: Int? = null, // null → render blank 24dp column (GlobalSearch + "Other members" rows)
    isHead: Boolean = false, // queue head (position 1) → fg (not muted) on the position number
    // False hides the dial button and its "Call" accessibility action. Browse's
    // multi-select passes false: the button used to stay visible but inert
    // there, a dead control (vision BROWSE-3). Defaults to true, so existing
    // callers are unchanged.
    showDial: Boolean = true,
    whenLabel: String? = null,
    marker: String? = null,
    leadingCell: Boolean = false,
    dragHandle: Modifier? = null,
    dragHandleLabel: String? = null,
    // More TalkBack actions after "Call" and "Open details" (Browse's "Move
    // up" and "Move down", BROWSE-08). They go in this row's one list: a
    // caller that set its own list on an ancestor would replace this one,
    // "Call {name}" included, since the merged node keeps the parent's.
    extraActions: List<CustomAccessibilityAction> = emptyList()
) {
    val curtain = LocalPrivacyCurtain.current
    val displayName = if (curtain) stringResource(R.string.components_curtain_contact) else contact.name
    val firstName = displayName.substringBefore(' ').ifBlank { displayName }
    // Resolved here: the semantics blocks below are not composable.
    val callLabel = stringResource(R.string.components_browse_row_call, firstName)
    // Shared with Card view's face, so TalkBack hears one phrase for one
    // destination (voice.md glossary, one word for one idea).
    val openDetailsLabel = stringResource(R.string.components_action_open_details)
    val dueDescription = stringResource(R.string.components_browse_row_due)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = OrbitTheme.spacing.tapMin)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .then(
                // The leading cell is its own inset (see the KDoc).
                if (leadingCell) {
                    Modifier.padding(end = OrbitTheme.spacing.x5, top = OrbitTheme.spacing.x3, bottom = OrbitTheme.spacing.x3)
                } else {
                    Modifier.padding(horizontal = OrbitTheme.spacing.x5, vertical = OrbitTheme.spacing.x3)
                }
            )
            .semantics {
                customActions = buildList {
                    if (showDial) {
                        add(
                            CustomAccessibilityAction(label = callLabel) {
                                onDial()
                                true
                            }
                        )
                    }
                    if (onTap != null) {
                        add(
                            CustomAccessibilityAction(label = openDetailsLabel) {
                                onTap()
                                true
                            }
                        )
                    }
                    addAll(extraActions)
                }
            }
    ) {
        // The number column: QueuePositionText documents its 24dp.
        if (leadingCell) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = (dragHandle ?: Modifier)
                    .defaultMinSize(minWidth = OrbitTheme.spacing.tapMin, minHeight = OrbitTheme.spacing.tapMin)
                    .then(
                        if (dragHandle != null && dragHandleLabel != null) {
                            Modifier.semantics { contentDescription = dragHandleLabel }
                        } else {
                            Modifier
                        }
                    )
            ) {
                Box(modifier = Modifier.width(OrbitTheme.spacing.x6), contentAlignment = Alignment.Center) {
                    if (dragHandle != null) {
                        PhIcon(
                            name = "dots-six-vertical",
                            size = OrbitTheme.spacing.x4, // 16dp visual; the cell stays 48dp
                            tint = OrbitTheme.colors.fgSubtle
                        )
                    }
                }
                QueuePositionText(queuePosition, isHead)
            }
        } else {
            QueuePositionText(queuePosition, isHead)
        }
        Avatar(name = displayName, size = 44.dp, photoUri = if (curtain) null else contact.photoUri)
        Column(modifier = Modifier.weight(1f)) {
            if (marker != null) {
                OrbitChip(
                    label = marker,
                    tone = ChipTone.Stone,
                    modifier = Modifier.padding(bottom = OrbitTheme.spacing.x1)
                )
            }
            // The name line. The dot sits in the name's own row, which measures
            // it first and lets the name wrap by word in what is left; the
            // status word flows onto the next line when there is no room
            // beside them. In one plain Row (until 2026-10-07) a long name at
            // 200% squeezed the dot to nothing and broke "Paused" letter by
            // letter (rubric gate G3).
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2)
                ) {
                    Text(
                        text = displayName,
                        // Paused/ignored rows read muted — visually distinct without
                        // shouting (#19).
                        color = if (statusLabel != null) OrbitTheme.colors.fgMuted else OrbitTheme.colors.fg,
                        style = OrbitTheme.type.h3,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (due && statusLabel == null) {
                        // Quiet due dot (accent token per features/browse/README.md:34).
                        // TalkBack hears "Worth a call now", not "Due": the app
                        // retired deadline words with HOME-6 (voice.md).
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(OrbitTheme.shapes.full)
                                .background(OrbitTheme.colors.accent)
                                .semantics { contentDescription = dueDescription }
                        )
                    }
                }
                if (statusLabel != null) {
                    Text(
                        text = statusLabel,
                        style = OrbitTheme.type.meta,
                        color = OrbitTheme.colors.fgSubtle
                    )
                }
            }
            val lastCallText = if (showCallMeta) {
                val lastCalled = contact.lastCalledLabel
                if (lastCalled == null) {
                    stringResource(R.string.components_browse_row_never_called)
                } else {
                    stringResource(R.string.components_browse_row_last_call, lastCalled.asString())
                }
            } else {
                null
            }
            val secondaryText = when {
                whenLabel != null && lastCallText != null ->
                    stringResource(R.string.components_browse_row_when_meta, whenLabel, lastCallText)
                else -> whenLabel ?: lastCallText
            }
            if (secondaryText != null) {
                Text(
                    text = secondaryText,
                    style = OrbitTheme.type.meta,
                    color = OrbitTheme.colors.fgMuted
                )
            }
        }
        // Trailing phone icon: a separate tap target ≥48dp. Muted, not accent:
        // it repeats on every row, and an accent icon per row spent the
        // screen's one accent element N times (rules.md Design 5). The row's
        // accent is reserved for the due dot.
        if (showDial) {
            Box(
                modifier = Modifier
                    .defaultMinSize(
                        minWidth = OrbitTheme.spacing.tapMin,
                        minHeight = OrbitTheme.spacing.tapMin
                    )
                    // Named, so TalkBack says "Call Avery, button" rather than
                    // "unlabelled" (rubric gate G2). Muted per rules.md Design 6.
                    .clickable(onClickLabel = callLabel, role = Role.Button, onClick = onDial)
                    .semantics { contentDescription = callLabel },
                contentAlignment = Alignment.Center
            ) {
                PhIcon(
                    name = "phone-call",
                    size = 22.dp,
                    tint = OrbitTheme.colors.fgMuted
                )
            }
        }
    }
}

// Documented dp-token exception (project convention, Code rule 2): `widthIn(min = 24.dp)`
// is the sole raw `.dp` here. Rationale: `OrbitSpacing` exposes a 4dp grid (x1..x10) plus
// `tapMin = 48dp`; none describe a "single-glyph numeric column width", and 24dp keeps
// single-digit and 99-cap two-digit positions aligned without a one-off token. Blank when
// [position] is null so rows outside the queue and Search rows still align.
@Composable
private fun QueuePositionText(position: Int?, isHead: Boolean) {
    Text(
        text = position?.let { "$it" } ?: "",
        style = OrbitTheme.type.statValue,
        color = if (isHead) OrbitTheme.colors.fg else OrbitTheme.colors.fgMuted,
        textAlign = TextAlign.End,
        modifier = Modifier.widthIn(min = 24.dp)
    )
}

// 2026-06-09 #19 — preview fixtures for the new due / status / no-meta states.
private fun previewContact(name: String, lastCalled: UiText?) = Contact(
    id = "preview-$name",
    name = name,
    phone = "+1 555 0100",
    lastCalledLabel = lastCalled,
    avgLengthLabel = null,
    pickupRateLabel = "",
    totalCalls = 0,
    due = false,
    listIds = emptyList(),
    bestWindowLabel = null,
    heat = FloatArray(24) { 0f },
    history = emptyList(),
    notes = emptyList(),
    patternNote = ""
)

@PreviewLightDark
@PreviewFontScale
@Composable
private fun BrowseRowPreview() {
    OrbitTheme {
        Column(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            // Browse's sequence row: the grip and number cell, when, and the
            // card's mark. The label masks under the curtain as Browse's does.
            BrowseRow(
                contact = previewContact("Mei Tanaka", UiText.plural(R.plurals.time_ago_days, 4, 4)),
                onTap = null,
                onDial = {},
                due = true,
                queuePosition = 1,
                isHead = true,
                whenLabel = stringResource(R.string.browse_when_up_now),
                marker = stringResource(R.string.browse_row_on_your_card),
                leadingCell = true,
                dragHandle = Modifier,
                dragHandleLabel = if (LocalPrivacyCurtain.current) {
                    stringResource(R.string.browse_row_reorder_unnamed)
                } else {
                    stringResource(R.string.browse_row_reorder, "Mei Tanaka")
                }
            )
            BrowseRow(
                contact = previewContact("Avery Quinn", UiText.plural(R.plurals.time_ago_days, 3, 3)),
                onTap = {},
                onDial = {},
                due = true,
                queuePosition = 1,
                isHead = true
            )
            BrowseRow(
                contact = previewContact("Sam Patel", UiText.plural(R.plurals.time_ago_months, 2, 2)),
                onTap = {},
                onDial = {},
                statusLabel = stringResource(R.string.browse_row_paused)
            )
            BrowseRow(
                contact = previewContact("Jordan Lee", null),
                onTap = {},
                onDial = {},
                showCallMeta = false
            )
        }
    }
}
