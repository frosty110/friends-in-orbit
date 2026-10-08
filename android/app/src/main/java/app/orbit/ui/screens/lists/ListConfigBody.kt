package app.orbit.ui.screens.lists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import app.orbit.R
import app.orbit.data.entity.ListType
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.smart.SmartListRule
import app.orbit.notify.NudgeSchedule
import app.orbit.ui.components.CurtainMask
import app.orbit.ui.components.IntervalDaysPicker
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitSnackbarHost
import app.orbit.ui.components.OrbitTextField
import app.orbit.ui.theme.OrbitTheme

/**
 * ONB-20 — production-and-onboarding body for List Configuration.
 *
 * Hosts the onboarding-only name field (see the `isOnboarding` gate), How
 * often (LIST-30), Time of day (LIST-25), Nudges, the nudge schedule, the
 * optional Smart-rule editor, the People section (LIST-27) and the
 * convert-to-static action. The chrome (OrbitScreen + OrbitAppBar) lives in
 * the caller: production [ListConfigScreen] for the standard nav, whose
 * title is also the rename control (LIST-26), and
 * [app.orbit.ui.screens.onboarding.OnboardingFirstListScreen] for the
 * onboarding first-list step (which renders OnboardingScaffold instead of
 * the standard app bar).
 *
 * `isOnboarding` toggles two behaviors:
 *   - The onboarding branch drops the inner `verticalScroll` + `fillMaxSize`
 *     because [app.orbit.ui.screens.onboarding.OnboardingScaffold] already
 *     wraps `content` in a verticalScroll Column with infinite max height.
 *     Nesting a second scroll under an infinite-height parent triggers
 *     `IllegalStateException: "Vertically scrollable component was measured
 *     with an infinity maximum height constraints"` (F-1, 2026-04-30 UAT).
 *     The onboarding branch instead uses `fillMaxWidth()` and lets the
 *     OnboardingScaffold scroll container be the only scroll parent.
 *   - An [OrbitTextField] for the list name renders at the top of the
 *     body (BLOCKER 1 / ONB-11). Production has no Name section since
 *     LIST-26: the app bar's title is the name and renames in place. Make
 *     your first list keeps its field (that flow was not part of the review).
 *
 * Save-on-change semantics — every control commits via a VM setter (LIST-04);
 * the body never holds editable form state of its own beyond a typing buffer.
 * The onboarding name field emits to `onNameChange` on every keystroke (the VM
 * coalesces inside `runMutation`; v1 ships with no debounce). Confirmations
 * ("This is now a regular list.") come from the ViewModel through the
 * screen's one snackbar collector, so they say only what was saved.
 */
@Composable
internal fun ListConfigBody(
    state: ListConfigUiState.Ready,
    isOnboarding: Boolean,
    snackbarHostState: SnackbarHostState,
    // Non-null on the production path only: onboarding has its own
    // "Continue" in [OnboardingScaffold] and must not grow a second exit.
    // List settings passes null while its title is being renamed (LIST-26).
    onDone: (() -> Unit)? = null,
    // Onboarding's name field only; production renames from its title.
    onNameChange: (String) -> Unit = {},
    // LIST-30: hours, from How often. The ViewModel decides what a move does
    // to the list (setIntervalHours); the body only reports the number.
    onIntervalChange: (Int) -> Unit,
    onTimeOfDayChange: (DayPart) -> Unit,
    onNotificationsToggle: (Boolean) -> Unit,
    onNudgeScheduleChange: (NudgeSchedule) -> Unit,
    onSmartRuleChange: (SmartListRule) -> Unit,
    onConfirmConvert: () -> Unit,
    onRemoveMember: (Long, String) -> Unit = { _, _ -> },
    onAddContacts: () -> Unit = {}
) {
    var showConvertDialog by rememberSaveable { mutableStateOf(false) }

    if (isOnboarding) {
        // F-1 fix (2026-04-30 hot-fix-260430-hs4): drop the inner
        // verticalScroll + fillMaxSize. OnboardingScaffold wraps `content`
        // in a verticalScroll Column with infinite max height; a second
        // scroll under an infinite-height parent crashes the layout pass.
        // The onboarding branch lets the scaffold be the only scroll parent.
        // The keyboard is handled above and below this (ONB-21): OrbitScreen
        // pads for it, and each OrbitTextField keeps itself above it. An
        // imePadding() here did nothing, because OrbitScreen had already
        // consumed the inset.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x1)
                .padding(bottom = OrbitTheme.spacing.x7)
        ) {
            ListConfigBodySections(
                state = state,
                isOnboarding = true,
                onDone = null,
                onNameChange = onNameChange,
                onIntervalChange = onIntervalChange,
                onTimeOfDayChange = onTimeOfDayChange,
                onNotificationsToggle = onNotificationsToggle,
                onNudgeScheduleChange = onNudgeScheduleChange,
                onSmartRuleChange = onSmartRuleChange,
                onShowConvertDialog = { showConvertDialog = true },
                onRemoveMember = onRemoveMember,
                onAddContacts = onAddContacts
            )
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            val scrollModifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x1)
                .padding(bottom = OrbitTheme.spacing.x7)

            Column(modifier = scrollModifier) {
                ListConfigBodySections(
                    state = state,
                    isOnboarding = false,
                    onDone = onDone,
                    onNameChange = onNameChange,
                    onIntervalChange = onIntervalChange,
                    onTimeOfDayChange = onTimeOfDayChange,
                    onNotificationsToggle = onNotificationsToggle,
                    onNudgeScheduleChange = onNudgeScheduleChange,
                    onSmartRuleChange = onSmartRuleChange,
                    onShowConvertDialog = { showConvertDialog = true },
                    onRemoveMember = onRemoveMember,
                    onAddContacts = onAddContacts
                )
            }

            OrbitSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showConvertDialog) {
        ConvertToStaticDialog(
            memberCount = state.members.size,
            firstNames = state.members.map { it.displayName },
            onConfirm = {
                showConvertDialog = false
                // The VM confirms with "This is now a regular list." once the
                // write is in; the body announced it here, before and
                // regardless of the write, until 2026-10-06.
                onConfirmConvert()
            },
            onDismiss = { showConvertDialog = false }
        )
    }
}

/**
 * Body sections shared between the onboarding and production branches of
 * [ListConfigBody]. Extracted so both branches render byte-identical
 * content; only the surrounding scroll/IME modifier differs (see F-1 fix
 * KDoc on [ListConfigBody]).
 */
@Composable
private fun ColumnScope.ListConfigBodySections(
    state: ListConfigUiState.Ready,
    isOnboarding: Boolean,
    onDone: (() -> Unit)?,
    onNameChange: (String) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onTimeOfDayChange: (DayPart) -> Unit,
    onNotificationsToggle: (Boolean) -> Unit,
    onNudgeScheduleChange: (NudgeSchedule) -> Unit,
    onSmartRuleChange: (SmartListRule) -> Unit,
    onShowConvertDialog: () -> Unit,
    onRemoveMember: (Long, String) -> Unit,
    onAddContacts: () -> Unit
) {
    if (isOnboarding) {
        // BLOCKER 1 fix — name editor is required so onboarding
        // can satisfy ONB-11 ("no empty/unnamed lists can leave
        // onboarding"). List settings renames from its app bar title instead
        // (LIST-26), since a list arrives there already named by New list
        // (LIST-28).
        SettingGroup(title = stringResource(R.string.lists_section_name)) {
            // Local typing buffer prevents the async VM round-trip
            // from racing the IME — without it, fast typing drops the
            // first keystroke (Room write → Flow emit → recompose lags
            // the next IME event, and Compose's value= prop overwrites
            // the live buffer with the stale state.name).
            var nameText by rememberSaveable { mutableStateOf(state.name) }
            // PRIV-03: under the curtain the field draws "List" over the
            // user's buffer, which it leaves alone (CurtainMask).
            val curtainList = stringResource(R.string.components_curtain_list)
            // The group's title says "Name" over it, so the field is named
            // for TalkBack rather than labelled twice on screen.
            OrbitTextField(
                value = nameText,
                onValueChange = {
                    nameText = it
                    onNameChange(it)
                },
                label = null,
                contentDescription = stringResource(R.string.lists_section_name),
                placeholder = stringResource(R.string.lists_create_name_placeholder),
                visualTransformation = if (LocalPrivacyCurtain.current) CurtainMask(curtainList) else VisualTransformation.None,
                modifier = Modifier.padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x2)
            )
        }
    }

    // LIST-30: one control for the rhythm, for every list. The rhythm choice
    // (Keep in touch, Late night, Energize) that sat above it is gone: the
    // three are one calculation with different numbers, so a Late night or
    // Energize list shows its real base interval here (every 3 days, every
    // day) and turning the wheel makes it an ordinary list at the interval
    // chosen. Smart lists have it too: their members surface on the card like
    // anyone else's. "How often", not "Interval" (voice.md glossary, LIST-21);
    // no accent spent on settings in this body.
    SettingGroup(title = stringResource(R.string.lists_section_how_often)) {
        val intervalHours = state.intervalHours
        if (intervalHours == null) {
            // Nothing configured, or an override that no longer decodes: say
            // so, and offer the wheel at Keep in touch's starting interval.
            // Moving it is how such a list gets a rhythm (setIntervalHours
            // writes any choice when there is nothing to compare against).
            Text(
                text = stringResource(R.string.lists_rhythm_unset),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = OrbitTheme.spacing.x4, end = OrbitTheme.spacing.x4, top = OrbitTheme.spacing.x4)
            )
        }
        // The day wheel (ADR 0011), the same one New list and Contact
        // detail's custom schedule draw. It hands back whole days; the
        // ViewModel takes hours. With no rhythm yet it opens on Keep in
        // touch's starting interval, and choosing that one saves it too.
        IntervalDaysPicker(
            currentHours = intervalHours ?: RuleParams.KeepInTouch().cooldownMinHours,
            onCommit = { days -> onIntervalChange(days * 24) },
            valueIsSet = intervalHours != null,
        )
    }

    // LIST-25: which part of the day this list's nudges may come in, in place
    // of the active-hours editor ("Always active" with two time pickers).
    SettingGroup(title = stringResource(R.string.lists_section_time_of_day)) {
        TimeOfDayPicker(
            selection = state.timeOfDay,
            onSelect = onTimeOfDayChange,
        )
    }

    // One word for these notifications: "nudges" (voice.md glossary).
    SettingGroup(title = stringResource(R.string.lists_section_nudges)) {
        ToggleRow(
            label = stringResource(R.string.lists_send_nudges),
            sub = stringResource(R.string.lists_send_nudges_sub),
            value = state.notificationsEnabled,
            onChange = onNotificationsToggle
        )
        // Onboarding hides the full nudge editor (below) to stay lean, but the
        // nudge is on by default — so state its schedule here, where the list is
        // born, instead of only on the earlier permission screen. The user owns it
        // at creation: the toggle above turns it off; retiming lives in settings.
        // ADR 0009 — user-owned reminders, default-on, never a surprise.
        if (isOnboarding && state.notificationsEnabled) {
            // LIST-25: with the time of day, so the line says when the nudge
            // really comes (the scheduler's answer), not the stored 10am.
            OnboardingNudgeSummary(
                schedule = state.nudgeSchedule,
                activeHoursStart = state.activeHoursStart,
                activeHoursEnd = state.activeHoursEnd,
            )
        }
    }

    // D-05 / NOTIF-10: Nudges section is fully absent during onboarding — not
    // disabled, not alpha-hidden — so it is unreachable via keyboard or a11y
    // before setup completes (Pitfall 8).
    if (!isOnboarding) {
        SettingGroup(title = stringResource(R.string.lists_section_when_to_nudge)) {
            NudgeScheduleSection(
                schedule = state.nudgeSchedule,
                activeHoursStart = state.activeHoursStart,
                activeHoursEnd = state.activeHoursEnd,
                notificationsEnabled = state.notificationsEnabled,
                onScheduleChange = onNudgeScheduleChange
            )
        }
    }

    if (state.type == ListType.SMART) {
        val rule = state.smartRule
        if (rule != null) {
            SettingGroup(title = stringResource(R.string.lists_section_smart_rule)) {
                SmartRuleEditor(
                    rule = rule,
                    onChange = onSmartRuleChange
                )
            }
        }
    }

    SettingGroup(title = stringResource(R.string.lists_section_members)) {
        MembersPreview(
            members = state.members,
            isSmart = state.type == ListType.SMART,
            onRemoveMember = onRemoveMember,
            onAddContacts = onAddContacts
        )
    }

    if (state.type == ListType.SMART) {
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        OrbitButton(
            text = stringResource(R.string.lists_convert_button),
            onClick = onShowConvertDialog,
            variant = OrbitButtonVariant.Destructive,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.lists_convert_note),
            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgSubtle),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = OrbitTheme.spacing.x3, start = OrbitTheme.spacing.x5, end = OrbitTheme.spacing.x5)
        )
    }

    // 2026-08-15 UAT — the create flow ended here with no way to say "done",
    // only a back arrow. Everything above is already saved, so this closes the
    // screen and returns to wherever the list was opened from (Lists Manager,
    // for a list that was just created). Secondary, not Primary: the app bar's
    // Done is the screen's one accent (LIST-21, rules.md §Design 5), and two
    // accent Dones on one screen said neither was the primary action.
    if (onDone != null) {
        Spacer(Modifier.height(OrbitTheme.spacing.x6))
        OrbitButton(
            text = stringResource(R.string.components_action_done),
            onClick = onDone,
            variant = OrbitButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// `templateIdForKindLocal` (the hardcoded 1L/2L/3L kind → seed id map) is gone:
// the picker hands the RuleKind straight to the VM, which resolves the row via
// RuleTemplateRepository.getByKind. The rhythm picker itself left this body
// with LIST-30; How often is the shared day wheel, IntervalDaysPicker.
