package app.orbit.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import app.orbit.data.entity.CallDirection
import app.orbit.ui.components.Avatar
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme
import coil.compose.SubcomposeAsyncImage

/**
 * HOME-8 — the day behind a rhythm bar.
 *
 * Tapping a day column on the 7-day strip opens this sheet: who you actually
 * spoke to that day, which way each call went, how long it ran, and when.
 * The strip answers "how was my week"; this answers "who was that".
 *
 * The direction language is deliberately symmetric and unweighted —
 * "You called" / "They called", never "you only made N calls". The strip is
 * reflection, not a scoreboard (vision/00-home HOME-7), and the summary line
 * keeps that voice: it counts both sides in the same breath.
 *
 * Rows tap through to Contact Detail. Under the privacy curtain (PRIV-03) names
 * mask to "Someone", photos are withheld, and initials derive from the masked
 * literal — the BrowseRow / CallLogScreen convention.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RhythmDaySheet(
    dayLabel: String,
    calls: List<RhythmCall>,
    curtain: Boolean,
    onOpenContact: (contactId: Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OrbitTheme.colors.surface,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = OrbitTheme.spacing.x4,
                    end = OrbitTheme.spacing.x4,
                    bottom = OrbitTheme.spacing.x5,
                ),
        ) {
            Text(
                text = dayLabel,
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            )
            Text(
                text = directionSummary(calls),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x3))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x1),
            ) {
                items(
                    items = calls,
                    key = { it.callEventId },
                    contentType = { "rhythmCall" },
                ) { call ->
                    RhythmCallRow(
                        call = call,
                        curtain = curtain,
                        onClick = { onOpenContact(call.contactId) },
                    )
                }
            }
        }
    }
}

/**
 * "You called 2 · They called 1" — both sides in one line, same weight. Drops
 * the empty half rather than printing a zero, so a one-sided day reads as a
 * fact instead of a shortfall.
 */
internal fun directionSummary(calls: List<RhythmCall>): String {
    val out = calls.count { it.direction == CallDirection.OUTGOING }
    val incoming = calls.size - out
    return buildList {
        if (out > 0) add("You called $out")
        if (incoming > 0) add("They called $incoming")
    }.joinToString(" · ").ifEmpty { "No calls" }
}

/** Human-readable direction, used by both the sheet row and its a11y label. */
internal fun directionWord(direction: CallDirection): String =
    if (direction == CallDirection.OUTGOING) "You called" else "They called"

@Composable
internal fun directionColor(direction: CallDirection): Color =
    if (direction == CallDirection.OUTGOING) {
        OrbitTheme.colors.directionOutgoing
    } else {
        OrbitTheme.colors.directionIncoming
    }

@Composable
private fun RhythmCallRow(
    call: RhythmCall,
    curtain: Boolean,
    onClick: () -> Unit,
) {
    val name = if (curtain) "Someone" else call.contactName
    val rim = directionColor(call.direction)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OrbitTheme.shapes.md)
            .clickable(onClick = onClick)
            .heightIn(min = OrbitTheme.spacing.tapMin)
            .padding(
                horizontal = OrbitTheme.spacing.x2,
                vertical = OrbitTheme.spacing.x2,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Same rim colour as the bar the user just tapped — the sheet is where
        // the strip's two rim colours get their names.
        RowAvatar(
            photoUri = if (curtain) null else call.photoUri,
            name = name,
            rim = rim,
        )
        Spacer(Modifier.width(OrbitTheme.spacing.x3))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(
                    directionWord(call.direction),
                    call.durationLabel,
                    call.timeLabel,
                ).joinToString(" · "),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(OrbitTheme.spacing.x2))
        PhIcon(
            name = if (call.direction == CallDirection.OUTGOING) {
                "phone-outgoing"
            } else {
                "phone-incoming"
            },
            size = 18.dp,
            tint = rim,
        )
    }
}

/** 40dp avatar wearing the call's direction rim. */
@Composable
private fun RowAvatar(photoUri: String?, name: String, rim: Color) {
    val ring = Modifier
        .size(40.dp)
        .border(width = 2.dp, color = rim, shape = CircleShape)
        .padding(2.dp)
        .clip(CircleShape)
    Box(modifier = ring, contentAlignment = Alignment.Center) {
        if (photoUri.isNullOrBlank()) {
            Avatar(name = name, size = 36.dp)
        } else {
            SubcomposeAsyncImage(
                model = photoUri,
                contentDescription = null,
                loading = { Avatar(name = name, size = 36.dp) },
                error = { Avatar(name = name, size = 36.dp) },
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(OrbitTheme.colors.bgSubtle),
            )
        }
    }
}

// ---- Previews ----
//
// ModalBottomSheet needs a real window host, so the preview renders the sheet's
// body directly — the same workaround ListSelectorSheet's preview uses.

@PreviewLightDark
@PreviewFontScale
@Composable
private fun RhythmDaySheetBodyPreview() {
    OrbitTheme {
        Box(modifier = Modifier.background(OrbitTheme.colors.surface)) {
            Column(Modifier.padding(OrbitTheme.spacing.x4)) {
                Text(
                    text = "Wednesday 3 June",
                    style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
                )
                Text(
                    text = directionSummary(previewCalls),
                    style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(OrbitTheme.spacing.x3))
                previewCalls.forEach { call ->
                    RhythmCallRow(call = call, curtain = false, onClick = {})
                }
            }
        }
    }
}

private val previewCalls = listOf(
    RhythmCall(
        callEventId = 1L,
        contactId = 1L,
        contactName = "Kai Mensah",
        photoUri = null,
        durationSeconds = 14 * 60,
        direction = CallDirection.OUTGOING,
        durationLabel = "14 min",
        timeLabel = "4:30pm",
    ),
    RhythmCall(
        callEventId = 2L,
        contactId = 2L,
        contactName = "Mara Ellis",
        photoUri = null,
        durationSeconds = 26 * 60,
        direction = CallDirection.INCOMING,
        durationLabel = "26 min",
        timeLabel = "8:05pm",
    ),
)
