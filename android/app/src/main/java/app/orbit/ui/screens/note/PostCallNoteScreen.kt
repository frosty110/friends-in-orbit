package app.orbit.ui.screens.note

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.orbit.R
import app.orbit.data.entity.CallDirection
import app.orbit.ui.components.CurtainMask
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.components.OrbitSnackbarHost
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString
import app.orbit.ui.util.formatDuration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * NOTE-04: the page for writing about a call. The owner asked for "a little
 * feedback, survey, or notes ... how I felt about it or what I want to
 * remember", with a timer from zero that stays in view however long the entry
 * gets (vision/flows/owner-review-2026-10-07.md, decisions 3 and 12).
 *
 * Reached from Home's calls waiting for a note (HOME-14), from the
 * notification after a call (NOTIF-16) and from Card view's "Called Kai"
 * snackbar; Card view will also open it by itself (CARD-11), through
 * `Routes.postCallNote`. [onLeave] returns to wherever it was opened from.
 *
 * This wrapper resolves the ViewModel, ticks the timer and turns the saved
 * state into leaving; everything on screen is [PostCallNoteContent].
 */
@Composable
fun PostCallNoteScreen(
    onLeave: () -> Unit,
    vm: PostCallNoteViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val elapsed = rememberElapsedSeconds(vm.startedAt)
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(vm) {
        vm.events.collect { message -> snackbarHostState.showSnackbar(message.asString(context)) }
    }
    // The write landed: leave. State, not an event, so a rotation during the
    // write cannot leave the page open over a note that is already saved.
    val saved = (state as? PostCallNoteUiState.Ready)?.saved == true
    val currentOnLeave by rememberUpdatedState(onLeave)
    LaunchedEffect(saved) { if (saved) currentOnLeave() }

    PostCallNoteContent(
        state = state,
        elapsedSeconds = { elapsed.value },
        initialDraft = vm.initialDraft,
        onDraftChange = vm::onDraftChange,
        onSave = vm::save,
        onLeave = onLeave,
        onDiscard = vm::onDiscard,
        onRetry = vm::onRetry,
        snackbarHostState = snackbarHostState,
    )
}

/**
 * Whole seconds since [startedAt], ticking on the second while the page is
 * on screen. The start comes from the ViewModel's SavedStateHandle, so the
 * count survives rotation and process death; it pauses only its redraws
 * while the app is in the background, never the count.
 */
@Composable
private fun rememberElapsedSeconds(startedAt: Instant): State<Long> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val startMs = startedAt.toEpochMilli()
    val nowMs = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startMs, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowMs.longValue = System.currentTimeMillis()
                delay(MS_PER_SECOND - (nowMs.longValue - startMs).mod(MS_PER_SECOND))
            }
        }
    }
    return remember(startMs) {
        derivedStateOf { ((nowMs.longValue - startMs) / MS_PER_SECOND).coerceAtLeast(0L) }
    }
}

private const val MS_PER_SECOND = 1_000L

/**
 * The stateless page (THEME-04). The writing field owns its text here
 * ([rememberSaveable], rules.md Code 7) and hands every change one way to
 * [onDraftChange]; nothing writes it back. Whether "Not now" or Back asks
 * "Discard this note?" is decided from that text: with words written it
 * asks, with nothing written it just leaves.
 *
 * - App bar: "Not now", the title ("Your call with Kai"; "Your call" under
 *   the curtain) and the timer, in the bar so it never scrolls away.
 * - The call in one line ("You called Kai · 14 min · Today at 4:30pm").
 * - One large writing field filling the page, which scrolls inside itself
 *   and keeps the cursor in view as the entry grows, with "How did it go?
 *   What do you want to remember?" as its placeholder and TalkBack label.
 * - "Save note", the page's one accent element (rules.md Design 5),
 *   disabled while the field is blank, and above the keyboard: OrbitScreen
 *   pads by the keyboard's inset, so the page shrinks to the space left.
 *
 * [elapsedSeconds] is read only by the timer, so the tick redraws the timer
 * and nothing else.
 */
@Composable
internal fun PostCallNoteContent(
    state: PostCallNoteUiState,
    elapsedSeconds: () -> Long,
    initialDraft: String,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onLeave: () -> Unit,
    onDiscard: () -> Unit,
    onRetry: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    autoFocus: Boolean = true,
    askingDiscardAtStart: Boolean = false,
) {
    val curtain = LocalPrivacyCurtain.current
    var draft by rememberSaveable { mutableStateOf(initialDraft) }
    var askingDiscard by rememberSaveable { mutableStateOf(askingDiscardAtStart) }
    val ready = state as? PostCallNoteUiState.Ready
    val hasWords = ready != null && !ready.saved && draft.isNotBlank()
    val requestClose: () -> Unit = { if (hasWords) askingDiscard = true else onLeave() }
    BackHandler(enabled = hasWords) { askingDiscard = true }

    val messageTitle = when (state) {
        PostCallNoteUiState.NotFound -> stringResource(R.string.contact_not_found_title)
        PostCallNoteUiState.Error -> stringResource(R.string.contact_error_title)
        else -> null
    }
    val title = when {
        messageTitle != null -> ""
        ready != null && !curtain -> stringResource(R.string.note_title_named, ready.firstName)
        else -> stringResource(R.string.note_title)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // With no person to name, the pane takes the message's heading so
            // TalkBack still announces the page (Contact detail's precedent).
            .semantics { if (messageTitle != null) paneTitle = messageTitle },
    ) {
        OrbitScreen {
            OrbitAppBar(
                title = title,
                leading = {
                    if (messageTitle == null) {
                        NotNowAction(onClick = requestClose)
                    } else {
                        OrbitIconButton(
                            icon = "arrow-left",
                            onClick = onLeave,
                            contentDescription = stringResource(R.string.components_action_back),
                        )
                    }
                },
                trailing = if (messageTitle == null) {
                    { ElapsedTimer(elapsedSeconds) }
                } else {
                    null
                },
            )
            when (state) {
                // Quiet chrome while the person loads: the bar and its timer.
                PostCallNoteUiState.Loading -> Spacer(Modifier.weight(1f))
                // The only action here, so it takes the accent (Contact
                // detail's NotFound and Error, the precedent for both).
                PostCallNoteUiState.NotFound -> OrbitScreenMessage(
                    icon = "user",
                    title = stringResource(R.string.contact_not_found_title),
                    body = stringResource(R.string.contact_not_found_body),
                    actionLabel = stringResource(R.string.components_action_go_back),
                    onAction = onLeave,
                    actionVariant = OrbitButtonVariant.Primary,
                )
                PostCallNoteUiState.Error -> OrbitScreenMessage(
                    icon = "warning-circle",
                    title = stringResource(R.string.contact_error_title),
                    body = stringResource(R.string.components_error_body),
                    actionLabel = stringResource(R.string.components_error_retry),
                    onAction = onRetry,
                    actionVariant = OrbitButtonVariant.Primary,
                )
                is PostCallNoteUiState.Ready -> {
                    val focusRequester = remember { FocusRequester() }
                    // The page exists to write in: the cursor is in the field
                    // and the keyboard up as it opens.
                    LaunchedEffect(Unit) { if (autoFocus) focusRequester.requestFocus() }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = OrbitTheme.spacing.x5),
                    ) {
                        state.call?.let { call ->
                            CallLine(firstName = state.firstName, call = call, curtain = curtain)
                        }
                        WritingField(
                            draft = draft,
                            onDraftChange = {
                                draft = it
                                onDraftChange(it)
                            },
                            curtain = curtain,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                        )
                    }
                    OrbitButton(
                        text = stringResource(R.string.note_save),
                        onClick = onSave,
                        enabled = draft.isNotBlank() && !state.saving && !state.saved,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = OrbitTheme.spacing.x5, vertical = OrbitTheme.spacing.x3),
                    )
                }
            }
        }
        OrbitSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding(),
        )
    }

    if (askingDiscard) {
        DiscardDialog(
            onKeepWriting = { askingDiscard = false },
            onDiscard = {
                askingDiscard = false
                onDiscard()
                onLeave()
            },
        )
    }
}

/**
 * "Not now": leaves without a note (asking first when words are written).
 * A word, not an X, because it says what leaving means; quiet ink, because
 * "Save note" is the page's one accent. 48dp, like every control.
 */
@Composable
private fun NotNowAction(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minWidth = OrbitTheme.spacing.tapMin, minHeight = OrbitTheme.spacing.tapMin)
            .clip(OrbitTheme.shapes.md)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = OrbitTheme.spacing.x2),
    ) {
        Text(
            text = stringResource(R.string.note_not_now),
            style = OrbitTheme.type.button.copy(color = OrbitTheme.colors.fgMuted),
        )
    }
}

/**
 * The count-up timer: "0:00", "2:14", then "1:02:07" past an hour, in
 * tabular figures so the digits do not shuffle as they tick. TalkBack does
 * not hear it tick: its words change once a minute ("Writing for 2
 * minutes") and it is no live region, so it is read only when the user
 * moves to it. It is a nudge to keep the entry short, not a limit.
 */
@Composable
private fun ElapsedTimer(elapsedSeconds: () -> Long) {
    val seconds = elapsedSeconds()
    val minutes = (seconds / 60L).toInt()
    val spoken = if (minutes < 1) {
        stringResource(R.string.note_timer_under_a_minute)
    } else {
        pluralStringResource(R.plurals.note_timer_minutes, minutes, minutes)
    }
    // The words replace the digits for TalkBack: the box clears what the Text
    // under it would expose, so the ticking "2:14" is never in the tree.
    Box(
        Modifier
            .padding(horizontal = OrbitTheme.spacing.x3)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Text(
            text = formatElapsed(seconds),
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted, fontFeatureSettings = "tnum"),
        )
    }
}

/** m:ss, then h:mm:ss from an hour on. Digits, not words: the spoken form is [ElapsedTimer]'s. */
internal fun formatElapsed(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0L)
    val hours = s / 3_600L
    val minutes = (s % 3_600L) / 60L
    val secs = s % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, secs)
    }
}

/** "You called Kai · 14 min · Today at 4:30pm"; under the curtain "You called · 14 min · ...". */
@Composable
private fun CallLine(firstName: String, call: NoteCall, curtain: Boolean) {
    val duration = call.durationLabel.asString()
    val whenLabel = call.whenLabel.asString()
    val line = when {
        curtain && call.direction == CallDirection.OUTGOING ->
            stringResource(R.string.note_call_you_called_masked, duration, whenLabel)
        curtain -> stringResource(R.string.note_call_they_called_masked, duration, whenLabel)
        call.direction == CallDirection.OUTGOING ->
            stringResource(R.string.note_call_you_called, firstName, duration, whenLabel)
        else -> stringResource(R.string.note_call_they_called, firstName, duration, whenLabel)
    }
    Text(
        text = line,
        style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
        modifier = Modifier.padding(bottom = OrbitTheme.spacing.x4),
    )
}

/**
 * The writing field. It fills the page and scrolls inside itself, so the
 * cursor stays in view however long the entry gets while the bar, with the
 * timer, stays put. The placeholder sits in its decoration, which makes it
 * the field's TalkBack label while it is empty (OrbitSearchField's
 * precedent). Under the curtain the words are drawn as "Note hidden", the
 * way Contact detail hides a note, with [CurtainMask] over the buffer so what
 * is typed is never replaced by the mask.
 */
@Composable
private fun WritingField(
    draft: String,
    onDraftChange: (String) -> Unit,
    curtain: Boolean,
    modifier: Modifier = Modifier,
) {
    val mask = stringResource(R.string.note_hidden)
    BasicTextField(
        value = draft,
        onValueChange = onDraftChange,
        textStyle = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
        cursorBrush = SolidColor(OrbitTheme.colors.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        visualTransformation = if (curtain && draft.isNotEmpty()) CurtainMask(mask) else VisualTransformation.None,
        modifier = modifier,
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize()) {
                if (draft.isEmpty()) {
                    Text(
                        text = stringResource(R.string.note_prompt),
                        style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgSubtle),
                    )
                }
                inner()
            }
        },
    )
}

/** "Discard this note?": "Keep writing" or "Discard". The DeleteListDialog shell. */
@Composable
private fun DiscardDialog(onKeepWriting: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepWriting,
        containerColor = OrbitTheme.colors.surface,
        title = {
            Text(
                text = stringResource(R.string.note_discard_title),
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            )
        },
        confirmButton = {
            OrbitButton(
                text = stringResource(R.string.note_discard_confirm),
                onClick = onDiscard,
                variant = OrbitButtonVariant.Destructive,
            )
        },
        dismissButton = {
            OrbitButton(
                text = stringResource(R.string.note_discard_keep),
                onClick = onKeepWriting,
                variant = OrbitButtonVariant.Ghost,
            )
        },
    )
}

// ── Previews (THEME-05: one per state; light, dark, 200%, the curtain) ──

private val previewCall = NoteCall(
    direction = CallDirection.OUTGOING,
    durationLabel = formatDuration(14 * 60),
    whenLabel = UiText.res(R.string.note_call_when, UiText.res(R.string.time_day_today), "4:30pm"),
)

private val previewReady = PostCallNoteUiState.Ready(contactId = 1L, firstName = "Kai", call = previewCall)

private const val PREVIEW_ENTRY =
    "Kai sounded tired but happy. The new job starts in March and he is nervous about the commute. " +
        "Ask about the flat viewing next time, and send him the name of that bakery."

@Composable
private fun NotePreviewHost(
    state: PostCallNoteUiState,
    draft: String = "",
    seconds: Long = 134L,
    curtain: Boolean = false,
    askingDiscard: Boolean = false,
) {
    OrbitTheme {
        // Only ever turns the curtain on, so the gallery's curtain pass, which
        // provides it from outside, still sees through this host.
        CompositionLocalProvider(LocalPrivacyCurtain provides (curtain || LocalPrivacyCurtain.current)) {
            PostCallNoteContent(
                state = state,
                elapsedSeconds = { seconds },
                initialDraft = draft,
                onDraftChange = {},
                onSave = {},
                onLeave = {},
                onDiscard = {},
                onRetry = {},
                autoFocus = false,
                askingDiscardAtStart = askingDiscard,
            )
        }
    }
}

@PreviewLightDark
@Preview(name = "200%", fontScale = 2f)
@Composable
private fun PostCallNoteContentEmptyPreview() {
    NotePreviewHost(previewReady, seconds = 8L)
}

@PreviewLightDark
@Preview(name = "200%", fontScale = 2f)
@Composable
private fun PostCallNoteContentWritingPreview() {
    NotePreviewHost(previewReady, draft = PREVIEW_ENTRY, seconds = 3_725L)
}

@PreviewLightDark
@Composable
private fun PostCallNoteContentIncomingPreview() {
    NotePreviewHost(
        previewReady.copy(firstName = "Bartholomew", call = previewCall.copy(direction = CallDirection.INCOMING)),
        draft = PREVIEW_ENTRY,
    )
}

@Preview(name = "curtain")
@Preview(name = "curtain, dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PostCallNoteContentCurtainPreview() {
    NotePreviewHost(previewReady, draft = PREVIEW_ENTRY, curtain = true)
}

@PreviewLightDark
@Composable
private fun PostCallNoteContentDiscardPreview() {
    NotePreviewHost(previewReady, draft = PREVIEW_ENTRY, askingDiscard = true)
}

@PreviewLightDark
@Composable
private fun PostCallNoteContentLoadingPreview() {
    NotePreviewHost(PostCallNoteUiState.Loading, seconds = 0L)
}

@PreviewLightDark
@Composable
private fun PostCallNoteContentNotFoundPreview() {
    NotePreviewHost(PostCallNoteUiState.NotFound)
}

@PreviewLightDark
@Composable
private fun PostCallNoteContentErrorPreview() {
    NotePreviewHost(PostCallNoteUiState.Error)
}
