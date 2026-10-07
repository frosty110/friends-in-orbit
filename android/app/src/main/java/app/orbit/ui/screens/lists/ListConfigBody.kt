package app.orbit.ui.screens.lists

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.orbit.R
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
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
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme
import java.time.LocalTime

/**
 * ONB-20 — production-and-onboarding body for List Configuration.
 *
 * Hosts the optional name editor (onboarding only — see `isOnboarding`
 * gate), the three SettingGroups (Cadence / Active hours / Notifications),
 * the optional Smart-rule editor, the Members preview, and the convert-to-
 * static action. The chrome (OrbitScreen + OrbitAppBar) lives in the
 * caller — production [ListConfigScreen] for the standard nav, and
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
 *     body (BLOCKER 1 / ONB-11). The production path renders the name as an
 *     inline rename row instead (F-12, [ListNameRenameRow]: static text with
 *     a pencil, a field while editing); both commit through `onNameChange`.
 *
 * Save-on-change semantics — every control commits via a VM setter (LIST-04);
 * the body never holds editable form state of its own beyond a typing buffer.
 * The onboarding name field emits to `onNameChange` on every keystroke (the VM
 * coalesces inside `runMutation`; v1 ships with no debounce); the rename row
 * commits once, on IME Done, focus loss or the check. Confirmations ("This is
 * now a regular list.") come from the ViewModel through the screen's one
 * snackbar collector, so they say only what was saved.
 */
@Composable
internal fun ListConfigBody(
    state: ListConfigUiState.Ready,
    isOnboarding: Boolean,
    snackbarHostState: SnackbarHostState,
    // Non-null on the production path only: onboarding has its own
    // "Continue" in [OnboardingScaffold] and must not grow a second exit.
    onDone: (() -> Unit)? = null,
    onNameChange: (String) -> Unit,
    // Callers hand over the RuleKind; the VM resolves the template row via
    // RuleTemplateRepository.getByKind. The previous (Long) shape required
    // UI-side hardcoded seed ids (1L/2L/3L).
    onRuleTemplateChange: (RuleKind) -> Unit,
    onRuleParamsChange: (RuleParams) -> Unit,
    onActiveHoursChange: (LocalTime?, LocalTime?) -> Unit,
    onAlwaysActiveToggled: (Boolean) -> Unit,
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
                onRuleTemplateChange = onRuleTemplateChange,
                onRuleParamsChange = onRuleParamsChange,
                onActiveHoursChange = onActiveHoursChange,
                onAlwaysActiveToggled = onAlwaysActiveToggled,
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
                    onRuleTemplateChange = onRuleTemplateChange,
                    onRuleParamsChange = onRuleParamsChange,
                    onActiveHoursChange = onActiveHoursChange,
                    onAlwaysActiveToggled = onAlwaysActiveToggled,
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
    onRuleTemplateChange: (RuleKind) -> Unit,
    onRuleParamsChange: (RuleParams) -> Unit,
    onActiveHoursChange: (LocalTime?, LocalTime?) -> Unit,
    onAlwaysActiveToggled: (Boolean) -> Unit,
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
        // onboarding"). The production branch below renders the name as
        // an inline rename row instead (F-12), since a list arrives there
        // already named by the create sheet.
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
    } else {
        // F-12 — production inline rename. The list name is rendered as
        // static text alongside a pencil affordance that flips the row
        // into an OrbitTextField. Save paths: IME "Done", focus loss,
        // or trailing check icon. Empty names revert silently — the VM
        // setter is never invoked when the trimmed buffer is blank.
        SettingGroup(title = stringResource(R.string.lists_section_name)) {
            ListNameRenameRow(
                currentName = state.name,
                onCommit = onNameChange
            )
        }
    }

    // Cadence applies to smart lists too: their members surface on the card
    // like anyone else's, so they need a rhythm. This used to be static-only,
    // which left a smart list with no way to get one.
    run {
        // LIST-21: "Rhythm", not "Cadence" (voice.md glossary); no accent spent
        // on settings in this body. The app bar's "Done" is the screen's one
        // accent; the foot-of-form Done below is Secondary for that reason.
        SettingGroup(title = stringResource(R.string.lists_section_rhythm)) {
            RuleTemplatePicker(
                currentKind = state.ruleKind,
                templates = emptyList(),
                onSelect = onRuleTemplateChange
            )
        }

        val keepInTouch = state.ruleParams as? RuleParams.KeepInTouch
        if (keepInTouch != null) {
            SettingGroup(title = stringResource(R.string.lists_section_how_often)) {
                // The day wheel, shared with Contact detail's custom schedule
                // (ADR 0011).
                IntervalDaysPicker(
                    currentHours = keepInTouch.cooldownMinHours,
                    onCommit = { days ->
                        // Rule-correctness fix — commit through withIntervalHours
                        // so cooldownMaxHours moves with the chosen interval.
                        // Committing only cooldownMinHours let the default 336h
                        // cap silently turn "aim for every 30 days" into every
                        // 14 (see RuleParams.KeepInTouch.withIntervalHours KDoc).
                        onRuleParamsChange(keepInTouch.withIntervalHours(days * 24))
                    }
                )
            }
        } else {
            // Late night / Energize carry no user-facing tunables. One quiet
            // line replaces the interval group so the hidden controls don't
            // read as something missing.
            val note = state.ruleKind?.let { rhythmNoteFor(it) }
            if (note != null) {
                SettingGroup(title = stringResource(R.string.lists_section_how_often)) {
                    Text(
                        text = stringResource(note),
                        style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.rowY)
                    )
                }
            }
        }
    }

    SettingGroup(title = stringResource(R.string.lists_section_active_hours)) {
        ActiveHoursEditor(
            start = state.activeHoursStart,
            end = state.activeHoursEnd,
            onAlwaysActiveToggled = onAlwaysActiveToggled,
            onTimesChanged = onActiveHoursChange
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
            OnboardingNudgeSummary(schedule = state.nudgeSchedule)
        }
    }

    // D-05 / NOTIF-10: Nudges section is fully absent during onboarding — not
    // disabled, not alpha-hidden — so it is unreachable via keyboard or a11y
    // before setup completes (Pitfall 8).
    if (!isOnboarding) {
        SettingGroup(title = stringResource(R.string.lists_section_when_to_nudge)) {
            NudgeScheduleSection(
                schedule = state.nudgeSchedule,
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
// RuleTemplateRepository.getByKind.

/**
 * One quiet line shown in place of the interval slider when the selected
 * template has no user-facing tunables. Copy is checked against the engine
 * defaults in [RuleParams]: late night runs the longest cooldowns (72h base)
 * with the gentlest resets; energize runs the shortest cooldowns (24h base)
 * with the strongest call-driven resets. Returns null for keep in touch,
 * which renders the slider instead.
 */
@StringRes
private fun rhythmNoteFor(kind: RuleKind): Int? = when (kind) {
    RuleKind.KEEP_IN_TOUCH -> null
    RuleKind.LATE_NIGHT -> R.string.lists_rhythm_note_late_night
    RuleKind.ENERGIZE -> R.string.lists_rhythm_note_energize
}

/**
 * F-12 — production inline rename row. Renders the current list name as
 * static text with a trailing pencil affordance; tapping the pencil (or
 * the row itself) flips into an [OrbitTextField] with the keyboard
 * raised. Save paths:
 *  - IME "Done" tap
 *  - Focus loss
 *  - Trailing check icon
 *
 * Empty names revert silently — when the trimmed buffer is blank the row
 * exits edit mode without dispatching to [onCommit]. Saves dispatch
 * through the supplied [onCommit] (wired in [ListConfigBody] to
 * `vm::setName` → `ListRepository.updateName`); the composable never
 * touches the DAO directly.
 */
@Composable
private fun ListNameRenameRow(currentName: String, onCommit: (String) -> Unit) {
    val curtain = LocalPrivacyCurtain.current
    val curtainList = stringResource(R.string.components_curtain_list)
    var editing by rememberSaveable { mutableStateOf(false) }
    // Guards the focus-loss commit below. onFocusChanged fires once with
    // isFocused=false the moment the field enters composition — before the
    // LaunchedEffect requestFocus lands — so committing on any unfocused
    // state would unmount the field on its first frame (the "flash" bug).
    // We only commit on blur after the field has genuinely held focus.
    var hasFocused by remember { mutableStateOf(false) }
    // Local typing buffer mirrors the onboarding name editor's H3 fix —
    // an in-flight Room write + Flow round-trip would otherwise overwrite
    // the next IME event with stale state. Keyed on `editing` only — keying
    // on currentName too let a mid-edit Flow re-emission wipe the buffer.
    var nameText by rememberSaveable(editing) { mutableStateOf(currentName) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Resolved here: the semantics blocks below are not composable.
    val saveDescription = stringResource(R.string.lists_name_save)
    val renameDescription = stringResource(R.string.lists_name_rename)

    fun commit() {
        val trimmed = nameText.trim()
        if (trimmed.isNotEmpty() && trimmed != currentName) {
            onCommit(trimmed)
        }
        hasFocused = false
        editing = false
    }

    if (editing) {
        // Auto-focus the field when entering edit mode so the keyboard
        // raises immediately. requestFocus is wrapped in runCatching to
        // mirror the ContactDetailScreen pattern (focus may not be
        // available the first composition pass on slow devices).
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }
        OrbitTextField(
            value = nameText,
            onValueChange = { nameText = it },
            label = null,
            contentDescription = stringResource(R.string.lists_section_name),
            // PRIV-03: drawn as "List" under the curtain; the buffer, which
            // saves on focus loss, is untouched (CurtainMask).
            visualTransformation = if (curtain) CurtainMask(curtainList) else VisualTransformation.None,
            keyboardActions = KeyboardActions(onDone = {
                commit()
                focusManager.clearFocus()
            }),
            trailing = {
                Box(
                    modifier = Modifier
                        .size(OrbitTheme.spacing.tapMin)
                        .clickable {
                            commit()
                            focusManager.clearFocus()
                        }
                        .semantics { contentDescription = saveDescription },
                    contentAlignment = Alignment.Center
                ) {
                    PhIcon(
                        name = "check",
                        size = 20.dp,
                        tint = OrbitTheme.colors.fg
                    )
                }
            },
            modifier = Modifier
                .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x2)
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        hasFocused = true
                    } else if (hasFocused && editing) {
                        commit()
                    }
                }
        )
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editing = true }
                .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.rowY)
        ) {
            Text(
                text = if (curtain) curtainList else currentName.ifBlank { stringResource(R.string.lists_name_unnamed) },
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(OrbitTheme.spacing.tapMin)
                    .clickable { editing = true }
                    .semantics { contentDescription = renameDescription },
                contentAlignment = Alignment.Center
            ) {
                PhIcon(
                    name = "pencil-simple",
                    size = 18.dp,
                    tint = OrbitTheme.colors.fgMuted
                )
            }
        }
    }
}
