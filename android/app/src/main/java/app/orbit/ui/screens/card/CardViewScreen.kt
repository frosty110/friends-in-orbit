package app.orbit.ui.screens.card

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.data.ChipTone
import app.orbit.data.Contact
import app.orbit.data.NoteRow
import app.orbit.ui.components.Avatar
import app.orbit.ui.components.ListContextChip
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitChip
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.PhIcon
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.theme.OrbitHeatRamp
import app.orbit.ui.theme.OrbitMotion
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.orbitCardShadow
import app.orbit.ui.theme.orbitHeroShadow
import app.orbit.ui.util.dialPhoneNumber
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val USUALLY_TOOLTIP = "Based on when you usually answer or call this contact."

// Card View — drag to defer/surface, tap to call.
// 2026-06-09 card-loop revision: hydrated stats + heat, swipe undo snackbars,
// call-log-denied notice, actionable empty states, and crossfaded card
// advancement via [CardSwipeFrame]. A call placed from here advances the deck
// silently once the call log is synced (no "did you talk?" confirmation).

@Composable
fun CardViewScreen(
    listId: String,
    onBack: () -> Unit,
    onCall: (contactId: String) -> Unit,
    onBrowse: (listId: String) -> Unit,
    onEditList: (listId: String) -> Unit = {},
    onAddContacts: (listId: String) -> Unit = {},
    onOpenContact: (contactId: String) -> Unit = onCall,   // NOTE-03 — RecentNotesSummary tap target
    onOpenSettings: () -> Unit = {},
    vm: CardViewViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Permission visibility + post-dial resync. Both refresh on every ON_RESUME:
    // returning from the dialer triggers an immediate call-log resync (so a
    // just-placed call advances the deck on its own), and returning from Settings
    // clears the denied notice once the user grants READ_CALL_LOG.
    var callLogDenied by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        vm.onReturnedFromDial()
        callLogDenied = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG,
        ) != PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }

    CardViewContent(
        state = state,
        listId = listId,
        callLogDenied = callLogDenied,
        snackbarEvents = vm.snackbarEvents,
        onBack = onBack,
        onBrowse = onBrowse,
        onEditList = onEditList,
        onAddContacts = onAddContacts,
        onTapToCall = { contactId, phone ->
            // Tap-to-call dials only — navigation to contact detail is
            // explicit via "View details". vm.onCall records that a dial
            // happened so the screen triggers an immediate call-log resync on
            // return and the deck advances on its own.
            context.dialPhoneNumber(phone)
            vm.onCall(contactId)
        },
        onSwipeLeft = vm::onSwipeLeft,
        onSwipeRight = vm::onSwipeRight,
        onUndo = vm::onUndo,
        onOpenSettings = onOpenSettings,
        onOpenContact = { contactId -> onOpenContact("c-$contactId") },
    )
}

/**
 * List actions overflow for the Card view. The hamburger anchors a
 * [DropdownMenu] so the user chooses the action rather than being routed into one.
 */
@Composable
private fun ListActionsMenu(
    onBrowse: () -> Unit,
    onEditList: () -> Unit,
    onAddContacts: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OrbitIconButton(
            icon = "list",
            onClick = { expanded = true },
            contentDescription = "List actions",
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Browse people") },
                leadingIcon = { PhIcon(name = "list-bullets", size = 18.dp, tint = OrbitTheme.colors.fg) },
                onClick = { expanded = false; onBrowse() },
            )
            DropdownMenuItem(
                text = { Text("Add contacts") },
                leadingIcon = { PhIcon(name = "plus", size = 18.dp, tint = OrbitTheme.colors.fg) },
                onClick = { expanded = false; onAddContacts() },
            )
            DropdownMenuItem(
                text = { Text("Edit list") },
                leadingIcon = { PhIcon(name = "pencil-simple", size = 18.dp, tint = OrbitTheme.colors.fg) },
                onClick = { expanded = false; onEditList() },
            )
        }
    }
}

@Composable
private fun CardViewContent(
    state: CardViewUiState,
    listId: String,
    callLogDenied: Boolean,
    snackbarEvents: SharedFlow<SnackbarEvent>,
    onBack: () -> Unit,
    onBrowse: (listId: String) -> Unit,
    onEditList: (listId: String) -> Unit,
    onAddContacts: (listId: String) -> Unit,
    onTapToCall: (contactId: Long, phone: String) -> Unit,
    onSwipeLeft: (contactId: Long) -> Unit,
    onSwipeRight: (contactId: Long) -> Unit,
    onUndo: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenContact: (contactId: Long) -> Unit,
) {
    val curtain = LocalPrivacyCurtain.current
    val appBarTitle = when (state) {
        is CardViewUiState.Ready -> if (curtain) "Contact" else state.listContext
        else -> ""
    }

    // Swipe-commit / mark-called snackbars; "Undo" replays the UndoStack
    // inverse (mirrors SettingsIgnoredScreen's collector).
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        snackbarEvents.collect { event ->
            val r = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = event.actionLabel,
                duration = SnackbarDuration.Short,
                withDismissAction = false,
            )
            if (r == SnackbarResult.ActionPerformed) onUndo()
        }
    }

    OrbitScreen {
        OrbitAppBar(
            title = appBarTitle,
            leading = { OrbitIconButton("arrow-left", onBack, contentDescription = "Back") },
            trailing = {
                ListActionsMenu(
                    onBrowse = { onBrowse(listId) },
                    onEditList = { onEditList(listId) },
                    onAddContacts = { onAddContacts(listId) },
                )
            },
        )

        if (callLogDenied) {
            CallLogDeniedNotice(onOpenSettings = onOpenSettings)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when (state) {
                // F-8 — Loading is the pre-emission placeholder. Rendering
                // nothing avoids a flash of empty copy between screen open and
                // the CardFeed's first emission.
                CardViewUiState.Loading -> Box(modifier = Modifier.fillMaxSize())
                CardViewUiState.EmptyNoMembers -> NoMembersShell(
                    onAddContacts = { onAddContacts(listId) },
                    onGoHome = onBack,
                )
                is CardViewUiState.EmptyNothingEligible -> NothingEligibleShell(
                    state = state,
                    onBrowse = { onBrowse(listId) },
                    onGoHome = onBack,
                )
                is CardViewUiState.Error -> ErrorShell(state.cause, onGoHome = onBack)
                is CardViewUiState.Ready -> ReadyCard(
                    state = state,
                    onTapToCall = onTapToCall,
                    onSwipeLeft = onSwipeLeft,
                    onSwipeRight = onSwipeRight,
                    onOpenContact = onOpenContact,
                )
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(OrbitTheme.spacing.x4),
            )
        }
    }
}

/**
 * Quiet persistent notice for the READ_CALL_LOG-denied state — without the
 * permission, calls aren't detected and cards don't advance on their own.
 * Inline (not a dialog), dismiss-free: the honest state deserves to stay
 * visible until it's fixed.
 */
@Composable
private fun CallLogDeniedNotice(onOpenSettings: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x1)
            .clip(OrbitTheme.shapes.md)
            .background(OrbitTheme.colors.bgSubtle)
            .padding(start = OrbitTheme.spacing.x3),
    ) {
        Text(
            text = "Orbit can't see your calls — cards won't move on on their own",
            style = OrbitTheme.type.meta,
            color = OrbitTheme.colors.fgMuted,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = OrbitTheme.spacing.x2),
        )
        InlineTextAction(text = "Open settings", onClick = onOpenSettings)
    }
}

/**
 * 48dp-min text affordance for inline strips (denied notice, post-dial
 * prompt) — keeps tap targets honest without a full button fill.
 */
@Composable
private fun InlineTextAction(
    text: String,
    onClick: () -> Unit,
    color: Color = OrbitTheme.colors.fg,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(
                minWidth = OrbitTheme.spacing.tapMin,
                minHeight = OrbitTheme.spacing.tapMin,
            )
            .clip(OrbitTheme.shapes.md)
            .clickable(onClick = onClick)
            .padding(horizontal = OrbitTheme.spacing.x3),
    ) {
        Text(text = text, style = OrbitTheme.type.button, color = color)
    }
}

@PreviewLightDark
@Composable
private fun InlineTextActionPreview() {
    OrbitTheme {
        InlineTextAction(text = "Mark it", onClick = {})
    }
}

@PreviewLightDark
@Composable
private fun CallLogDeniedNoticePreview() {
    OrbitTheme {
        CallLogDeniedNotice(onOpenSettings = {})
    }
}

/**
 * Tide-marker empty state #1 — the list has zero non-archived non-ignored
 * members. 2026-06-09: the primary action is now the fix ("Add contacts"),
 * not an exit. Sentence case, no exclamation marks — voice rules.
 */
@Composable
private fun NoMembersShell(onAddContacts: () -> Unit, onGoHome: () -> Unit) {
    EmptyShell(
        heading = "No one is in this list yet.",
        body = "Add a few people to start surfacing names.",
        primaryText = "Add contacts",
        onPrimary = onAddContacts,
        secondaryText = "Go home",
        onSecondary = onGoHome,
    )
}

/**
 * Tide-marker empty state #2 — the list has visible members but none
 * surfaces right now. 2026-06-09: the old "paused or out of reach" line was
 * false for lists whose members simply aren't due; the copy now leads with
 * the soonest upcoming member when the feed can see one, and offers Browse
 * as a way in rather than stranding the user.
 */
@Composable
private fun NothingEligibleShell(
    state: CardViewUiState.EmptyNothingEligible,
    onBrowse: () -> Unit,
    onGoHome: () -> Unit,
) {
    val curtain = LocalPrivacyCurtain.current
    val body = if (state.upNextName != null && state.upNextLabel != null) {
        val who = if (curtain) "Someone" else state.upNextName
        "$who comes up ${state.upNextLabel}."
    } else {
        "No one needs a call right now."
    }
    EmptyShell(
        heading = "No one is up next on this list.",
        body = body,
        primaryText = "Browse this list",
        onPrimary = onBrowse,
        secondaryText = "Go home",
        onSecondary = onGoHome,
    )
}

@Composable
private fun EmptyShell(
    heading: String,
    body: String,
    primaryText: String,
    onPrimary: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(OrbitTheme.spacing.x8),
    ) {
        Text(
            text = heading,
            style = OrbitTheme.type.h2,
            color = OrbitTheme.colors.fg,
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x3))
        Text(
            text = body,
            style = OrbitTheme.type.body,
            color = OrbitTheme.colors.fgMuted,
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x6))
        OrbitButton(text = primaryText, onClick = onPrimary)
        if (secondaryText != null && onSecondary != null) {
            Spacer(Modifier.height(OrbitTheme.spacing.x3))
            OrbitButton(
                text = secondaryText,
                onClick = onSecondary,
                variant = OrbitButtonVariant.Ghost,
            )
        }
    }
}

@Composable
private fun ErrorShell(cause: String, onGoHome: () -> Unit) {
    EmptyShell(
        heading = "Something's off here.",
        body = "Pull back and try this list again in a moment.",
        primaryText = "Go home",
        onPrimary = onGoHome,
    )
    // Keep `cause` referenced so the parameter isn't elided; surface only
    // in logs once Timber lands.
    @Suppress("UNUSED_EXPRESSION") cause
}

@Composable
private fun ReadyCard(
    state: CardViewUiState.Ready,
    onTapToCall: (contactId: Long, phone: String) -> Unit,
    onSwipeLeft: (contactId: Long) -> Unit,
    onSwipeRight: (contactId: Long) -> Unit,
    onOpenContact: (contactId: Long) -> Unit,
) {
    val contactId = state.contactId
    val contact = state.contact
    val frameState = remember { CardSwipeFrameState() }

    Column(modifier = Modifier.fillMaxSize()) {
        CardSwipeFrame(
            contactKey = contactId,
            emissionKey = state,
            frameState = frameState,
            onSwipeLeft = { onSwipeLeft(contactId) },
            onSwipeRight = { onSwipeRight(contactId) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x3),
            ghostOverlay = { offsetFraction -> GhostHints(offsetFraction) },
        ) {
            // Crossfade keyed on contactId — the outgoing face fades while the
            // incoming face fades in, so card advancement reads as one quiet
            // motion instead of a teleporting snap-back (2026-06-09 fix).
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    fadeIn(tween(OrbitMotion.DurBaseMs, easing = OrbitMotion.EaseOut)) togetherWith
                        fadeOut(tween(OrbitMotion.DurBaseMs, easing = OrbitMotion.EaseOut))
                },
                contentKey = { it.contactId },
                label = "card face",
                modifier = Modifier.fillMaxSize(),
            ) { face ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .orbitHeroShadow(OrbitTheme.shapes.xl, OrbitTheme.colors.isDark)
                        .clip(OrbitTheme.shapes.xl)
                        .background(OrbitTheme.colors.surface)
                        .clickable { onTapToCall(face.contactId, face.contact.phone) },
                ) {
                    ContactCardFace(
                        contact = face.contact,
                        listContext = face.listContext,
                        nowHour = face.nowHour,
                        isAheadOfToday = face.isAheadOfToday,
                        whyNowLine = face.whyNowLine,
                    )
                }
            }
        }

        // NOTE-03 — RecentNotesSummary peek under the
        // stats panel. Hidden entirely when empty so the Card stays focused.
        RecentNotesSummary(
            notes = state.recentNotes,
            onOpenContact = { onOpenContact(contactId) },
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OrbitTheme.spacing.x6, vertical = OrbitTheme.spacing.x5),
        ) {
            // Buttons animate the card to its anchor (same settle path +
            // haptic as a drag) instead of mutating with zero motion.
            CircleSideButton("arrow-left", onClick = frameState::requestSwipeLeft)
            OrbitButton(
                text = "Call ${contact.name.substringBefore(' ')}",
                onClick = { onTapToCall(contactId, contact.phone) },
                leadingIcon = "phone-call",
                height = 56.dp,
                modifier = Modifier.weight(1f),
            )
            CircleSideButton("arrow-right", onClick = frameState::requestSwipeRight)
        }

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = OrbitTheme.spacing.x4),
        ) {
            Text(
                text = "Skip",
                style = OrbitTheme.type.skipAffordance,
                color = OrbitTheme.colors.fgSubtle,
                modifier = Modifier
                    .defaultMinSize(minWidth = 96.dp, minHeight = OrbitTheme.spacing.tapMin)
                    .clickable(onClick = frameState::requestSwipeLeft)
                    .padding(OrbitTheme.spacing.x3),
            )
            Text(
                text = "·",
                style = OrbitTheme.type.skipAffordance,
                color = OrbitTheme.colors.fgSubtle,
                modifier = Modifier.padding(horizontal = OrbitTheme.spacing.x1),
            )
            Text(
                text = "View details",
                style = OrbitTheme.type.skipAffordance,
                color = OrbitTheme.colors.fgSubtle,
                modifier = Modifier
                    .defaultMinSize(minWidth = 96.dp, minHeight = OrbitTheme.spacing.tapMin)
                    .clickable { onOpenContact(contactId) }
                    .padding(OrbitTheme.spacing.x3),
            )
        }
    }
}

/**
 * I-01 — offset-fading hint chips. `offsetFraction` runs in [-1f, 1f]:
 *   -1f → full Later commit (left chip at full opacity)
 *    0f → at rest (both chips invisible)
 *   +1f → full Sooner commit (right chip at full opacity)
 */
@Composable
private fun BoxScope.GhostHints(offsetFraction: Float) {
    val absFrac = abs(offsetFraction).coerceAtMost(1f)
    if (absFrac <= 0.01f) return

    if (offsetFraction > 0f) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = OrbitTheme.spacing.x5)
                .alpha(absFrac)
                .clip(OrbitTheme.shapes.full)
                .background(OrbitTheme.colors.swipeGhostSooner.copy(alpha = absFrac * 0.18f))
                .padding(horizontal = OrbitTheme.spacing.x3, vertical = OrbitTheme.spacing.x1),
        ) {
            OrbitChip(label = "Sooner", tone = ChipTone.Sage)
        }
    } else {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = OrbitTheme.spacing.x5)
                .alpha(absFrac)
                .clip(OrbitTheme.shapes.full)
                .background(OrbitTheme.colors.swipeGhostDefer.copy(alpha = absFrac * 0.18f))
                .padding(horizontal = OrbitTheme.spacing.x3, vertical = OrbitTheme.spacing.x1),
        ) {
            OrbitChip(label = "Later", tone = ChipTone.Stone)
        }
    }
}

@Composable
private fun CircleSideButton(icon: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .orbitCardShadow(OrbitTheme.shapes.full, OrbitTheme.colors.isDark)
            .clip(OrbitTheme.shapes.full)
            .background(OrbitTheme.colors.surface)
            .border(1.dp, OrbitTheme.colors.line, OrbitTheme.shapes.full)
            .clickable(onClick = onClick),
    ) {
        PhIcon(name = icon, size = 22.dp, tint = OrbitTheme.colors.fgMuted)
    }
}

@Composable
private fun ContactCardFace(
    contact: Contact,
    listContext: String,
    nowHour: Int,
    isAheadOfToday: Boolean,
    whyNowLine: String,
) {
    // 200% font-scale fix (2026-06-09 a11y sweep) — at default scale the
    // weight spacer pins StatRow to the card's bottom edge; at large font
    // scales the content outgrows the fixed card and the stats clipped
    // off the bottom. Above the threshold the column scrolls instead
    // (vertical scroll is cross-axis to CardSwipeFrame's horizontal drag,
    // so swipe handling is unaffected). Default-scale layout is untouched.
    val scrollForFontScale = LocalDensity.current.fontScale > 1.3f
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .then(if (scrollForFontScale) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = OrbitTheme.spacing.x6, vertical = OrbitTheme.spacing.x6),
        ) {
            Spacer(Modifier.height(OrbitTheme.spacing.x6))
            Avatar(name = contact.name, size = 104.dp)
            Spacer(Modifier.height(OrbitTheme.spacing.x3))
            // Tide marker (2026-05-08) — small framing line above the contact
            // name. `due today` when the engine's nextDueAt has arrived;
            // `ahead of today` past the waterline.
            Text(
                text = if (isAheadOfToday) "ahead of today" else "due today",
                style = OrbitTheme.type.eyebrow,
                color = OrbitTheme.colors.fgMuted,
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x1))
            Text(
                text = contact.name,
                style = OrbitTheme.type.contactName,
                color = OrbitTheme.colors.fg,
            )
            // 2026-06-09 — why-now line from the last connected call
            // ("It's been 3 weeks."). Hidden when there's no history.
            if (whyNowLine.isNotBlank()) {
                Spacer(Modifier.height(OrbitTheme.spacing.x1))
                Text(
                    text = whyNowLine,
                    style = OrbitTheme.type.meta,
                    color = OrbitTheme.colors.fgMuted,
                )
            }
            Spacer(Modifier.height(OrbitTheme.spacing.x4))
            // The pattern panel needs real signal — below the connected-call
            // floor the heat array stays all-zero and we show a neutral line
            // instead of a false "Rarely answers now" chip (2026-06-09 fix).
            if (contact.heat.any { it > 0f }) {
                UsuallyAnswersCard(contact, nowHour)
            } else {
                NoCallHistoryPanel()
            }
            if (scrollForFontScale) {
                Spacer(Modifier.height(OrbitTheme.spacing.x6))
            } else {
                Spacer(Modifier.weight(1f))
            }
            StatRow(contact)
        }
        // List-context chip, top-right; respects privacy curtain via wrapper.
        if (listContext.isNotBlank()) {
            ListContextChip(
                listName = listContext,
                tone = ChipTone.Terracotta,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(OrbitTheme.spacing.x3),
            )
        }
    }
}

/** Neutral stand-in for the pattern panel when call history is too thin. */
@Composable
private fun NoCallHistoryPanel() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(OrbitTheme.shapes.lg)
            .background(OrbitTheme.colors.bgSubtle)
            .padding(OrbitTheme.spacing.x4),
    ) {
        Text(
            text = "No call history yet",
            style = OrbitTheme.type.meta,
            color = OrbitTheme.colors.fgMuted,
        )
    }
}

@PreviewLightDark
@Composable
private fun NoCallHistoryPanelPreview() {
    OrbitTheme {
        NoCallHistoryPanel()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsuallyAnswersCard(contact: Contact, nowHour: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OrbitTheme.shapes.lg)
            .background(OrbitTheme.colors.bgSubtle)
            .padding(OrbitTheme.spacing.x4),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = "Usually answers",
                    style = OrbitTheme.type.eyebrow.copy(color = OrbitTheme.colors.fgMuted),
                )
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text(USUALLY_TOOLTIP) } },
                    state = rememberTooltipState(isPersistent = false),
                ) {
                    // 48dp tap target per rules.md design rule 3
                    // (the bare 14dp glyph was the whole target). The glyph
                    // stays small; the Box carries the gesture area and its
                    // padding replaces the old 6dp spacer.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(OrbitTheme.spacing.tapMin),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "About this stat",
                            tint = OrbitTheme.colors.fgMuted,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            Text(
                text = contact.bestWindowLabel,
                color = OrbitTheme.colors.fg,
                style = OrbitTheme.type.statValue,
            )
        }
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        HeatStrip(heat = contact.heat, nowHour = nowHour)
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        val peak = contact.heat.getOrNull(nowHour) ?: 0f
        val (tone, label) = when {
            peak >= 0.6f -> ChipTone.Sage to "Good time to call"
            peak >= 0.3f -> ChipTone.Amber to "Sometimes answers now"
            else -> ChipTone.Stone to "Rarely answers now"
        }
        OrbitChip(label = label, tone = tone)
    }
}

@Composable
private fun HeatStrip(heat: FloatArray, nowHour: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp),
    ) {
        heat.forEachIndexed { i, v ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(OrbitTheme.shapes.xs)
                    .background(OrbitHeatRamp.colorFor(v, OrbitTheme.colors.isDark))
                    .then(if (i == nowHour) Modifier.border(1.5.dp, OrbitTheme.colors.fg, OrbitTheme.shapes.xs) else Modifier),
            )
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf("12a", "6a", "12p", "6p", "12a").forEach {
            Text(it, style = OrbitTheme.type.timelineAxis, color = OrbitTheme.colors.fgSubtle)
        }
    }
}

@Composable
private fun StatRow(contact: Contact) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = OrbitTheme.spacing.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 2026-06-09 — hydrated by withCallStats; blanks coalesce to honest
        // placeholders. "Pickup" was dropped: call_events stores connected
        // calls only, so a pickup rate is not computable — "Calls" (total
        // recorded) is the truthful third stat.
        Stat("Last called", contact.lastCalledLabel.ifBlank { "Never" }, Modifier.weight(1f))
        Divider(28.dp)
        Stat("Avg length", contact.avgLengthLabel.ifBlank { "—" }, Modifier.weight(1f))
        Divider(28.dp)
        Stat("Calls", if (contact.totalCalls > 0) "${contact.totalCalls}" else "—", Modifier.weight(1f))
    }
}

@Composable
internal fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = OrbitTheme.type.timelineAxis.copy(color = OrbitTheme.colors.fgMuted),
        )
        Text(
            text = value,
            color = OrbitTheme.colors.fg,
            style = OrbitTheme.type.statValue,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun Divider(height: Dp) {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(height)
            .background(OrbitTheme.colors.lineSoft),
    )
}

/**
 * NOTE-03 — RecentNotesSummary on Card View.
 *
 * Renders up to 2 recent notes (caller pre-filters to last 30 days, max 2)
 * underneath the stats panel. Tapping anywhere on the section invokes
 * [onOpenContact]. When [notes] is empty the section is omitted entirely.
 *
 * B3 — Clock-free composable; relative timestamps come pre-formatted from
 * the VM's `toNoteRow(now)` mapper.
 */
@Composable
private fun RecentNotesSummary(
    notes: List<NoteRow>,
    onOpenContact: () -> Unit,
) {
    if (notes.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = OrbitTheme.spacing.x6,
                vertical = OrbitTheme.spacing.x2,
            ),
    ) {
        notes.forEach { note ->
            key(note.id) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenContact() }
                        .padding(vertical = OrbitTheme.spacing.x2),
                ) {
                    Text(
                        text = note.relativeTimestamp,
                        style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                    )
                    Text(
                        text = note.body,
                        style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// Preview fixture for the stateless CardViewContent.
// Contact carries a 24-element heat array with evening signal so the pattern
// panel renders in previews (THEME-04 / THEME-05 — D-06).
private val previewContact: Contact = Contact(
    id = "preview-1",
    name = "Avery Quinn",
    phone = "+1 555 0100",
    lastCalledLabel = "11 days ago",
    avgLengthLabel = "14 min",
    pickupRateLabel = "",
    totalCalls = 12,
    due = true,
    listIds = listOf("inner-orbit"),
    bestWindowLabel = "Evenings",
    heat = FloatArray(24) { h -> if (h in 18..21) 1f - (21 - h) * 0.2f else 0f },
    history = emptyList(),
    notes = emptyList(),
    patternNote = "Usually calls in the evening.",
)

private val previewState: CardViewUiState = CardViewUiState.Ready(
    contactId = 1L,
    contact = previewContact,
    listContext = "Inner orbit",
    queueSize = 5,
    recentNotes = emptyList(),
    nowHour = 19,
    whyNowLine = "It's been 11 days.",
)

private val previewStateAhead: CardViewUiState = CardViewUiState.Ready(
    contactId = 1L,
    contact = previewContact,
    listContext = "Inner orbit",
    queueSize = 5,
    recentNotes = emptyList(),
    nowHour = 19,
    isAheadOfToday = true,
    whyNowLine = "You talked yesterday.",
)

@Composable
private fun PreviewContent(state: CardViewUiState, callLogDenied: Boolean = false) {
    CardViewContent(
        state = state,
        listId = "inner-orbit",
        callLogDenied = callLogDenied,
        snackbarEvents = MutableSharedFlow<SnackbarEvent>().asSharedFlow(),
        onBack = {},
        onBrowse = {},
        onEditList = {},
        onAddContacts = {},
        onTapToCall = { _, _ -> },
        onSwipeLeft = {},
        onSwipeRight = {},
        onUndo = {},
        onOpenSettings = {},
        onOpenContact = {},
    )
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun CardViewContentPreview() {
    OrbitTheme {
        PreviewContent(state = previewState)
    }
}

@PreviewLightDark
@Composable
private fun CardViewContentAheadOfTodayPreview() {
    OrbitTheme {
        PreviewContent(state = previewStateAhead)
    }
}

@PreviewLightDark
@Composable
private fun CardViewContentCallLogDeniedPreview() {
    OrbitTheme {
        PreviewContent(state = previewState, callLogDenied = true)
    }
}

@PreviewLightDark
@Composable
private fun CardViewContentNoMembersPreview() {
    OrbitTheme {
        PreviewContent(state = CardViewUiState.EmptyNoMembers)
    }
}

@PreviewLightDark
@Composable
private fun CardViewContentNothingEligiblePreview() {
    OrbitTheme {
        PreviewContent(
            state = CardViewUiState.EmptyNothingEligible(
                upNextName = "Avery Quinn",
                upNextLabel = "on Tuesday",
            ),
        )
    }
}
