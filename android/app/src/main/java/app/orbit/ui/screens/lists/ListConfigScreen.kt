package app.orbit.ui.screens.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.orbit.R
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.domain.JsonProvider
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.smart.SmartListRule
import app.orbit.notify.NudgeSchedule
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitAppBarTextAction
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.components.OrbitSwitch
import app.orbit.ui.components.SectionLabel
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.orbitCardShadow
import app.orbit.ui.util.asString
import java.time.LocalTime

/**
 * List Configuration screen.
 *
 * Two-layer composable: outer wires Hilt VM + `collectAsStateWithLifecycle()`
 * (`lifecycle-runtime-compose` 2.8.7 is in the catalog); inner is stateless
 * and composes the three editors
 * ([RuleTemplatePicker], [ActiveHoursEditor], [SmartRuleEditor]) plus
 * [MembersPreview].
 *
 * Save-on-change semantics — every control commits via a VM setter. There is
 * no app-bar commit chip and no archive or delete here: both live on the list's
 * menus (Lists Manager's row menu and archived section, Home's long-press),
 * each with Undo, so this screen only edits.
 *
 * SMART vs STATIC sectioning:
 *  - STATIC: Cadence (RuleTemplatePicker) → Interval (slider, KeepInTouch
 *    cooldown override) → Active hours → Notifications → Members preview.
 *  - SMART: Active hours → Notifications → Smart rule (SmartRuleEditor) →
 *    Members preview → Convert button (with confirmation dialog).
 *
 * `listId` arrives as a String for nav-graph compatibility; the VM reads it
 * from [androidx.lifecycle.SavedStateHandle].
 *
 * `onSave` is the screen's "I'm finished here" exit — an app-bar **Done** and
 * a Done button at the foot of the form, both popping back to wherever the
 * user came from (Lists Manager for a list they just created). Nothing is
 * committed by it: the screen is still save-on-change, so Done only closes.
 * Before 2026-08-15 the parameter was unused and the back arrow was the only
 * way out, which read as "no way to finish" at the end of the create flow.
 */
@Composable
fun ListConfigScreen(
    listId: String,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onAddContacts: (String) -> Unit,
    vm: ListConfigViewModel = hiltViewModel(),
) {
    @Suppress("UNUSED_VARIABLE") val listIdForKey = listId
    // Lifecycle-aware state collection; pauses re-emission while
    // the screen is below STARTED so backgrounded re-emissions don't drive
    // recomposition.
    val state by vm.uiState.collectAsStateWithLifecycle()

    // H4 fix: host state is hoisted to the outer composable and handed to
    // [ListConfigContent], so every snackbar on this screen (a failed save, a
    // removed member's Undo, "This is now a regular list.") goes through the
    // one collector below. The VM emits all of them; the convert success used
    // to be shown by the body before the write resolved, whether or not it
    // succeeded.
    val snackbarHostState = remember { SnackbarHostState() }
    // Wrap the SharedFlow collector in repeatOnLifecycle(STARTED)
    // so the collector stops while the screen is backgrounded. SnackbarEvent
    // is a tryEmit/replay=0 SharedFlow; events emitted while STOPPED are
    // dropped deliberately (a confirmation is local UI feedback, not a
    // critical journal). lifecycleOwner is captured once here and the
    // LaunchedEffect re-keys on it so a host swap re-establishes the
    // collector.
    val lifecycleOwner = LocalLifecycleOwner.current
    // Snackbar copy is UiText (strings_lists.xml); resolved when shown.
    val context = LocalContext.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.snackbarEvents.collect { event ->
                // F-6 — when an event carries an action label (today only the
                // member-remove "Undo" path), wire the action tap to the VM's
                // UndoStack pop. Other emitters (the failure surface, convert
                // success) emit without an action label and short-circuit.
                val result = snackbarHostState.showSnackbar(
                    message = event.message.asString(context),
                    actionLabel = event.actionLabel?.asString(context),
                    duration = SnackbarDuration.Short,
                    withDismissAction = false,
                )
                if (result == SnackbarResult.ActionPerformed) vm.onUndo()
            }
        }
    }

    ListConfigContent(
        onRetry = vm::onRetry,
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onDone = onSave,
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
        onNudgeScheduleChange = vm::onNudgeScheduleChange,
        onSmartRuleChange = { rule ->
            vm.setSmartRuleJson(
                JsonProvider.json.encodeToString(SmartListRule.serializer(), rule),
            )
        },
        onConfirmConvert = { vm.confirmConvert() },
        onRemoveMember = vm::onRemoveMember,
        onAddContacts = { onAddContacts(listId) },
    )
}

@Composable
private fun ListConfigContent(
    onRetry: () -> Unit = {},
    state: ListConfigUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onNameChange: (String) -> Unit,
    onRuleTemplateChange: (RuleKind) -> Unit,
    onRuleParamsChange: (RuleParams) -> Unit,
    onActiveHoursChange: (LocalTime?, LocalTime?) -> Unit,
    onAlwaysActiveToggled: (Boolean) -> Unit,
    onNotificationsToggle: (Boolean) -> Unit,
    onNudgeScheduleChange: (NudgeSchedule) -> Unit,
    onSmartRuleChange: (SmartListRule) -> Unit,
    onConfirmConvert: () -> Unit,
    onRemoveMember: (Long, String) -> Unit,
    onAddContacts: () -> Unit,
) {
    val fallbackTitle = stringResource(R.string.lists_config_title_fallback)
    val curtain = LocalPrivacyCurtain.current
    val curtainList = stringResource(R.string.components_curtain_list)
    val title = when (state) {
        // PRIV-03: the list's name masks as "List" under the curtain.
        is ListConfigUiState.Ready -> if (curtain) curtainList else state.name.ifBlank { fallbackTitle }
        ListConfigUiState.NotFound, ListConfigUiState.Error -> fallbackTitle
        ListConfigUiState.Loading -> ""
    }

    OrbitScreen {
        OrbitAppBar(
            title = title,
            leading = {
                OrbitIconButton("arrow-left", onBack, contentDescription = stringResource(R.string.components_action_back))
            },
            // Only offered once there is a list to be done with — Loading and
            // NotFound have nothing to finish. TalkBack reads the visible
            // "Done": an override used to say "back to your lists", which was
            // wrong from Home's and Card view's way in (Done pops to wherever
            // the screen was opened from).
            trailing = if (state is ListConfigUiState.Ready) {
                {
                    OrbitAppBarTextAction(
                        text = stringResource(R.string.components_action_done),
                        onClick = onDone,
                    )
                }
            } else null,
        )

        // A list that is gone (deleted elsewhere, a stale link) says so and
        // offers the way out, as Contact detail's NotFound does, instead of
        // one centred line under a bare back arrow (rubric G4: no dead ends).
        // Go back is the only thing to do here, so it takes the accent.
        if (state is ListConfigUiState.NotFound) {
            OrbitScreenMessage(
                icon = "list-bullets",
                title = stringResource(R.string.lists_config_not_found),
                body = stringResource(R.string.lists_config_not_found_body),
                actionLabel = stringResource(R.string.lists_config_go_back),
                onAction = onBack,
                actionVariant = OrbitButtonVariant.Primary,
            )
            return@OrbitScreen
        }

        if (state is ListConfigUiState.Error) {
            OrbitScreenMessage(
                icon = "warning-circle",
                title = stringResource(R.string.lists_config_error_title),
                body = stringResource(R.string.components_error_body),
                actionLabel = stringResource(R.string.components_error_retry),
                onAction = onRetry,
            )
            return@OrbitScreen
        }

        if (state !is ListConfigUiState.Ready) return@OrbitScreen

        // ONB-20 — body is delegated to the shared ListConfigBody so
        // both the production path (this screen) and the onboarding wrapper
        // (OnboardingFirstListScreen) render the same controls. The
        // production path passes `isOnboarding = false`, which renders the
        // name as an inline rename row (F-12, ListNameRenameRow) instead of
        // onboarding's always-open field; both commit through onNameChange,
        // wired to `vm::setName` at the entry point above.
        ListConfigBody(
            state = state,
            isOnboarding = false,
            snackbarHostState = snackbarHostState,
            // Foot-of-form Done, so the user who has just scrolled through
            // every setting doesn't have to travel back up to the app bar.
            onDone = onDone,
            onNameChange = onNameChange,
            onRuleTemplateChange = onRuleTemplateChange,
            onRuleParamsChange = onRuleParamsChange,
            onActiveHoursChange = onActiveHoursChange,
            onAlwaysActiveToggled = onAlwaysActiveToggled,
            onNotificationsToggle = onNotificationsToggle,
            onNudgeScheduleChange = onNudgeScheduleChange,
            onSmartRuleChange = onSmartRuleChange,
            onConfirmConvert = onConfirmConvert,
            onRemoveMember = onRemoveMember,
            onAddContacts = onAddContacts,
        )
    }

    // Touch the parameter so the lint suppression at the screen entry point
    // remains harmless if a future refactor surfaces it.
    LaunchedEffect(state) { /* no-op; presence reserved for save-confirmation snackbar */ }
}

// ──────────────────────────────────────────────────────────────────────────
// Shared section composables — re-exported for SettingsScreen consumption.
// ──────────────────────────────────────────────────────────────────────────

@Composable
internal fun SettingGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = OrbitTheme.spacing.x5)) {
        SectionLabel(
            text = title,
            modifier = Modifier.padding(horizontal = OrbitTheme.spacing.x2, vertical = OrbitTheme.spacing.x3),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .orbitCardShadow(OrbitTheme.shapes.lg, OrbitTheme.colors.isDark)
                .clip(OrbitTheme.shapes.lg)
                .background(OrbitTheme.colors.surface),
        ) { content() }
    }
}

@Composable
internal fun ToggleRow(
    label: String,
    sub: String?,
    value: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val labelColor = if (enabled) OrbitTheme.colors.fg else OrbitTheme.colors.fgMuted
    val subColor = if (enabled) OrbitTheme.colors.fgMuted else OrbitTheme.colors.fgSubtle
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // The row is the switch: one TalkBack stop with the label and an
            // on/off state, and the whole row is the touch target.
            .toggleable(
                value = value,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.rowY),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = OrbitTheme.type.body.copy(color = labelColor),
            )
            if (sub != null) {
                Text(
                    text = sub,
                    style = OrbitTheme.type.meta.copy(color = subColor),
                    modifier = Modifier.padding(top = OrbitTheme.spacing.hair),
                )
            }
        }
        OrbitSwitch(checked = value, onCheckedChange = null, enabled = enabled)
    }
}

// region Previews

@Preview(name = "ListConfigScreen — STATIC ready, light", showBackground = true)
@Composable
private fun ListConfigScreenStaticReadyLightPreview() {
    OrbitTheme(darkTheme = false) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            ListConfigContent(
                state = ListConfigUiState.Ready(
                    id = 1L,
                    name = "Inner orbit",
                    type = ListType.STATIC,
                    ruleKind = RuleKind.KEEP_IN_TOUCH,
                    ruleParams = RuleParams.KeepInTouch(),
                    smartRule = null,
                    activeHoursStart = null,
                    activeHoursEnd = null,
                    notificationsEnabled = true,
                    nudgeSchedule = null,
                    members = listOf(
                        ListConfigContactSnapshot(1L, "Alex Rivera", null),
                        ListConfigContactSnapshot(2L, "Sam Patel", null),
                    ),
                ),
                snackbarHostState = remember { SnackbarHostState() },
                onBack = {},
                onDone = {},
                onNameChange = {},
                onRuleTemplateChange = {},
                onRuleParamsChange = {},
                onActiveHoursChange = { _, _ -> },
                onAlwaysActiveToggled = {},
                onNotificationsToggle = {},
                onNudgeScheduleChange = {},
                onSmartRuleChange = {},
                onConfirmConvert = {},
                onRemoveMember = { _, _ -> },
                onAddContacts = {},
            )
        }
    }
}

// Rule-correctness fix — exercises the quiet rhythm note that replaces the
// interval slider when the selected template has no user-facing tunables.
@Preview(name = "ListConfigScreen — STATIC late night, light", showBackground = true)
@Composable
private fun ListConfigScreenStaticLateNightPreview() {
    OrbitTheme(darkTheme = false) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            ListConfigContent(
                state = ListConfigUiState.Ready(
                    id = 3L,
                    name = "Late night",
                    type = ListType.STATIC,
                    ruleKind = RuleKind.LATE_NIGHT,
                    ruleParams = RuleParams.LateNight(),
                    smartRule = null,
                    activeHoursStart = null,
                    activeHoursEnd = null,
                    notificationsEnabled = true,
                    nudgeSchedule = null,
                    members = listOf(
                        ListConfigContactSnapshot(1L, "Alex Rivera", null),
                    ),
                ),
                snackbarHostState = remember { SnackbarHostState() },
                onBack = {},
                onDone = {},
                onNameChange = {},
                onRuleTemplateChange = {},
                onRuleParamsChange = {},
                onActiveHoursChange = { _, _ -> },
                onAlwaysActiveToggled = {},
                onNotificationsToggle = {},
                onNudgeScheduleChange = {},
                onSmartRuleChange = {},
                onConfirmConvert = {},
                onRemoveMember = { _, _ -> },
                onAddContacts = {},
            )
        }
    }
}

@Preview(name = "ListConfigScreen — SMART ready, dark", showBackground = true)
@Composable
private fun ListConfigScreenSmartReadyDarkPreview() {
    OrbitTheme(darkTheme = true) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            ListConfigContent(
                state = ListConfigUiState.Ready(
                    id = 2L,
                    name = "Recently added, not called",
                    type = ListType.SMART,
                    ruleKind = null,
                    ruleParams = null,
                    smartRule = SmartListRule.RecentlyAddedNotCalled(daysWindow = 30),
                    activeHoursStart = null,
                    activeHoursEnd = null,
                    notificationsEnabled = true,
                    nudgeSchedule = null,
                    members = emptyList(),
                ),
                snackbarHostState = remember { SnackbarHostState() },
                onBack = {},
                onDone = {},
                onNameChange = {},
                onRuleTemplateChange = {},
                onRuleParamsChange = {},
                onActiveHoursChange = { _, _ -> },
                onAlwaysActiveToggled = {},
                onNotificationsToggle = {},
                onNudgeScheduleChange = {},
                onSmartRuleChange = {},
                onConfirmConvert = {},
                onRemoveMember = { _, _ -> },
                onAddContacts = {},
            )
        }
    }
}

// Combined preview for the stateless ListConfigContent
// (THEME-04 / THEME-05 — D-06). Renders at light/dark and 6 font-scale stops.
@PreviewLightDark
@PreviewFontScale
@Composable
private fun ListConfigContentPreview() {
    ListConfigPreviewHost(
        ListConfigUiState.Ready(
            id = 1L,
            name = "Inner orbit",
            type = ListType.STATIC,
            ruleKind = RuleKind.KEEP_IN_TOUCH,
            ruleParams = RuleParams.KeepInTouch(),
            smartRule = null,
            activeHoursStart = null,
            activeHoursEnd = null,
            notificationsEnabled = true,
            nudgeSchedule = null,
            members = listOf(
                ListConfigContactSnapshot(1L, "Alex Rivera", null),
                ListConfigContactSnapshot(2L, "Sam Patel", null),
            ),
        ),
    )
}

// One preview per remaining state (gallery convention: each state is a cell
// the a11y and curtain audits can see). Error, NotFound and a list with
// active hours set had no preview until 2026-10-06, so the accent count with
// the hours bar on screen (LIST-21) had never been looked at.
@Composable
private fun ListConfigPreviewHost(state: ListConfigUiState) {
    OrbitTheme {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            ListConfigContent(
                state = state,
                snackbarHostState = remember { SnackbarHostState() },
                onBack = {},
                onDone = {},
                onNameChange = {},
                onRuleTemplateChange = {},
                onRuleParamsChange = {},
                onActiveHoursChange = { _, _ -> },
                onAlwaysActiveToggled = {},
                onNotificationsToggle = {},
                onNudgeScheduleChange = {},
                onSmartRuleChange = {},
                onConfirmConvert = {},
                onRemoveMember = { _, _ -> },
                onAddContacts = {},
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun ListConfigActiveHoursSetPreview() {
    ListConfigPreviewHost(
        ListConfigUiState.Ready(
            id = 4L,
            name = "People who ground me",
            type = ListType.STATIC,
            ruleKind = RuleKind.KEEP_IN_TOUCH,
            ruleParams = RuleParams.KeepInTouch().withIntervalHours(14 * 24),
            smartRule = null,
            activeHoursStart = LocalTime.of(21, 0),
            activeHoursEnd = LocalTime.of(2, 0),
            notificationsEnabled = false,
            nudgeSchedule = null,
            members = listOf(ListConfigContactSnapshot(3L, "Jordan Lee", null)),
        ),
    )
}

@PreviewLightDark
@Composable
private fun ListConfigErrorPreview() {
    ListConfigPreviewHost(ListConfigUiState.Error)
}

@PreviewLightDark
@Composable
private fun ListConfigNotFoundPreview() {
    ListConfigPreviewHost(ListConfigUiState.NotFound)
}

// endregion
