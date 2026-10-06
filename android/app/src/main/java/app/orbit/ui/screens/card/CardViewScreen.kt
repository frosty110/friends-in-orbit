package app.orbit.ui.screens.card

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.R
import app.orbit.data.ChipTone
import app.orbit.data.Contact
import app.orbit.data.NoteRow
import app.orbit.data.entity.ListType
import app.orbit.ui.components.Avatar
import app.orbit.ui.components.InfoTip
import app.orbit.ui.components.ListContextChip
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitChip
import app.orbit.ui.components.OrbitDropdownMenu
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitInlineNotice
import app.orbit.ui.components.OrbitMenuAction
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.components.OrbitSnackbarHost
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitMotion
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.orbitCardShadow
import app.orbit.ui.theme.orbitHeroShadow
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString
import app.orbit.ui.util.axisTickLabels
import app.orbit.ui.util.dialPhoneNumber
import app.orbit.ui.util.formatAgo
import app.orbit.ui.util.formatDuration
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

// Card View: drag to defer/surface, tap Call to dial.
// 2026-06-09 card-loop revision: hydrated stats + heat, swipe undo snackbars,
// call-log-denied notice, actionable empty states, and crossfaded card
// advancement via [CardSwipeFrame]. A call placed from here advances the deck
// silently once the call log is synced (no "did you talk?" confirmation).
// 2026-10-06 card-view audit: every state message is the shared
// OrbitScreenMessage (the error one with Try again, CARD-07, or Go home alone
// when nothing can be retried), the app bar is titled in every state, and
// "Add a note" lands in the note field (NOTE-02).

/**
 * @param onOpenContact opens a person's page from the face or "Open details".
 * @param onAddNote opens the same page with the note field focused (NOTE-02),
 *   from the "Called {name}" snackbar's "Add a note" (CARD-03). Defaults to
 *   [onOpenContact] so a host that has not wired the focus still opens the
 *   person; the NavHost passes `Routes.contactWithFocus`.
 */
@Composable
fun CardViewScreen(
    listId: String,
    onBack: () -> Unit,
    onCall: (contactId: String) -> Unit,
    onBrowse: (listId: String) -> Unit,
    onEditList: (listId: String) -> Unit = {},
    onAddContacts: (listId: String) -> Unit = {},
    onOpenContact: (contactId: String) -> Unit = onCall, // NOTE-03 — RecentNotesSummary tap target
    onAddNote: (contactId: String) -> Unit = onOpenContact,
    onOpenSettings: () -> Unit = {},
    vm: CardViewViewModel = hiltViewModel()
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
            Manifest.permission.READ_CALL_LOG
        ) != PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }

    CardViewContent(
        state = state,
        listId = listId,
        callLogDenied = callLogDenied,
        messages = vm.messages,
        onBack = onBack,
        onBrowse = onBrowse,
        onEditList = onEditList,
        onAddContacts = onAddContacts,
        onTapToCall = { contactId, phone ->
            // Only the labelled Call button dials (CARD-01). vm.onCall records
            // that a dial happened so the screen triggers an immediate
            // call-log resync on return and the deck advances on its own.
            context.dialPhoneNumber(phone)
            vm.onCall(contactId)
        },
        onSwipeLeft = vm::onSwipeLeft,
        onSwipeRight = vm::onSwipeRight,
        onUndo = vm::onUndo,
        onRetry = vm::onRetry,
        onOpenSettings = onOpenSettings,
        onOpenContact = { contactId -> onOpenContact("c-$contactId") },
        onAddNote = { contactId -> onAddNote("c-$contactId") }
    )
}

/**
 * List actions overflow for the Card view. Three dots, the platform's sign for
 * "more options" (it was a hamburger, which on Home means "your lists"; one
 * icon meaning two things is the navigation bug the rubric's D2 names).
 * Named for the list it acts on, "More actions for Inner orbit", like every
 * overflow button (voice.md glossary; it said "List options", a third name
 * for the same control). [listName] is already masked under the curtain.
 */
@Composable
private fun ListActionsMenu(
    listName: String,
    listType: ListType?,
    onBrowse: () -> Unit,
    onEditList: () -> Unit,
    onAddContacts: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OrbitIconButton(
            icon = "dots-three-vertical",
            onClick = { expanded = true },
            contentDescription = if (listName.isBlank()) {
                stringResource(R.string.card_list_options_unnamed)
            } else {
                stringResource(R.string.card_list_options, listName)
            }
        )
        OrbitDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            actions = cardListMenuActions(
                LocalContext.current.resources,
                listType = listType,
                onBrowse = onBrowse,
                onAddContacts = onAddContacts,
                onEditList = onEditList
            )
        )
    }
}

/**
 * The Card view's list menu, in order: Browse people, Add people, List
 * settings. Nothing here is destructive, so the whole menu stays in fg. "Add
 * people" is offered only once the list is known to be a regular one
 * ([ListType.STATIC]). On a smart list its members are what its rule matches
 * (`SmartListMembershipSync`), so anyone added by hand was removed again on
 * the next sync with no word (rules.md Code 3), the same reason Lists
 * manager's row hides its "+". While the type is unknown (null: the Loading
 * and Error decks, see [CardViewUiState.listType]) it is withheld too; the
 * menu used to ask "is it smart?", which read an unknown type as "not smart"
 * and offered Add people on a failed smart list. `internal` so the order and
 * the omissions are unit-tested (CardListMenuTest); takes [Resources] because
 * [OrbitMenuAction] carries resolved text (the `listRowMenuActions`
 * precedent).
 */
internal fun cardListMenuActions(
    resources: Resources,
    listType: ListType?,
    onBrowse: () -> Unit,
    onAddContacts: () -> Unit,
    onEditList: () -> Unit
): List<OrbitMenuAction> = listOfNotNull(
    OrbitMenuAction(label = resources.getString(R.string.card_menu_browse), onClick = onBrowse, icon = "list-bullets"),
    if (listType == ListType.STATIC) {
        OrbitMenuAction(label = resources.getString(R.string.card_menu_add_people), onClick = onAddContacts, icon = "plus")
    } else {
        null
    },
    OrbitMenuAction(label = resources.getString(R.string.card_menu_list_settings), onClick = onEditList, icon = "pencil-simple")
)

// internal (not private) so CardViewScreenTest can drive the stateless content
// with a Ready state and callbacks (CARD-01, CARD-06); CardViewScreen itself
// takes a Hilt ViewModel and cannot be composed on the JVM.
@Composable
internal fun CardViewContent(
    state: CardViewUiState,
    listId: String,
    callLogDenied: Boolean,
    messages: SharedFlow<CardMessage>,
    onBack: () -> Unit,
    onBrowse: (listId: String) -> Unit,
    onEditList: (listId: String) -> Unit,
    onAddContacts: (listId: String) -> Unit,
    onTapToCall: (contactId: Long, phone: String) -> Unit,
    onSwipeLeft: (contactId: Long) -> Unit,
    onSwipeRight: (contactId: Long) -> Unit,
    onUndo: (token: Long) -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenContact: (contactId: Long) -> Unit,
    onAddNote: (contactId: Long) -> Unit = onOpenContact
) {
    val curtain = LocalPrivacyCurtain.current
    val context = LocalContext.current
    // The list's name titles every state that knows it (an empty or failed
    // deck used to leave the bar untitled, so TalkBack announced no pane and
    // "this list" was never named on screen). PRIV-03: it masks as "List"
    // (it read "Contact", the noun for a person). Blank while loading or when
    // the list itself could not be read; OrbitAppBar then sets no pane title.
    val listName = state.listName
    val appBarTitle = when {
        listName.isBlank() -> ""
        curtain -> stringResource(R.string.components_curtain_list)
        else -> listName
    }
    val isSmart = state.listType == ListType.SMART
    // An Error deck with no list name (a malformed id, or the list itself
    // could not be read) leaves the app bar untitled, so nothing in the tree
    // names the pane and TalkBack announces no screen at all (rubric D8). The
    // root carries the message's own heading then, as Contact detail's root
    // does for a person who could not be read. Not through appBarTitle: that
    // also names the overflow ("More actions for Something's off here.").
    val errorPaneTitle = if (state is CardViewUiState.Error && listName.isBlank()) {
        stringResource(R.string.card_error_heading)
    } else {
        null
    }

    // One snackbar at a time, newest wins (CARD-02, gate G1): collectLatest
    // cancels the older showSnackbar, which dismisses it, so the Undo on
    // screen always belongs to the person named in it. Undo snackbars stay
    // up for the long duration (and longer if the user has raised Android's
    // "time to take action" setting, which Material's host honours).
    val snackbarHostState = remember { SnackbarHostState() }
    val currentOnUndo by rememberUpdatedState(onUndo)
    val currentOnAddNote by rememberUpdatedState(onAddNote)
    LaunchedEffect(Unit) {
        messages.collectLatest { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val actionLabel = when (message) {
                is CardMessage.Undoable -> context.getString(R.string.components_action_undo)
                is CardMessage.Called -> context.getString(R.string.card_snackbar_add_note)
                is CardMessage.Failed -> null
            }
            val result = snackbarHostState.showSnackbar(
                message = message.text.asString(context),
                actionLabel = actionLabel,
                duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
                withDismissAction = false
            )
            if (result == SnackbarResult.ActionPerformed) {
                when (message) {
                    is CardMessage.Undoable -> currentOnUndo(message.token)
                    // CARD-03: "Add a note" lands in the note field (NOTE-02),
                    // as Home's does; it used to open the top of the page.
                    is CardMessage.Called -> currentOnAddNote(message.contactId)
                    is CardMessage.Failed -> Unit
                }
            }
        }
    }

    OrbitScreen(
        modifier = Modifier.semantics { if (errorPaneTitle != null) paneTitle = errorPaneTitle }
    ) {
        OrbitAppBar(
            title = appBarTitle,
            leading = {
                OrbitIconButton("arrow-left", onBack, contentDescription = stringResource(R.string.components_action_back))
            },
            trailing = {
                ListActionsMenu(
                    listName = appBarTitle,
                    listType = state.listType,
                    onBrowse = { onBrowse(listId) },
                    onEditList = { onEditList(listId) },
                    onAddContacts = { onAddContacts(listId) }
                )
            }
        )

        // Without READ_CALL_LOG, calls aren't detected and cards don't advance
        // on their own; the honest state stays visible until it is fixed. The
        // shared notice (it was a private copy that had drifted from Call
        // history's, calllog-13), with the screen's own words.
        if (callLogDenied) {
            OrbitInlineNotice(
                text = stringResource(R.string.card_call_log_denied),
                actionLabel = stringResource(R.string.components_action_open_settings),
                onAction = onOpenSettings
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (state) {
                // F-8 — Loading is the pre-emission placeholder. Rendering
                // nothing avoids a flash of empty copy between screen open and
                // the CardFeed's first emission.
                CardViewUiState.Loading -> Box(modifier = Modifier.fillMaxSize())
                is CardViewUiState.EmptyNoMembers -> NoMembersShell(
                    isSmart = isSmart,
                    onAddContacts = { onAddContacts(listId) },
                    onEditList = { onEditList(listId) },
                    onGoHome = onBack
                )
                is CardViewUiState.EmptyNothingEligible -> NothingEligibleShell(
                    state = state,
                    onBrowse = { onBrowse(listId) },
                    onGoHome = onBack
                )
                is CardViewUiState.Error -> ErrorShell(
                    canRetry = state.canRetry,
                    onRetry = onRetry,
                    onGoHome = onBack
                )
                is CardViewUiState.Ready -> ReadyCard(
                    state = state,
                    onTapToCall = onTapToCall,
                    onSwipeLeft = onSwipeLeft,
                    onSwipeRight = onSwipeRight,
                    onOpenContact = onOpenContact
                )
            }
            OrbitSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(OrbitTheme.spacing.x4)
            )
        }
    }
}

// The three state messages below are the shared OrbitScreenMessage (DESIGN.md:
// one component for "nothing here yet / nothing matches / couldn't load"),
// each with the next step as its one action and "Go home" as the quieter
// Ghost second way out. They kept a layout of their own (EmptyShell) until
// 2026-10-06, which is the drift the component table exists to stop
// (state-9). On these decks there is no Call button, so the one action is
// the screen's single accent (rules.md §Design 5) and may be Primary.

/**
 * Tide-marker empty state #1: the list has zero non-archived non-ignored
 * members. The action is the fix ("Add people"), not an exit. On a smart
 * list the members are its rule's matches, so the fix is the rule: "No one
 * matches this rule right now." with "List settings" (the same words as List
 * settings' own members section), and "Add people" is not offered (see
 * [cardListMenuActions]).
 */
@Composable
private fun NoMembersShell(
    isSmart: Boolean,
    onAddContacts: () -> Unit,
    onEditList: () -> Unit,
    onGoHome: () -> Unit
) {
    if (isSmart) {
        OrbitScreenMessage(
            icon = "users",
            title = stringResource(R.string.lists_members_empty_smart),
            actionLabel = stringResource(R.string.card_menu_list_settings),
            onAction = onEditList,
            actionVariant = OrbitButtonVariant.Primary,
            secondaryLabel = stringResource(R.string.card_go_home),
            onSecondary = onGoHome
        )
    } else {
        OrbitScreenMessage(
            icon = "users",
            title = stringResource(R.string.card_no_members_heading),
            body = stringResource(R.string.card_no_members_body),
            actionLabel = stringResource(R.string.card_add_people),
            onAction = onAddContacts,
            actionVariant = OrbitButtonVariant.Primary,
            secondaryLabel = stringResource(R.string.card_go_home),
            onSecondary = onGoHome
        )
    }
}

/**
 * Tide-marker empty state #2: the list has visible members but none
 * surfaces right now. 2026-06-09: the old "paused or out of reach" line was
 * false for lists whose members simply aren't due; the copy now leads with
 * the member who comes back soonest when the feed can see one (a paused
 * person for when the pause lifts, CardFeed.upNextFor), and offers Browse
 * as a way in rather than stranding the user.
 */
@Composable
private fun NothingEligibleShell(
    state: CardViewUiState.EmptyNothingEligible,
    onBrowse: () -> Unit,
    onGoHome: () -> Unit
) {
    val curtain = LocalPrivacyCurtain.current
    val body = if (state.upNextName != null && state.upNextLabel != null) {
        val who = if (curtain) stringResource(R.string.components_curtain_someone) else state.upNextName
        stringResource(R.string.card_quiet_body_up_next, who, state.upNextLabel.asString())
    } else {
        stringResource(R.string.card_quiet_body)
    }
    OrbitScreenMessage(
        icon = "moon",
        // CARD-05: a calm word for "nobody is due right now". Not "caught up":
        // the queue is continuous by design (HOME-6, SurfaceResult.kt), so
        // nothing here suggests a backlog was cleared or a task finished.
        title = stringResource(R.string.card_quiet_heading),
        body = body,
        actionLabel = stringResource(R.string.card_browse_list),
        onAction = onBrowse,
        actionVariant = OrbitButtonVariant.Primary,
        secondaryLabel = stringResource(R.string.card_go_home),
        onSecondary = onGoHome
    )
}

/**
 * CARD-07: a failed read says so and offers Try again (the accent) with Go
 * home beneath. The body is the shared one ("Nothing is lost. Try again in a
 * moment."); the old body told the user to try again while the screen
 * offered no way to. When nothing can be retried ([canRetry] false: the list
 * id never parsed, so there is no feed to re-subscribe) Go home is the one
 * action and takes the accent; a Try again that did nothing was the screen's
 * accent until 2026-10-06 (rules.md Code 3).
 */
@Composable
private fun ErrorShell(canRetry: Boolean, onRetry: () -> Unit, onGoHome: () -> Unit) {
    if (canRetry) {
        OrbitScreenMessage(
            icon = "warning-circle",
            title = stringResource(R.string.card_error_heading),
            body = stringResource(R.string.components_error_body),
            actionLabel = stringResource(R.string.components_error_retry),
            onAction = onRetry,
            actionVariant = OrbitButtonVariant.Primary,
            secondaryLabel = stringResource(R.string.card_go_home),
            onSecondary = onGoHome
        )
    } else {
        OrbitScreenMessage(
            icon = "warning-circle",
            title = stringResource(R.string.card_error_heading),
            body = stringResource(R.string.components_error_body),
            actionLabel = stringResource(R.string.card_go_home),
            onAction = onGoHome,
            actionVariant = OrbitButtonVariant.Primary
        )
    }
}

@Composable
private fun ReadyCard(
    state: CardViewUiState.Ready,
    onTapToCall: (contactId: Long, phone: String) -> Unit,
    onSwipeLeft: (contactId: Long) -> Unit,
    onSwipeRight: (contactId: Long) -> Unit,
    onOpenContact: (contactId: Long) -> Unit
) {
    val contactId = state.contactId
    val contact = state.contact
    val frameState = remember { CardSwipeFrameState() }
    val curtain = LocalPrivacyCurtain.current
    // Masked like the face and app bar: "Call Contact" under the curtain.
    val maskedName = stringResource(R.string.components_curtain_contact)
    val firstName = (if (curtain) maskedName else contact.name).substringBefore(' ')

    // CARD-06: in landscape on a phone (short and wide) the card and its
    // actions sit side by side. Stacked, the card face got about 120dp of
    // height and showed only the top of the avatar (rubric gate G3, found by
    // rendering at w740dp-h360dp). Portrait is unchanged.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sideBySide = maxWidth > maxHeight && maxHeight < LANDSCAPE_MAX_HEIGHT
        val frame: @Composable (Modifier) -> Unit = { frameModifier ->
            CardSwipeFrame(
                contactKey = contactId,
                emissionKey = state,
                frameState = frameState,
                onSwipeLeft = { onSwipeLeft(contactId) },
                onSwipeRight = { onSwipeRight(contactId) },
                modifier = frameModifier
                    .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x3),
                ghostOverlay = { offsetFraction -> GhostHints(offsetFraction) }
            ) {
                // Crossfade keyed on contactId: the outgoing face fades while the
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
                    modifier = Modifier.fillMaxSize()
                ) { face ->
                    val faceFirst = (if (curtain) maskedName else face.contact.name).substringBefore(' ')
                    // Resolved here: the semantics block below is not composable.
                    val callLabel = stringResource(R.string.card_call, faceFirst)
                    val laterLabel = stringResource(R.string.card_later)
                    val soonerLabel = stringResource(R.string.card_sooner)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .orbitHeroShadow(OrbitTheme.shapes.xl, OrbitTheme.colors.isDark)
                            .clip(OrbitTheme.shapes.xl)
                            .background(OrbitTheme.colors.surface)
                            // CARD-01: tapping the face opens the person's details.
                            // It used to dial, so the natural "look closer" tap
                            // placed a call (one was placed by accident in review).
                            // A call reaches another person and can't be undone, so
                            // only the labelled Call button dials. "Open details" is
                            // the one name for this everywhere (voice.md glossary),
                            // on screen below and to TalkBack here.
                            .clickable(onClickLabel = stringResource(R.string.components_action_open_details), role = Role.Button) {
                                onOpenContact(face.contactId)
                            }
                            // The swipes, and the call, as named actions on the node
                            // TalkBack actually focuses (they used to sit on the
                            // non-focusable frame, out of reach).
                            .semantics {
                                customActions = listOf(
                                    CustomAccessibilityAction(callLabel) {
                                        onTapToCall(face.contactId, face.contact.phone); true
                                    },
                                    CustomAccessibilityAction(laterLabel) { frameState.requestSwipeLeft(); true },
                                    CustomAccessibilityAction(soonerLabel) { frameState.requestSwipeRight(); true },
                                )
                            }
                    ) {
                        ContactCardFace(
                            contact = face.contact,
                            listContext = face.listContext,
                            nowHour = face.nowHour,
                            isAheadOfToday = face.isAheadOfToday,
                            whyNowLine = face.whyNowLine,
                            lastNote = face.recentNotes.firstOrNull()
                        )
                    }
                }
            }
        }
        val actions: @Composable () -> Unit = {
            val laterButton: @Composable () -> Unit = {
                CircleSideButton("arrow-left", label = stringResource(R.string.card_later), onClick = frameState::requestSwipeLeft)
            }
            val soonerButton: @Composable () -> Unit = {
                CircleSideButton("arrow-right", label = stringResource(R.string.card_sooner), onClick = frameState::requestSwipeRight)
            }
            val callButton: @Composable (Modifier) -> Unit = { callModifier ->
                OrbitButton(
                    text = stringResource(R.string.card_call, firstName),
                    onClick = { onTapToCall(contactId, contact.phone) },
                    leadingIcon = "phone-call",
                    height = 56.dp,
                    modifier = callModifier
                )
            }
            val rowPadding = Modifier.padding(
                start = OrbitTheme.spacing.x4,
                end = OrbitTheme.spacing.x4,
                top = OrbitTheme.spacing.x4,
                bottom = OrbitTheme.spacing.x2
            )
            // Buttons animate the card to its anchor (same settle path +
            // haptic as a drag) instead of mutating with zero motion. CARD-02:
            // they carry their names, "Later" and "Sooner", on screen and to
            // TalkBack; the bare arrows were unlabelled.
            if (LocalDensity.current.fontScale > 1.3f) {
                // Large text (CARD-06): Call gets its own full-width row. Between
                // the two labelled side buttons it was left too narrow for one
                // word, and at 200% read "Cal / l / Av / ery".
                Column(
                    verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
                    modifier = Modifier.fillMaxWidth().then(rowPadding),
                ) {
                    callButton(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        laterButton()
                        soonerButton()
                    }
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth().then(rowPadding),
                ) {
                    laterButton()
                    callButton(Modifier.weight(1f))
                    soonerButton()
                }
            }

            // A visible way in for anyone who doesn't guess the card is tappable.
            // "Skip" used to sit here too; it did exactly what Later does, under a
            // third name for the same thing. "Open details", the same words the
            // face's TalkBack label uses (it read "View details", a second name).
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = OrbitTheme.spacing.x3)
            ) {
                Text(
                    text = stringResource(R.string.components_action_open_details),
                    style = OrbitTheme.type.skipAffordance,
                    color = OrbitTheme.colors.fgMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 96.dp, minHeight = OrbitTheme.spacing.tapMin)
                        .clip(OrbitTheme.shapes.md)
                        .clickable(role = Role.Button) { onOpenContact(contactId) }
                        .padding(OrbitTheme.spacing.x3)
                )
            }
        }
        if (sideBySide) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                frame(Modifier.weight(1f).fillMaxHeight())
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.width(LANDSCAPE_ACTIONS_WIDTH),
                ) { actions() }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                frame(Modifier.fillMaxWidth().weight(1f))
                actions()
            }
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
                .padding(horizontal = OrbitTheme.spacing.x3, vertical = OrbitTheme.spacing.x1)
        ) {
            OrbitChip(label = stringResource(R.string.card_sooner), tone = ChipTone.Sage)
        }
    } else {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = OrbitTheme.spacing.x5)
                .alpha(absFrac)
                .clip(OrbitTheme.shapes.full)
                .background(OrbitTheme.colors.swipeGhostDefer.copy(alpha = absFrac * 0.18f))
                .padding(horizontal = OrbitTheme.spacing.x3, vertical = OrbitTheme.spacing.x1)
        ) {
            OrbitChip(label = stringResource(R.string.card_later), tone = ChipTone.Stone)
        }
    }
}

@Composable
private fun CircleSideButton(icon: String, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(OrbitTheme.shapes.md)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .orbitCardShadow(OrbitTheme.shapes.full, OrbitTheme.colors.isDark)
                .clip(OrbitTheme.shapes.full)
                .background(OrbitTheme.colors.surface)
                .border(1.dp, OrbitTheme.colors.line, OrbitTheme.shapes.full)
        ) {
            PhIcon(name = icon, size = 22.dp, tint = OrbitTheme.colors.fgMuted)
        }
        Text(
            text = label,
            style = OrbitTheme.type.meta,
            color = OrbitTheme.colors.fgMuted,
            modifier = Modifier.padding(top = OrbitTheme.spacing.x1)
        )
    }
}

// internal (not private) so CardFaceCurtainTest can compose the face directly.
@Composable
internal fun ContactCardFace(
    contact: Contact,
    listContext: String,
    nowHour: Int,
    isAheadOfToday: Boolean,
    whyNowLine: UiText?,
    lastNote: NoteRow? = null
) {
    // The face always scrolls when its content does not fit, and otherwise
    // pins StatRow to the bottom edge (SpaceBetween over a min height of the
    // card). It used to scroll only above 130% font scale, so at 130% on a
    // 360dp phone the stats and pattern panel were crushed and clipped
    // (rubric gate G3, found by rendering at w360dp). Vertical scroll is
    // cross-axis to CardSwipeFrame's horizontal drag, so swiping is unaffected.
    // PRIV-03: the face renders contact.name, so it masks it like every other
    // surface does; the app bar above already reads "Contact" under the curtain,
    // and a real name (or real initials) on the card below it was the leak.
    val curtain = LocalPrivacyCurtain.current
    val shownName = if (curtain) stringResource(R.string.components_curtain_contact) else contact.name
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(
                    start = OrbitTheme.spacing.x6,
                    end = OrbitTheme.spacing.x6,
                    top = OrbitTheme.spacing.x3,
                    bottom = OrbitTheme.spacing.x6,
                )
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                // List-context chip, top right. In the column's flow rather than
                // overlaid on the face: overlaid, a long list name at 200% text
                // wrapped over the avatar (gate G3). The offset keeps it 12dp
                // from the card's edge, where it always sat.
                if (listContext.isNotBlank()) {
                    ListContextChip(
                        listName = listContext,
                        tone = ChipTone.Terracotta,
                        modifier = Modifier
                            .align(Alignment.End)
                            .offset(x = OrbitTheme.spacing.x3)
                    )
                    Spacer(Modifier.height(OrbitTheme.spacing.x3))
                } else {
                    Spacer(Modifier.height(OrbitTheme.spacing.x6 + OrbitTheme.spacing.x3))
                }
                Avatar(name = shownName, size = 104.dp, photoUri = if (curtain) null else contact.photoUri)
                Spacer(Modifier.height(OrbitTheme.spacing.x3))
                // Tide marker (2026-05-08): small framing line above the contact
                // name. "Up now" when the engine's nextDueAt has arrived; "Coming
                // up" past the waterline. A fact about the rhythm, never a
                // deadline: it read "Due today" / "Not due yet", and "due" is the
                // deadline framing voice.md retired with HOME-6.
                Text(
                    text = stringResource(if (isAheadOfToday) R.string.card_not_due_yet else R.string.card_due_today),
                    style = OrbitTheme.type.eyebrow,
                    color = OrbitTheme.colors.fgMuted
                )
                Spacer(Modifier.height(OrbitTheme.spacing.x1))
                Text(
                    text = shownName,
                    style = OrbitTheme.type.contactName,
                    color = OrbitTheme.colors.fg,
                    textAlign = TextAlign.Center
                )
                // 2026-06-09 — why-now line from the last connected call
                // ("You spoke 3 weeks ago."). Hidden when there's no history.
                if (whyNowLine != null) {
                    Spacer(Modifier.height(OrbitTheme.spacing.x1))
                    Text(
                        text = whyNowLine.asString(),
                        style = OrbitTheme.type.meta,
                        color = OrbitTheme.colors.fgMuted,
                        textAlign = TextAlign.Center
                    )
                }
                // CARD-04: the last thing you noted is the best reason to call,
                // so it leads the context, ahead of the statistics. Note bodies
                // are private, so the curtain hides it.
                if (lastNote != null && !curtain) {
                    Spacer(Modifier.height(OrbitTheme.spacing.x3))
                    Text(
                        text = stringResource(R.string.card_last_note_quoted, lastNote.body),
                        style = OrbitTheme.type.body,
                        color = OrbitTheme.colors.fg,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(
                            R.string.card_last_note_meta,
                            lastNote.relativeTimestamp?.asString().orEmpty()
                        ),
                        style = OrbitTheme.type.meta,
                        color = OrbitTheme.colors.fgMuted
                    )
                }
                Spacer(Modifier.height(OrbitTheme.spacing.x4))
                // The pattern panel needs real signal — below the connected-call
                // floor the heat array stays all-zero and we show a neutral line
                // instead of a false "Rarely answers now" chip (2026-06-09 fix).
                if (contact.heat.any { it > 0f }) {
                    UsuallyAnswersCard(contact, nowHour)
                } else {
                    NotEnoughCallsPanel()
                }
            }
            Spacer(Modifier.height(OrbitTheme.spacing.x6))
            StatRow(contact)
        }
    }
}

/**
 * Neutral stand-in for the pattern panel below the three-connected-call floor
 * (`withCallPatterns`). Worded for what is true in every case it covers: it
 * said "No call history yet" beside "Last call 3 days ago" and "Total calls
 * 2", because the stats hydrate from the first call and the pattern from the
 * third.
 */
@Composable
private fun NotEnoughCallsPanel() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(OrbitTheme.shapes.lg)
            .background(OrbitTheme.colors.bgSubtle)
            .padding(OrbitTheme.spacing.x4)
    ) {
        Text(
            text = stringResource(R.string.card_pattern_not_enough_calls),
            style = OrbitTheme.type.meta,
            color = OrbitTheme.colors.fgMuted,
            textAlign = TextAlign.Center
        )
    }
}

@PreviewLightDark
@Composable
private fun NotEnoughCallsPanelPreview() {
    OrbitTheme {
        NotEnoughCallsPanel()
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
            .padding(OrbitTheme.spacing.x4)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.card_usually_answers),
                    style = OrbitTheme.type.eyebrow.copy(color = OrbitTheme.colors.fgMuted),
                    // Shrinks before the info button does, so the button
                    // survives 200% font scale.
                    modifier = Modifier.weight(1f, fill = false)
                )
                InfoTip(
                    text = stringResource(R.string.card_usually_tooltip),
                    label = stringResource(R.string.card_usually_tooltip_label),
                )
            }
            Text(
                text = contact.bestWindowLabel?.asString().orEmpty(),
                color = OrbitTheme.colors.fg,
                style = OrbitTheme.type.statValue
            )
        }
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        HeatStrip(heat = contact.heat, nowHour = nowHour)
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        val peak = contact.heat.getOrNull(nowHour) ?: 0f
        val (tone, label) = when {
            peak >= 0.6f -> ChipTone.Sage to R.string.card_answer_good
            peak >= 0.3f -> ChipTone.Amber to R.string.card_answer_sometimes
            else -> ChipTone.Stone to R.string.card_answer_rarely
        }
        OrbitChip(label = stringResource(label), tone = tone)
    }
}

@Composable
private fun HeatStrip(heat: FloatArray, nowHour: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.hair),
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
    ) {
        heat.forEachIndexed { i, v ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(OrbitTheme.shapes.xs)
                    .background(OrbitTheme.tones.heatColor(v))
                    .then(
                        if (i == nowHour) {
                            Modifier.border(
                                1.5.dp,
                                OrbitTheme.colors.fg,
                                OrbitTheme.shapes.xs
                            )
                        } else {
                            Modifier
                        }
                    )
            )
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = OrbitTheme.spacing.x2),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Midnight, 6am, noon, 6pm, midnight in the phone's 12 or 24 hour
        // style (strings_time.xml, shared with a list's active hours bar).
        axisTickLabels().forEach {
            Text(stringResource(it), style = OrbitTheme.type.timelineAxis, color = OrbitTheme.colors.fgSubtle)
        }
    }
}

@Composable
private fun StatRow(contact: Contact) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = OrbitTheme.spacing.x4),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 2026-06-09: hydrated by withCallStats; a blank stat says so in the
        // words Contact detail uses ("Never called", "Not enough calls yet"),
        // never a dash, which TalkBack read as "en dash" or nothing. "Pickup"
        // was dropped: call_events stores connected calls only, so a pickup
        // rate is not computable, so "Total calls" is the truthful third stat.
        val none = stringResource(R.string.components_stat_not_enough_calls)
        val never = stringResource(R.string.components_stat_never_called)
        Stat(
            stringResource(R.string.card_stat_last_called),
            contact.lastCalledLabel?.asString() ?: never,
            Modifier.weight(1f)
        )
        Divider(28.dp)
        Stat(
            stringResource(R.string.card_stat_avg_length),
            contact.avgLengthLabel?.asString() ?: none,
            Modifier.weight(1f)
        )
        Divider(28.dp)
        Stat(
            stringResource(R.string.card_stat_calls),
            if (contact.totalCalls > 0) "${contact.totalCalls}" else none,
            Modifier.weight(1f)
        )
    }
}

@Composable
internal fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(
            text = label,
            style = OrbitTheme.type.timelineAxis.copy(color = OrbitTheme.colors.fgMuted)
        )
        Text(
            text = value,
            color = OrbitTheme.colors.fg,
            style = OrbitTheme.type.statValue,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = OrbitTheme.spacing.hair)
        )
    }
}

@Composable
private fun Divider(height: Dp) {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(height)
            .background(OrbitTheme.colors.lineSoft)
    )
}

// Preview fixture for the stateless CardViewContent.
// Contact carries a 24-element heat array with evening signal so the pattern
// panel renders in previews (THEME-04 / THEME-05 — D-06).
private val previewContact: Contact = Contact(
    id = "preview-1",
    name = "Avery Quinn",
    phone = "+1 555 0100",
    lastCalledLabel = UiText.plural(R.plurals.time_ago_days, 11, 11),
    avgLengthLabel = formatDuration(14 * 60),
    pickupRateLabel = "",
    totalCalls = 12,
    due = true,
    listIds = listOf("inner-orbit"),
    bestWindowLabel = UiText.res(R.string.time_daypart_evenings),
    heat = FloatArray(24) { h -> if (h in 18..21) 1f - (21 - h) * 0.2f else 0f },
    history = emptyList(),
    notes = emptyList(),
    patternNote = "Usually calls in the evening."
)

private val previewState: CardViewUiState = CardViewUiState.Ready(
    contactId = 1L,
    contact = previewContact,
    listContext = "Inner orbit",
    queueSize = 5,
    recentNotes = listOf(
        NoteRow(
            id = 1L,
            contactId = 1L,
            body = "Starting the new job on Monday. Ask how the first week went.",
            createdAtMs = 0L,
            relativeTimestamp = UiText.plural(R.plurals.time_ago_days, 12, 12),
            absoluteTimestamp = "",
        )
    ),
    nowHour = 19,
    whyNowLine = UiText.res(
        R.string.card_why_two_lines,
        UiText.res(R.string.card_why_ago, formatAgo(11)),
        UiText.plural(R.plurals.card_rhythm_weeks, 2, 2),
    )
)

private val previewStateAhead: CardViewUiState = CardViewUiState.Ready(
    contactId = 1L,
    contact = previewContact,
    listContext = "Inner orbit",
    queueSize = 5,
    recentNotes = emptyList(),
    nowHour = 19,
    isAheadOfToday = true,
    whyNowLine = UiText.res(R.string.card_why_yesterday)
)

@Composable
private fun PreviewContent(state: CardViewUiState, callLogDenied: Boolean = false) {
    CardViewContent(
        state = state,
        listId = "inner-orbit",
        callLogDenied = callLogDenied,
        messages = MutableSharedFlow<CardMessage>().asSharedFlow(),
        onBack = {},
        onBrowse = {},
        onEditList = {},
        onAddContacts = {},
        onTapToCall = { _, _ -> },
        onSwipeLeft = {},
        onSwipeRight = {},
        onUndo = {},
        onRetry = {},
        onOpenSettings = {},
        onOpenContact = {}
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

// Gate G3: a 40-character name and list name, at 100% and 200% text.
@PreviewLightDark
@Preview(name = "200%", fontScale = 2f)
@Composable
private fun CardViewContentLongNamesPreview() {
    OrbitTheme {
        PreviewContent(
            state = (previewState as CardViewUiState.Ready).copy(
                contact = previewContact.copy(name = "Bartholomew Montgomery-Featherstonehaugh"),
                listContext = "Old friends from the climbing gym crew",
            )
        )
    }
}

@PreviewLightDark
@Composable
private fun CardViewContentAheadOfTodayPreview() {
    OrbitTheme {
        PreviewContent(state = previewStateAhead)
    }
}

// Someone never called: the three stats say so in words ("Never called", "Not
// enough calls yet", never a dash) and the pattern panel says what it needs.
// Rendered at 200% too, because those words share a third of the width each.
@PreviewLightDark
@Preview(name = "200%", fontScale = 2f)
@Composable
private fun CardViewContentNeverCalledPreview() {
    OrbitTheme {
        PreviewContent(
            state = (previewState as CardViewUiState.Ready).copy(
                contact = previewContact.copy(
                    lastCalledLabel = null,
                    avgLengthLabel = null,
                    totalCalls = 0,
                    bestWindowLabel = null,
                    heat = FloatArray(24),
                ),
                recentNotes = emptyList(),
                whyNowLine = null,
            )
        )
    }
}

@PreviewLightDark
@Composable
private fun CardViewContentCallLogDeniedPreview() {
    OrbitTheme {
        PreviewContent(state = previewState, callLogDenied = true)
    }
}

// One preview per state, so each renders in the screenshot gallery and its
// a11y and curtain audits see it (state-10). The list name is on every one.

@PreviewLightDark
@Composable
private fun CardViewContentNoMembersPreview() {
    OrbitTheme {
        PreviewContent(state = CardViewUiState.EmptyNoMembers(listName = "Inner orbit"))
    }
}

@PreviewLightDark
@Composable
private fun CardViewContentSmartNoMembersPreview() {
    OrbitTheme {
        PreviewContent(state = CardViewUiState.EmptyNoMembers(listName = "Late night", listType = ListType.SMART))
    }
}

@PreviewLightDark
@Composable
private fun CardViewContentNothingEligiblePreview() {
    OrbitTheme {
        PreviewContent(
            state = CardViewUiState.EmptyNothingEligible(
                upNextName = "Avery Quinn",
                upNextLabel = UiText.res(R.string.card_due_on_day, "Tuesday"),
                listName = "Inner orbit"
            )
        )
    }
}

@PreviewLightDark
@Composable
private fun CardViewContentLoadingPreview() {
    OrbitTheme {
        PreviewContent(state = CardViewUiState.Loading)
    }
}

@PreviewLightDark
@Preview(name = "200%", fontScale = 2f)
@Composable
private fun CardViewContentErrorPreview() {
    OrbitTheme {
        PreviewContent(state = CardViewUiState.Error(listName = "Inner orbit"))
    }
}

// A list id that never parsed (a bad deep link): no list name, no Try again,
// Go home as the one action.
@PreviewLightDark
@Composable
private fun CardViewContentBadLinkPreview() {
    OrbitTheme {
        PreviewContent(state = CardViewUiState.Error(canRetry = false))
    }
}

/** Card view switches to side by side below this height when wider than tall (CARD-06). */
private val LANDSCAPE_MAX_HEIGHT = 480.dp

/** Width of the actions column in side-by-side Card view. */
private val LANDSCAPE_ACTIONS_WIDTH = 320.dp
