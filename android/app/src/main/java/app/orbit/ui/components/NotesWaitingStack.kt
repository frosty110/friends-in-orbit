package app.orbit.ui.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.orbit.R
import app.orbit.data.entity.CallDirection
import app.orbit.ui.theme.LocalReducedMotion
import app.orbit.ui.theme.OrbitMotion
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.orbitCardShadow
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString
import app.orbit.ui.util.formatDuration

/**
 * One call waiting for a note on Home (NOTE-05), already worded: [meta] is
 * "14 min · 2 hours ago" from the app's duration and time formatters.
 * [name] is the person's display name; the stack says their first name and
 * masks it under the privacy curtain.
 */
@Immutable
data class NoteWaiting(
    val callEventId: Long,
    val contactId: Long,
    val name: String,
    val photoUri: String?,
    val direction: CallDirection,
    val meta: UiText,
)

/**
 * HOME-14: the calls waiting for a note, at the top of Home. Replaced the
 * single "You just called Sam" banner (`PostCallBanner`, NOTE-02) on
 * 2026-10-07, when the owner asked for every unnoted call of the last day,
 * stacked, each closable, and for a cleaner look.
 *
 * - One call is one card: the person's face, "You called Kai" or "Kai called
 *   you", "14 min · 2 hours ago", "Add a note" and "Dismiss".
 * - Two or more lie in a pile: the newest card on top with the edges of one
 *   or two more under it, and "3 calls to write about". The whole pile is
 *   one button, so a tap opens it, accordion style, into one row per call
 *   with the same two buttons, then "Dismiss all"; the line at the top folds
 *   it again.
 *
 * Stateless: the screen owns whether the pile is [open] (rules.md Code 7),
 * so the choice outlives the branch that draws it, and Home owns whether the
 * stack shows at all. Nothing here spends the accent: with lists on the page
 * Home has none to spend (rules.md Design 5), so "Add a note" is Secondary.
 * Opening, closing and a row leaving are a short fade and resize, and
 * instant when the system's animations are off (Design 8).
 *
 * TalkBack: the closed pile is one node that says how many calls and that it
 * is collapsed, and acts as a button; the open pile's top line is the same
 * node, expanded. Each call's buttons say whose call they are ("Dismiss your
 * call with Kai"). Names read the curtain here ([LocalPrivacyCurtain]), so
 * the gallery's curtain pass audits this component in place.
 */
@Composable
fun NotesWaitingStack(
    calls: List<NoteWaiting>,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onAddNote: (NoteWaiting) -> Unit,
    onDismiss: (NoteWaiting) -> Unit,
    onDismissAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (calls.isEmpty()) return
    val reducedMotion = LocalReducedMotion.current
    val shape = when {
        calls.size == 1 -> StackShape.Single
        open -> StackShape.Open
        else -> StackShape.Closed
    }
    AnimatedContent(
        targetState = shape,
        transitionSpec = {
            if (reducedMotion) {
                (EnterTransition.None togetherWith ExitTransition.None)
                    .using(SizeTransform(clip = false) { _, _ -> snap() })
            } else {
                (
                    fadeIn(tween(OrbitMotion.DurBaseMs, easing = OrbitMotion.EaseOut)) togetherWith
                        fadeOut(tween(OrbitMotion.DurFastMs))
                    ).using(
                    SizeTransform(clip = false) { _, _ ->
                        tween(OrbitMotion.DurBaseMs, easing = OrbitMotion.EaseInOut)
                    },
                )
            }
        },
        label = "notesWaitingStack",
        modifier = modifier.fillMaxWidth(),
    ) { target ->
        when (target) {
            StackShape.Single -> calls.firstOrNull()?.let { call ->
                StackCard {
                    CallSummary(call)
                    Spacer(Modifier.height(OrbitTheme.spacing.x3))
                    CallActions(call, onAddNote, onDismiss)
                }
            }
            StackShape.Closed -> ClosedPile(calls, onOpen = { onOpenChange(true) })
            StackShape.Open -> OpenPile(
                calls = calls,
                onFold = { onOpenChange(false) },
                onAddNote = onAddNote,
                onDismiss = onDismiss,
                onDismissAll = onDismissAll,
            )
        }
    }
}

private enum class StackShape { Single, Closed, Open }

/**
 * The surface every shape sits on: the card Home's old banner used.
 * [inside] goes inside the card's clip, so a click's press overlay takes the
 * card's rounded shape while the shadow stays outside it.
 */
@Composable
private fun StackCard(
    modifier: Modifier = Modifier,
    inside: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .orbitCardShadow(shape = OrbitTheme.shapes.lg, isDark = OrbitTheme.colors.isDark)
            .clip(OrbitTheme.shapes.lg)
            .background(OrbitTheme.colors.surface)
            .then(inside)
            .padding(OrbitTheme.spacing.x4),
        content = content,
    )
}

/** The face and the two lines that say which call this is. */
@Composable
private fun CallSummary(call: NoteWaiting) {
    val curtain = LocalPrivacyCurtain.current
    val firstName = firstNameOf(call.name)
    val heading = when {
        curtain && call.direction == CallDirection.OUTGOING ->
            stringResource(R.string.components_notes_waiting_you_called_someone)
        curtain -> stringResource(R.string.components_notes_waiting_someone_called)
        call.direction == CallDirection.OUTGOING ->
            stringResource(R.string.components_notes_waiting_you_called, firstName)
        else -> stringResource(R.string.components_notes_waiting_they_called, firstName)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(
            // Masked initials and no photo under the curtain: a face is as
            // telling as a name (PRIV-03).
            name = if (curtain) stringResource(R.string.components_curtain_someone) else call.name,
            size = 40.dp,
            photoUri = if (curtain) null else call.photoUri,
        )
        Spacer(Modifier.width(OrbitTheme.spacing.x3))
        Column(Modifier.weight(1f)) {
            Text(
                text = heading,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg, fontWeight = FontWeight.Medium),
            )
            Text(
                text = call.meta.asString(),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
            )
        }
    }
}

/**
 * "Add a note" and "Dismiss" for one call. A flow row, so at 200% text the
 * second button wraps under the first instead of squeezing it. TalkBack
 * hears whose call each button is about, because in an open pile several
 * rows carry the same two words.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CallActions(
    call: NoteWaiting,
    onAddNote: (NoteWaiting) -> Unit,
    onDismiss: (NoteWaiting) -> Unit,
) {
    val curtain = LocalPrivacyCurtain.current
    val firstName = firstNameOf(call.name)
    val addLabel = if (curtain) {
        stringResource(R.string.components_notes_waiting_add_note_masked)
    } else {
        stringResource(R.string.components_notes_waiting_add_note_named, firstName)
    }
    val dismissLabel = if (curtain) {
        stringResource(R.string.components_notes_waiting_dismiss_masked)
    } else {
        stringResource(R.string.components_notes_waiting_dismiss_named, firstName)
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
        verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
    ) {
        OrbitButton(
            text = stringResource(R.string.components_notes_waiting_add_note),
            onClick = { onAddNote(call) },
            // Secondary: Home spends no accent while it shows lists
            // (rules.md Design 5; features/home/README.md, "The one accent").
            variant = OrbitButtonVariant.Secondary,
            leadingIcon = "note-pencil",
            modifier = Modifier.semantics { contentDescription = addLabel },
        )
        OrbitButton(
            text = stringResource(R.string.components_notes_waiting_dismiss),
            onClick = { onDismiss(call) },
            variant = OrbitButtonVariant.Ghost,
            modifier = Modifier.semantics { contentDescription = dismissLabel },
        )
    }
}

/**
 * Two or more calls, closed: the newest on top, the edges of the next one or
 * two showing under it, and the count. One button for TalkBack: the count and
 * "Collapsed", the card's own words hidden, because a tap opens the pile
 * where each call is read in full.
 */
@Composable
private fun ClosedPile(calls: List<NoteWaiting>, onOpen: () -> Unit) {
    val count = calls.size
    val countLine = pluralStringResource(R.plurals.components_notes_waiting_count, count, count)
    val collapsed = stringResource(R.string.components_notes_waiting_collapsed)
    val showEach = stringResource(R.string.components_notes_waiting_show_each)
    Column(Modifier.fillMaxWidth()) {
        // The top card is the button; the edges under it are drawing only.
        // It is drawn over them (zIndex), so its shadow falls on them the way
        // a card's falls on the one beneath.
        StackCard(
            modifier = Modifier.zIndex(1f),
            inside = Modifier
                .clickable(onClickLabel = showEach, role = Role.Button, onClick = onOpen)
                .semantics(mergeDescendants = true) {
                    contentDescription = countLine
                    stateDescription = collapsed
                },
        ) {
            // The card's own words stay out of the button's name: TalkBack
            // hears the count and "Collapsed", and each call in full once open.
            Column(Modifier.clearAndSetSemantics { }) {
                CallSummary(calls.first())
                Spacer(Modifier.height(OrbitTheme.spacing.x3))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = countLine,
                        style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                        modifier = Modifier.weight(1f),
                    )
                    PhIcon(name = "caret-down", size = 18.dp, tint = OrbitTheme.colors.fgMuted)
                }
            }
        }
        PileEdge(inset = OrbitTheme.spacing.x3, z = 0.5f)
        if (count > 2) PileEdge(inset = OrbitTheme.spacing.x6, z = 0f)
    }
}

/**
 * A card lying under the one above it: narrower, a step lower, and tucked
 * under it, so only its rounded bottom shows. It is a whole card shape whose
 * top half the card above covers ([tuckedUnder]); a sliver with square top
 * corners read as notches cut into the top card rather than a card beneath.
 * The hairline outline separates it where no shadow is drawn.
 */
@Composable
private fun PileEdge(inset: Dp, z: Float) {
    val shape = OrbitTheme.shapes.lg
    val tuck = OrbitTheme.spacing.x4 // the card's corner radius: the rounded top stays hidden
    Box(
        Modifier
            .zIndex(z)
            .tuckedUnder(tuck)
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(OrbitTheme.spacing.x2 + tuck)
            .orbitCardShadow(shape = shape, isDark = OrbitTheme.colors.isDark)
            .border(width = 1.dp, color = OrbitTheme.colors.line, shape = shape)
            .clip(shape)
            .background(OrbitTheme.colors.surface),
    )
}

/** Lays this out [by] higher than its slot, and takes [by] less room, so it slides under what is above it. */
private fun Modifier.tuckedUnder(by: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val tuckPx = by.roundToPx().coerceAtMost(placeable.height)
    layout(placeable.width, placeable.height - tuckPx) { placeable.place(0, -tuckPx) }
}

/**
 * The pile, open: the count line (the button that folds it again), one row
 * per call with its own buttons, then "Dismiss all". A row that is dismissed
 * leaves with a short resize rather than a jump.
 */
@Composable
private fun OpenPile(
    calls: List<NoteWaiting>,
    onFold: () -> Unit,
    onAddNote: (NoteWaiting) -> Unit,
    onDismiss: (NoteWaiting) -> Unit,
    onDismissAll: () -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    val count = calls.size
    val countLine = pluralStringResource(R.plurals.components_notes_waiting_count, count, count)
    val expanded = stringResource(R.string.components_notes_waiting_expanded)
    val showLess = stringResource(R.string.components_notes_waiting_show_less)
    val caretTurn by animateFloatAsState(
        targetValue = 180f,
        animationSpec = if (reducedMotion) snap() else tween(OrbitMotion.DurBaseMs, easing = OrbitMotion.EaseInOut),
        label = "notesWaitingCaret",
    )
    val resize = if (reducedMotion) {
        Modifier
    } else {
        Modifier.animateContentSize(tween(OrbitMotion.DurBaseMs, easing = OrbitMotion.EaseInOut))
    }
    StackCard(resize) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = OrbitTheme.spacing.tapMin)
                .clip(OrbitTheme.shapes.md)
                .clickable(onClickLabel = showLess, role = Role.Button, onClick = onFold)
                .semantics(mergeDescendants = true) {
                    contentDescription = countLine
                    stateDescription = expanded
                },
        ) {
            Text(
                text = countLine,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg, fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f),
            )
            PhIcon(
                name = "caret-down",
                size = 18.dp,
                tint = OrbitTheme.colors.fgMuted,
                modifier = Modifier.rotate(caretTurn),
            )
        }
        calls.forEach { call ->
            key(call.callEventId) {
                HorizontalDivider(
                    color = OrbitTheme.colors.lineSoft,
                    modifier = Modifier.padding(vertical = OrbitTheme.spacing.x3),
                )
                CallSummary(call)
                Spacer(Modifier.height(OrbitTheme.spacing.x3))
                CallActions(call, onAddNote, onDismiss)
            }
        }
        HorizontalDivider(
            color = OrbitTheme.colors.lineSoft,
            modifier = Modifier.padding(vertical = OrbitTheme.spacing.x3),
        )
        OrbitButton(
            text = stringResource(R.string.components_notes_waiting_dismiss_all),
            onClick = onDismissAll,
            variant = OrbitButtonVariant.Ghost,
        )
    }
}

/** The name a sentence uses: the first word, as Card view's "Called Kai" does. */
private fun firstNameOf(name: String): String = name.trim().substringBefore(' ').ifBlank { name }

// ── Previews (THEME-05: one per state, light and dark, 200%, the curtain) ──

private fun previewCall(id: Long, name: String, direction: CallDirection, minutes: Int, ago: Int): NoteWaiting =
    NoteWaiting(
        callEventId = id,
        contactId = id,
        name = name,
        photoUri = null,
        direction = direction,
        meta = UiText.res(
            R.string.components_notes_waiting_meta,
            formatDuration(minutes * 60),
            UiText.plural(R.plurals.time_ago_hours, ago, ago),
        ),
    )

private val previewCalls = listOf(
    previewCall(1L, "Kai Mensah", CallDirection.OUTGOING, minutes = 14, ago = 2),
    previewCall(2L, "Mara Ellis", CallDirection.INCOMING, minutes = 26, ago = 5),
    previewCall(3L, "Sam Okafor", CallDirection.OUTGOING, minutes = 41, ago = 20),
)

@Composable
private fun StackPreviewHost(calls: List<NoteWaiting>, open: Boolean, curtain: Boolean = false) {
    OrbitTheme {
        // Only ever turns the curtain on: the gallery's curtain pass provides
        // it from outside, and a host that provided `false` would hide every
        // leak from that audit.
        CompositionLocalProvider(LocalPrivacyCurtain provides (curtain || LocalPrivacyCurtain.current)) {
            // The pile scrolls with Home's list in the app, so it scrolls here
            // too (development-cycle.md, "The preview gallery"): an open pile
            // at 200% is taller than the window.
            Box(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(OrbitTheme.spacing.x4),
            ) {
                NotesWaitingStack(
                    calls = calls,
                    open = open,
                    onOpenChange = {},
                    onAddNote = {},
                    onDismiss = {},
                    onDismissAll = {},
                )
            }
        }
    }
}

@PreviewLightDark
@Preview(name = "200%", fontScale = 2f)
@Composable
private fun NotesWaitingStackOnePreview() {
    StackPreviewHost(calls = previewCalls.take(1), open = false)
}

@PreviewLightDark
@Preview(name = "200%", fontScale = 2f)
@Composable
private fun NotesWaitingStackPilePreview() {
    StackPreviewHost(calls = previewCalls, open = false)
}

@PreviewLightDark
@Composable
private fun NotesWaitingStackPileOfTwoPreview() {
    StackPreviewHost(calls = previewCalls.take(2), open = false)
}

@PreviewLightDark
@Preview(name = "200%", fontScale = 2f)
@Composable
private fun NotesWaitingStackOpenPreview() {
    StackPreviewHost(calls = previewCalls, open = true)
}

@Preview(name = "curtain")
@Preview(name = "curtain, dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NotesWaitingStackCurtainPreview() {
    StackPreviewHost(calls = previewCalls, open = true, curtain = true)
}
