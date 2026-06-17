package app.orbit.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.domain.JsonProvider
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.smart.SmartListRule
import app.orbit.notify.NudgeSchedule
import app.orbit.ui.screens.lists.ListConfigBody
import app.orbit.ui.screens.lists.ListConfigContactSnapshot
import app.orbit.ui.screens.lists.ListConfigUiState
import app.orbit.ui.screens.lists.ListConfigViewModel
import app.orbit.ui.screens.lists.SettingGroup
import app.orbit.ui.theme.OrbitTheme
import java.time.LocalTime

/**
 * ONB-20 — first-list creation reusing the production List
 * Configuration screen. Wraps [ListConfigBody] inside [OnboardingScaffold]
 * so the user lands directly in the same UI they'll use forever.
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
 * creation for "Add another" happens in OrbitNavHost.
 *
 * The ViewModel is the production [ListConfigViewModel] — `listId` flows
 * through `SavedStateHandle` exactly as the production path. This means
 * the onboarding wrapper inherits Save-on-change behavior, the convert
 * dialog (irrelevant for new STATIC lists but harmless), and the
 * snackbar-event collector. The setter callbacks bind to the actual VM
 * method names (`setName`, `setRuleTemplate`, `setActiveHours`,
 * `setNotificationsEnabled`, `setSmartRuleJson`, `confirmConvert`).
 */
@Composable
fun OnboardingFirstListScreen(
    @Suppress("UNUSED_PARAMETER") listId: String,
    onDone: () -> Unit,
    onAddAnother: () -> Unit,
    vm: ListConfigViewModel = hiltViewModel(),
    permVm: OnboardingPermissionsViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.snackbarEvents.collect { event ->
                snackbarHostState.showSnackbar(
                    message = event.message,
                    actionLabel = event.actionLabel,
                )
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
        hasContactsPermission = hasContacts,
    )

    // 2026-06-09 — the list arrives from createOnboardingFirstList
    // with ruleTemplateId = null, so the Cadence picker rendered with nothing
    // selected. Pre-seed the "Keep in touch" template once the entity loads;
    // the Room write re-emits with ruleKind set, so the effect self-quiesces.
    // Kind-based: the VM resolves the seeded row via
    // RuleTemplateRepository.getByKind (no hardcoded seed id).
    LaunchedEffect(ready?.id, ready?.ruleKind) {
        if (ready != null && ready.type == ListType.STATIC && ready.ruleKind == null) {
            vm.setRuleTemplate(RuleKind.KEEP_IN_TOUCH)
        }
    }

    OnboardingScaffold(
        step = OnboardingStep.FirstList,
        onBack = null, // first list is required (E1)
        primary = OnboardingAction(
            label = "Done",
            onClick = onDone,
            enabled = canFinish,
        ),
        secondary = OnboardingAction(
            label = "Add another list",
            onClick = onAddAnother,
            enabled = canFinish,
        ),
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
            hasContactsPermission = hasContacts,
        )?.let { helper ->
            Text(
                text = helper,
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(horizontal = OrbitTheme.spacing.x4),
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x3))
        }

        ListConfigBody(
            state = ready,
            isOnboarding = true,
            snackbarHostState = snackbarHostState,
            onNameChange = vm::setName,
            onRuleTemplateChange = vm::setRuleTemplate,
            onRuleParamsChange = { params ->
                vm.setRuleParamsOverrideJson(
                    JsonProvider.json.encodeToString(RuleParams.serializer(), params),
                )
            },
            onActiveHoursChange = vm::setActiveHours,
            onAlwaysActiveToggled = { alwaysActive ->
                if (alwaysActive) {
                    vm.setActiveHours(start = null, end = null)
                } else {
                    vm.setActiveHours(start = LocalTime.of(9, 0), end = LocalTime.of(17, 0))
                }
            },
            onNotificationsToggle = vm::setNotificationsEnabled,
            // isOnboarding=true means NudgeScheduleSection is absent from the tree;
            // the callback is still required by the signature.
            onNudgeScheduleChange = {},
            onSmartRuleChange = { rule ->
                vm.setSmartRuleJson(
                    JsonProvider.json.encodeToString(SmartListRule.serializer(), rule),
                )
            },
            onConfirmConvert = vm::confirmConvert,
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
    hasContactsPermission: Boolean,
): Boolean = name.isNotBlank() && (!hasContactsPermission || memberCount >= 3)

/**
 * Helper line rendered above the list-config body. Null = nothing to say
 * (gate satisfied, contacts granted). In the denied state the helper sets
 * the expectation for the empty members picker instead of nudging toward a
 * threshold the user cannot meet.
 */
internal fun firstListHelperText(
    name: String,
    memberCount: Int,
    hasContactsPermission: Boolean,
): String? = when {
    !hasContactsPermission && name.isBlank() ->
        "Give your list a name to finish. You can add people once Orbit can see your contacts."
    !hasContactsPermission ->
        "You can add people once Orbit can see your contacts — grant access any time in Settings."
    name.isBlank() || memberCount < 3 ->
        "Add a name and pick at least 3 people to finish."
    else -> null
}

/**
 * Quiet placeholder rendered while [ListConfigUiState.Loading] — the section
 * labels the real body will use, each over a muted bar, so the screen reads
 * as settling rather than broken.
 */
@Composable
private fun FirstListLoadingSkeleton() {
    listOf("Name", "Cadence", "Active hours", "Notifications", "Members preview").forEach { title ->
        SettingGroup(title = title) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(OrbitTheme.spacing.x3)
                    .height(OrbitTheme.spacing.x6)
                    .clip(OrbitTheme.shapes.md)
                    .background(OrbitTheme.colors.bgSubtle),
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
    hasContactsPermission: Boolean = true,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val canFinish = firstListCanFinish(
        name = state.name,
        memberCount = state.members.size,
        hasContactsPermission = hasContactsPermission,
    )
    OnboardingScaffold(
        step = OnboardingStep.FirstList,
        onBack = null,
        primary = OnboardingAction(label = "Done", onClick = {}, enabled = canFinish),
        secondary = OnboardingAction(label = "Add another list", onClick = {}, enabled = canFinish),
    ) {
        firstListHelperText(
            name = state.name,
            memberCount = state.members.size,
            hasContactsPermission = hasContactsPermission,
        )?.let { helper ->
            Text(
                text = helper,
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(horizontal = OrbitTheme.spacing.x4),
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x3))
        }
        ListConfigBody(
            state = state,
            isOnboarding = true,
            snackbarHostState = snackbarHostState,
            onNameChange = {},
            onRuleTemplateChange = {},
            onRuleParamsChange = {},
            onActiveHoursChange = { _, _ -> },
            onAlwaysActiveToggled = {},
            onNotificationsToggle = {},
            onNudgeScheduleChange = {},
            onSmartRuleChange = {},
            onConfirmConvert = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun OnboardingFirstListLoadingPreview() {
    OrbitTheme {
        OnboardingScaffold(
            step = OnboardingStep.FirstList,
            onBack = null,
            primary = OnboardingAction(label = "Done", onClick = {}, enabled = false),
            secondary = OnboardingAction(label = "Add another list", onClick = {}, enabled = false),
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
                activeHoursStart = null,
                activeHoursEnd = null,
                notificationsEnabled = true,
                nudgeSchedule = null,
                members = listOf(
                    ListConfigContactSnapshot(id = 1L, displayName = "Sarah", photoUri = null),
                    ListConfigContactSnapshot(id = 2L, displayName = "Marcus", photoUri = null),
                    ListConfigContactSnapshot(id = 3L, displayName = "Priya", photoUri = null),
                ),
            ),
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
                activeHoursStart = null,
                activeHoursEnd = null,
                notificationsEnabled = true,
                nudgeSchedule = null,
                members = emptyList(),
            ),
            hasContactsPermission = false,
        )
    }
}
