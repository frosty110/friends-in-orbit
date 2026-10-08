package app.orbit.ui.screens.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.orbit.R
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.domain.JsonProvider
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.smart.SmartListRule
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.screens.lists.ListConfigBody
import app.orbit.ui.screens.lists.ListConfigContactSnapshot
import app.orbit.ui.screens.lists.ListConfigUiState
import app.orbit.ui.screens.lists.ListConfigViewModel
import app.orbit.ui.screens.lists.SettingGroup
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.asString

/**
 * ONB-20: first-list creation reusing the production List
 * Configuration screen. Wraps [ListConfigBody] inside [OnboardingScaffold]
 * so the user lands directly in the same UI they'll use forever.
 *
 * Non-Ready states (see [firstListFallback]): Loading keeps the skeleton;
 * Error says the list could not be read and offers Try again; NotFound (the
 * list was deleted or archived between steps) offers "Start again", which
 * [onStartAgain] routes back to the Sync step, whose Continue reads a missing
 * list as no list and sets up a fresh one (README, Mid-flow resume). Until
 * 2026-10-06 both rendered the loading skeleton under two disabled CTAs with
 * no back arrow: a screen that said "loading" for ever (G4).
 *
 * Activation gate (E5 / ONB-24): with contacts
 * access granted, the primary "Done" CTA is enabled only when the list has
 * a non-blank name AND ≥3 members; with contacts access denied, a non-blank
 * name is enough (see [firstListCanFinish]). The helper text above the CTA
 * names the threshold without shame — or, in the denied state, says what to
 * expect of the empty picker.
 *
 * Add-another (ONB-09): the secondary CTA "Add another list" exits this
 * screen by navigating to a freshly-created list and re-entering this
 * route — the next press of Done finishes onboarding. The actual list
 * creation for "Add another" happens in OrbitNavHost, via
 * [OnboardingListStarter.startAnother].
 *
 * The ViewModel is the production [ListConfigViewModel] — `listId` flows
 * through `SavedStateHandle` exactly as the production path. This means
 * the onboarding wrapper inherits Save-on-change behavior, the convert
 * dialog (irrelevant for new STATIC lists but harmless), and the
 * snackbar-event collector. The setter callbacks bind to the actual VM
 * method names (`setName`, `setIntervalHours`,
 * `setNotificationsEnabled`, `setSmartRuleJson`, `confirmConvert`), and the
 * first read seeds the template with `setRuleTemplate`.
 */
@Composable
fun OnboardingFirstListScreen(
    @Suppress("UNUSED_PARAMETER") listId: String,
    onDone: () -> Unit,
    onAddAnother: () -> Unit,
    onAddContacts: () -> Unit,
    // NotFound's "Start again": pop back to the Sync step. Defaulted so the
    // nav graph can wire it in its own change.
    onStartAgain: () -> Unit = {},
    vm: ListConfigViewModel = hiltViewModel(),
    permVm: OnboardingPermissionsViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Snackbar copy is UiText (strings_lists.xml); resolved when shown.
    val context = LocalContext.current
    // Same handling as the production ListConfigScreen: Short, and an Undo
    // tap (member remove) pops the VM's UndoStack. The host is passed to
    // OnboardingScaffold below; without one on screen, the first showSnackbar
    // suspended forever, so "Removed {name}" never appeared and every later
    // message stalled behind it.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.snackbarEvents.collect { event ->
                val result = snackbarHostState.showSnackbar(
                    message = event.message.asString(context),
                    actionLabel = event.actionLabel?.asString(context),
                    duration = SnackbarDuration.Short,
                    withDismissAction = false
                )
                if (result == SnackbarResult.ActionPerformed) vm.onUndo()
            }
        }
    }

    // #15 (2026-06-09) — the contacts-denied path promised "You can still
    // create lists" (OnboardingPermContactsScreen deniedNote) but this step
    // gated Done on >= 3 members with no back and no skip: an empty picker +
    // a permanently disabled CTA = hard-stuck. The gate now relaxes when
    // READ_CONTACTS is denied (see firstListCanFinish). Same permission
    // plumbing as the rationale screens (OnboardingPermissionsViewModel);
    // refreshed on resume so granting access in Settings mid-flow re-tightens
    // the gate and fires the contacts ingest (computePermSnapshot flip).
    val permState by permVm.uiState.collectAsStateWithLifecycle()
    val hasContacts = (permState as? OnboardingPermissionsUiState.Ready)?.hasContacts ?: false
    LifecycleResumeEffect(key1 = Unit, lifecycleOwner = lifecycleOwner) {
        permVm.onRefresh()
        onPauseOrDispose { }
    }

    val ready = state as? ListConfigUiState.Ready
    val canFinish = ready != null && firstListCanFinish(
        name = ready.name,
        memberCount = ready.members.size,
        hasContactsPermission = hasContacts
    )

    // 2026-06-09: the list arrives from OnboardingListStarter
    // with ruleTemplateId = null, so the Cadence picker rendered with nothing
    // selected. Pre-seed the "Keep in touch" template once the entity loads;
    // the Room write re-emits with ruleKind set, so the effect self-quiesces.
    // Kind-based: the VM resolves the seeded row via
    // RuleTemplateRepository.getByKind (no hardcoded seed id). The picker is
    // gone (LIST-30), but a list with no template still surfaces no one, and
    // How often reads the template's interval, so the seed stays.
    LaunchedEffect(ready?.id, ready?.ruleKind) {
        if (ready != null && ready.type == ListType.STATIC && ready.ruleKind == null) {
            vm.setRuleTemplate(RuleKind.KEEP_IN_TOUCH)
        }
    }

    val fallback = firstListFallback(state)
    if (fallback != null) {
        FirstListFallbackContent(
            fallback = fallback,
            onAction = when (fallback.action) {
                FirstListFallbackAction.Retry -> vm::onRetry
                FirstListFallbackAction.StartAgain -> onStartAgain
            }
        )
        return
    }

    OnboardingScaffold(
        title = stringResource(R.string.onb_first_list_title),
        step = OnboardingStep.FirstList,
        onBack = null, // first list is required (E1)
        primary = OnboardingAction(
            label = stringResource(R.string.components_action_done),
            onClick = onDone,
            enabled = canFinish
        ),
        secondary = OnboardingAction(
            label = stringResource(R.string.onb_first_list_add_another),
            onClick = onAddAnother,
            enabled = canFinish
        ),
        snackbarHostState = snackbarHostState
    ) {
        if (ready == null) {
            // 2026-06-09 — arriving from the preview commit can
            // leave this state Loading for a few seconds while the new list and
            // memberships land in Room. Render the section labels with quiet
            // placeholders instead of a blank column under disabled CTAs.
            FirstListLoadingSkeleton()
            return@OnboardingScaffold
        }

        // Helper above the body. Sentence case, no shame, no "You need to"
        // (UI-SPEC §"Helper text under disabled CTA"). With contacts denied
        // the helper instead sets the expectation for the empty picker (#15).
        firstListHelperText(
            name = ready.name,
            memberCount = ready.members.size,
            hasContactsPermission = hasContacts
        )?.let { helper ->
            Text(
                text = stringResource(helper),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(horizontal = OrbitTheme.spacing.x4)
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x3))
        }

        ListConfigBody(
            state = ready,
            isOnboarding = true,
            snackbarHostState = snackbarHostState,
            onNameChange = vm::setName,
            // LIST-30: the same How often as List settings; the rhythm
            // choice went from both screens at once. Time of day went from
            // both too (LIST-25): the step says the nudge's days and time
            // under Nudges, and they change in the list's settings.
            onIntervalChange = vm::setIntervalHours,
            onNotificationsToggle = vm::setNotificationsEnabled,
            // isOnboarding=true means NudgeScheduleSection is absent from the tree;
            // the callback is still required by the signature.
            onNudgeScheduleChange = {},
            onSmartRuleChange = { rule ->
                vm.setSmartRuleJson(
                    JsonProvider.json.encodeToString(SmartListRule.serializer(), rule)
                )
            },
            onConfirmConvert = vm::confirmConvert,
            // 2026-07-03 — the onboarding wrapper previously omitted both member
            // callbacks, so "add contacts" and per-member remove were dead until
            // the list was reopened from Lists Manager. Wire them to the same VM
            // path production uses; onAddContacts navigates to the picker.
            onRemoveMember = vm::onRemoveMember,
            onAddContacts = onAddContacts
        )
    }
}

/**
 * #15 (2026-06-09) — activation gate for the first-list Done CTA.
 *
 * With READ_CONTACTS granted: non-blank name AND >= 3 members (E5 / ONB-24).
 * With READ_CONTACTS denied: non-blank name only — the picker is empty and
 * ContactsIngestWorker never ran, so a member threshold would hard-stick the
 * user on a step with no back and no skip, contradicting the rationale
 * screen's "You can still create lists" promise.
 */
internal fun firstListCanFinish(
    name: String,
    memberCount: Int,
    hasContactsPermission: Boolean
): Boolean = name.isNotBlank() && (!hasContactsPermission || memberCount >= 3)

/** What a non-Ready, non-Loading first-list state offers the user. */
internal enum class FirstListFallbackAction { Retry, StartAgain }

/**
 * The message and the one action for a state that cannot show the list:
 * [ListConfigUiState.Error] and [ListConfigUiState.NotFound]. Null for Loading
 * (the skeleton) and Ready (the body). Pure, so `OnboardingFirstListGateTest`
 * can pin that no fallback branch leaves the user without a visible action,
 * the dead end this screen had until 2026-10-06.
 */
internal fun firstListFallback(state: ListConfigUiState): FirstListFallback? = when (state) {
    ListConfigUiState.Error -> FirstListFallback(
        titleRes = R.string.onb_first_list_error_title,
        bodyRes = R.string.components_error_body,
        actionLabelRes = R.string.components_error_retry,
        action = FirstListFallbackAction.Retry
    )
    ListConfigUiState.NotFound -> FirstListFallback(
        titleRes = R.string.onb_first_list_not_found_title,
        bodyRes = R.string.onb_first_list_not_found_body,
        actionLabelRes = R.string.onb_first_list_start_again,
        action = FirstListFallbackAction.StartAgain
    )
    ListConfigUiState.Loading, is ListConfigUiState.Ready -> null
}

internal data class FirstListFallback(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    @StringRes val actionLabelRes: Int,
    val action: FirstListFallbackAction
)

/**
 * Error and NotFound: the footer's Primary is the fallback's one action (the
 * screen's single accent); the message carries the words. Not scrollable:
 * OrbitScreenMessage scrolls itself at large text, and a scroll nested in the
 * scaffold's scroll is the F-1 hazard below.
 */
@Composable
private fun FirstListFallbackContent(fallback: FirstListFallback, onAction: () -> Unit) {
    OnboardingScaffold(
        title = stringResource(fallback.titleRes),
        step = OnboardingStep.FirstList,
        onBack = null,
        primary = OnboardingAction(
            label = stringResource(fallback.actionLabelRes),
            onClick = onAction
        ),
        scrollable = false
    ) {
        OrbitScreenMessage(
            icon = "warning-circle",
            title = stringResource(fallback.titleRes),
            body = stringResource(fallback.bodyRes)
        )
    }
}

/**
 * Helper line rendered above the list-config body, as a string resource id.
 * Null = nothing to say (gate satisfied, contacts granted). In the denied
 * state the helper sets the expectation for the empty members picker instead
 * of nudging toward a threshold the user cannot meet.
 */
@StringRes
internal fun firstListHelperText(
    name: String,
    memberCount: Int,
    hasContactsPermission: Boolean
): Int? = when {
    !hasContactsPermission && name.isBlank() -> R.string.onb_first_list_helper_no_contacts_no_name
    !hasContactsPermission -> R.string.onb_first_list_helper_no_contacts
    name.isBlank() || memberCount < 3 -> R.string.onb_first_list_helper_threshold
    else -> null
}

/**
 * Quiet placeholder rendered while [ListConfigUiState.Loading]: the section
 * labels the real body will use (the same string resources), each over a
 * muted bar, so the screen reads as settling rather than broken.
 */
@Composable
private fun FirstListLoadingSkeleton() {
    listOf(
        R.string.lists_section_name,
        R.string.lists_section_how_often,
        R.string.lists_section_nudges,
        R.string.lists_section_members,
    ).forEach { title ->
        SettingGroup(title = stringResource(title)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(OrbitTheme.spacing.x3)
                    .height(OrbitTheme.spacing.x6)
                    .clip(OrbitTheme.shapes.md)
                    .background(OrbitTheme.colors.bgSubtle)
            )
        }
    }
}

/**
 * F-1 fix (2026-04-30 hot-fix-260430-hs4) — preview-only host that exercises
 * the layout pass without [hiltViewModel]. Mirrors the
 * [OnboardingPermContactsScreen] preview pattern (Pitfall 4: previews cannot
 * resolve `hiltViewModel()`). The point of this preview is the layout pass:
 * if a future change re-introduces a nested-scroll under
 * [OnboardingScaffold]'s already-scrolling content slot, the IDE preview
 * pane fails — the missing safety net that would have caught F-1.
 */
@Composable
private fun OnboardingFirstListScreenPreviewBody(
    state: ListConfigUiState.Ready,
    hasContactsPermission: Boolean = true
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val canFinish = firstListCanFinish(
        name = state.name,
        memberCount = state.members.size,
        hasContactsPermission = hasContactsPermission
    )
    OnboardingScaffold(
        title = stringResource(R.string.onb_first_list_title),
        step = OnboardingStep.FirstList,
        onBack = null,
        primary = OnboardingAction(
            label = stringResource(R.string.components_action_done),
            onClick = {},
            enabled = canFinish,
        ),
        secondary = OnboardingAction(
            label = stringResource(R.string.onb_first_list_add_another),
            onClick = {},
            enabled = canFinish,
        )
    ) {
        firstListHelperText(
            name = state.name,
            memberCount = state.members.size,
            hasContactsPermission = hasContactsPermission
        )?.let { helper ->
            Text(
                text = stringResource(helper),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(horizontal = OrbitTheme.spacing.x4)
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x3))
        }
        ListConfigBody(
            state = state,
            isOnboarding = true,
            snackbarHostState = snackbarHostState,
            onNameChange = {},
            onIntervalChange = {},
            onNotificationsToggle = {},
            onNudgeScheduleChange = {},
            onSmartRuleChange = {},
            onConfirmConvert = {}
        )
    }
}

@PreviewLightDark
@Composable
private fun OnboardingFirstListLoadingPreview() {
    OrbitTheme {
        OnboardingScaffold(
            title = stringResource(R.string.onb_first_list_title),
            step = OnboardingStep.FirstList,
            onBack = null,
            primary = OnboardingAction(
                label = stringResource(R.string.components_action_done),
                onClick = {},
                enabled = false,
            ),
            secondary = OnboardingAction(
                label = stringResource(R.string.onb_first_list_add_another),
                onClick = {},
                enabled = false,
            )
        ) {
            FirstListLoadingSkeleton()
        }
    }
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun OnboardingFirstListScreenPreview() {
    OrbitTheme {
        OnboardingFirstListScreenPreviewBody(
            state = ListConfigUiState.Ready(
                id = 0L,
                name = "In touch",
                type = ListType.STATIC,
                ruleKind = RuleKind.KEEP_IN_TOUCH,
                ruleParams = RuleParams.KeepInTouch(cooldownMinHours = 168),
                smartRule = null,
                notificationsEnabled = true,
                nudgeSchedule = null,
                members = listOf(
                    ListConfigContactSnapshot(id = 1L, displayName = "Sarah", photoUri = null),
                    ListConfigContactSnapshot(id = 2L, displayName = "Marcus", photoUri = null),
                    ListConfigContactSnapshot(id = 3L, displayName = "Priya", photoUri = null)
                )
            )
        )
    }
}

// #15 — contacts denied: empty picker, relaxed gate (Done enabled on name
// alone), denied-state helper above the body.
@PreviewLightDark
@Composable
private fun OnboardingFirstListContactsDeniedPreview() {
    OrbitTheme {
        OnboardingFirstListScreenPreviewBody(
            state = ListConfigUiState.Ready(
                id = 0L,
                name = "In touch",
                type = ListType.STATIC,
                ruleKind = RuleKind.KEEP_IN_TOUCH,
                ruleParams = RuleParams.KeepInTouch(cooldownMinHours = 168),
                smartRule = null,
                notificationsEnabled = true,
                nudgeSchedule = null,
                members = emptyList()
            ),
            hasContactsPermission = false
        )
    }
}

// The list could not be read: Try again is the footer's one accent.
@PreviewLightDark
@Composable
private fun OnboardingFirstListErrorPreview() {
    OrbitTheme {
        FirstListFallbackContent(fallback = firstListFallback(ListConfigUiState.Error)!!, onAction = {})
    }
}

// The list is gone: "Start again" returns to the Sync step for a fresh one.
@PreviewLightDark
@Composable
private fun OnboardingFirstListNotFoundPreview() {
    OrbitTheme {
        FirstListFallbackContent(fallback = firstListFallback(ListConfigUiState.NotFound)!!, onAction = {})
    }
}
