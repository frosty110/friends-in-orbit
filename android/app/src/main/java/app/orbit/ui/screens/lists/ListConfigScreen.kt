package app.orbit.ui.screens.lists

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
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
import app.orbit.ui.components.CurtainMask
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitAppBarTextAction
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.components.OrbitSwitch
import app.orbit.ui.components.OrbitTextField
import app.orbit.ui.components.PhIcon
import app.orbit.ui.components.SectionLabel
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.orbitCardShadow
import app.orbit.ui.util.asString
import java.time.LocalTime

/**
 * List Configuration screen.
 *
 * Two-layer composable: outer wires Hilt VM + `collectAsStateWithLifecycle()`
 * (`lifecycle-runtime-compose` 2.8.7 is in the catalog); inner
 * ([ListConfigContent]) is stateless apart from the title's rename state, and
 * composes the app bar and [ListConfigBody] ([app.orbit.ui.components.IntervalDaysPicker],
 * [TimeOfDayPicker], [SmartRuleEditor], [MembersPreview]).
 *
 * Save-on-change semantics — every control commits via a VM setter. There is
 * no app-bar commit chip and no archive or delete here: both live on the list's
 * menus (Lists Manager's row menu and archived section, Home's long-press),
 * each with Undo, so this screen only edits. The one explicit save is the
 * title's rename (LIST-26), which has its own "Save list name".
 *
 * Sections, top to bottom: How often (every list, LIST-30) → Time of day
 * (LIST-25) → Nudges → When to nudge → Smart rule (smart lists) → People
 * (with Add people in its header for regular lists, LIST-27) → Make this a
 * regular list (smart lists) → Done.
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
        onRename = vm::setName,
        onIntervalChange = vm::setIntervalHours,
        onTimeOfDayChange = vm::setTimeOfDay,
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

/**
 * The screen as a function of its state (THEME-04).
 *
 * LIST-26: the app bar's title is the list's name and the control that
 * renames it. The name with a pencil is one button ("Rename list, Inner
 * orbit"); tapping it turns the title into a single-line field, with Cancel
 * and "Save list name" where Done was and no back arrow, so the bar holds the
 * edit and nothing else. "Save list name" and the keyboard's Done save; Cancel
 * and system Back leave the edit and keep the name (Back cancels the edit
 * before it leaves the screen). A blank name keeps the old one, and a name
 * that has not changed writes nothing. The owner's review asked for this in
 * place of the separate Name section, which is gone.
 *
 * Whether the title is being edited and the draft have one owner, this
 * composable (rules.md Code 7): the app bar's title and trailing slots and the
 * Back handler all read and write the same two values, and nothing is read
 * back from the ViewModel into the field while it is open, so a Room
 * re-emission cannot overwrite what is being typed. The name shown in the
 * title is always the stored one: after Save it changes when the write lands,
 * and a failed write leaves it as it was, with "Couldn't save your change".
 *
 * The pane title stays the screen's ([title]: the list's name, or "List"
 * under the curtain and before the list loads), so TalkBack announces the
 * screen by its name whichever control the title shows.
 *
 * [startRenaming] opens with the title already in edit mode, for previews and
 * tests.
 */
@Composable
internal fun ListConfigContent(
    state: ListConfigUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onRename: (String) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onTimeOfDayChange: (DayPart) -> Unit,
    onNotificationsToggle: (Boolean) -> Unit,
    onNudgeScheduleChange: (NudgeSchedule) -> Unit,
    onSmartRuleChange: (SmartListRule) -> Unit,
    onConfirmConvert: () -> Unit,
    onRemoveMember: (Long, String) -> Unit,
    onAddContacts: () -> Unit,
    onRetry: () -> Unit = {},
    startRenaming: Boolean = false,
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

    val ready = state as? ListConfigUiState.Ready
    var renaming by rememberSaveable { mutableStateOf(startRenaming) }
    var nameDraft by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(draftOf(ready?.name.orEmpty()))
    }
    // Only a list that is there can be renamed: if it goes (deleted
    // elsewhere) mid-edit, the bar falls back to its plain title.
    val editing = renaming && ready != null

    fun startRename() {
        nameDraft = draftOf(ready?.name.orEmpty())
        renaming = true
    }

    fun saveName() {
        val trimmed = nameDraft.text.trim()
        if (ready != null && trimmed.isNotEmpty() && trimmed != ready.name) onRename(trimmed)
        renaming = false
    }

    BackHandler(enabled = editing) { renaming = false }

    OrbitScreen {
        OrbitAppBar(
            title = title,
            leading = if (editing) {
                null
            } else {
                {
                    OrbitIconButton("arrow-left", onBack, contentDescription = stringResource(R.string.components_action_back))
                }
            },
            titleContent = when {
                editing -> {
                    { ListNameField(value = nameDraft, onValueChange = { nameDraft = it }, onSave = { saveName() }) }
                }
                ready != null -> {
                    { ListTitleButton(name = ready.name, onRename = { startRename() }) }
                }
                else -> null
            },
            // Done is only offered once there is a list to be done with:
            // Loading and NotFound have nothing to finish. TalkBack reads the
            // visible "Done": an override used to say "back to your lists",
            // which was wrong from Home's and Card view's way in (Done pops to
            // wherever the screen was opened from). While renaming, the slot
            // holds the edit's own two controls instead.
            trailing = when {
                editing -> {
                    {
                        Row {
                            OrbitIconButton(
                                icon = "x",
                                onClick = { renaming = false },
                                contentDescription = stringResource(R.string.components_action_cancel),
                            )
                            OrbitIconButton(
                                icon = "check",
                                onClick = { saveName() },
                                contentDescription = stringResource(R.string.lists_name_save),
                            )
                        }
                    }
                }
                ready != null -> {
                    {
                        OrbitAppBarTextAction(
                            text = stringResource(R.string.components_action_done),
                            onClick = onDone,
                        )
                    }
                }
                else -> null
            },
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
                actionLabel = stringResource(R.string.components_action_go_back),
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
        // production path passes `isOnboarding = false`, which leaves out the
        // name field: here the title renames (LIST-26).
        ListConfigBody(
            state = state,
            isOnboarding = false,
            snackbarHostState = snackbarHostState,
            // Foot-of-form Done, so the user who has just scrolled through
            // every setting doesn't have to travel back up to the app bar.
            onDone = onDone,
            onIntervalChange = onIntervalChange,
            onTimeOfDayChange = onTimeOfDayChange,
            onNotificationsToggle = onNotificationsToggle,
            onNudgeScheduleChange = onNudgeScheduleChange,
            onSmartRuleChange = onSmartRuleChange,
            onConfirmConvert = onConfirmConvert,
            onRemoveMember = onRemoveMember,
            onAddContacts = onAddContacts,
        )
    }
}

/** The name as an edit buffer with the cursor at its end, ready to type on. */
private fun draftOf(name: String): TextFieldValue = TextFieldValue(name, TextRange(name.length))

/**
 * LIST-26: the title at rest. The name in the title's style with a pencil
 * beside it, one 48dp button: tapping the name or the pencil starts the
 * rename. TalkBack hears "Rename list, Inner orbit", a button and still the
 * screen's heading; under the privacy curtain the title reads "List" and
 * TalkBack "Rename list" (PRIV-03). A list with no name shows "List" too.
 * The name wraps rather than truncates, as OrbitAppBar's title does at 200%.
 */
@Composable
private fun ListTitleButton(name: String, onRename: () -> Unit) {
    val curtain = LocalPrivacyCurtain.current
    val shown = if (curtain || name.isBlank()) stringResource(R.string.lists_config_title_fallback) else name
    // Resolved here: the semantics block below is not composable.
    val label = if (curtain || name.isBlank()) {
        stringResource(R.string.lists_name_rename)
    } else {
        stringResource(R.string.lists_name_rename_named, name)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
        modifier = Modifier
            .heightIn(min = OrbitTheme.spacing.tapMin)
            .clip(OrbitTheme.shapes.md)
            .clickable(role = Role.Button, onClick = onRename)
            .semantics {
                contentDescription = label
                heading()
            }
            .padding(vertical = OrbitTheme.spacing.x2),
    ) {
        Text(
            text = shown,
            style = OrbitTheme.type.h3.copy(
                color = OrbitTheme.colors.fg,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.weight(1f, fill = false),
        )
        PhIcon(
            name = "pencil-simple",
            size = 18.dp,
            tint = OrbitTheme.colors.fgMuted,
        )
    }
}

/**
 * LIST-26: the title while renaming. A single-line field named "List
 * name", focused as it appears so the keyboard comes up; the keyboard's Done
 * saves. Under the curtain it draws "List" over the buffer and leaves the
 * buffer alone, so what is saved is what was typed (CurtainMask, PRIV-03).
 * It saves only through [onSave] (the keyboard's Done or the bar's "Save list
 * name"), never on focus loss: with an explicit Cancel beside it, a blur that
 * saved would make Cancel's outcome depend on which happened first.
 */
@Composable
private fun ListNameField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSave: () -> Unit,
) {
    val curtainList = stringResource(R.string.components_curtain_list)
    val focusRequester = remember { FocusRequester() }
    // Focus may not be available on the first pass on a slow device; the
    // ContactDetailScreen pattern.
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    // The shared field (ADR 0012), over a TextFieldValue so the cursor opens
    // after the old name. No label above it: in the app bar, where the title
    // was, with Cancel and "Save list name" beside it, it is plainly the
    // name, and a label would double the bar's height. TalkBack hears "List
    // name", and an emptied field shows it as its placeholder.
    OrbitTextField(
        value = value,
        onValueChange = onValueChange,
        label = null,
        contentDescription = stringResource(R.string.lists_name_field),
        placeholder = stringResource(R.string.lists_name_field),
        visualTransformation = if (LocalPrivacyCurtain.current) CurtainMask(curtainList) else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onSave() }),
        modifier = Modifier.focusRequester(focusRequester),
    )
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

// One host for every state (gallery convention: each state is a cell the
// a11y and curtain audits can see).
@Composable
private fun ListConfigPreviewHost(state: ListConfigUiState, startRenaming: Boolean = false) {
    OrbitTheme {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            ListConfigContent(
                state = state,
                snackbarHostState = remember { SnackbarHostState() },
                onBack = {},
                onDone = {},
                onRename = {},
                onIntervalChange = {},
                onTimeOfDayChange = {},
                onNotificationsToggle = {},
                onNudgeScheduleChange = {},
                onSmartRuleChange = {},
                onConfirmConvert = {},
                onRemoveMember = { _, _ -> },
                onAddContacts = {},
                startRenaming = startRenaming,
            )
        }
    }
}

private fun previewReady(
    name: String = "Inner orbit",
    type: ListType = ListType.STATIC,
    ruleKind: RuleKind? = RuleKind.KEEP_IN_TOUCH,
    ruleParams: RuleParams? = RuleParams.KeepInTouch(),
    smartRule: SmartListRule? = null,
    activeHoursStart: LocalTime? = null,
    activeHoursEnd: LocalTime? = null,
    notificationsEnabled: Boolean = true,
    members: List<ListConfigContactSnapshot> = listOf(
        ListConfigContactSnapshot(1L, "Alex Rivera", null),
        ListConfigContactSnapshot(2L, "Sam Patel", null),
    ),
) = ListConfigUiState.Ready(
    id = 1L,
    name = name,
    type = type,
    ruleKind = ruleKind,
    ruleParams = ruleParams,
    smartRule = smartRule,
    activeHoursStart = activeHoursStart,
    activeHoursEnd = activeHoursEnd,
    notificationsEnabled = notificationsEnabled,
    nudgeSchedule = null,
    members = members,
)

@Preview(name = "ListConfigScreen, regular list, light", showBackground = true)
@Composable
private fun ListConfigScreenStaticReadyLightPreview() {
    ListConfigPreviewHost(previewReady())
}

// LIST-30: a Late night list now shows How often at its real base, "Aim for
// every 3 days", where it used to say it had nothing to set; and Mornings
// selected under Time of day.
@PreviewLightDark
@Composable
private fun ListConfigScreenStaticLateNightPreview() {
    ListConfigPreviewHost(
        previewReady(
            name = "Late night",
            ruleKind = RuleKind.LATE_NIGHT,
            ruleParams = RuleParams.LateNight(),
            activeHoursStart = DayPart.Mornings.start,
            activeHoursEnd = DayPart.Mornings.end,
            members = listOf(ListConfigContactSnapshot(1L, "Alex Rivera", null)),
        ),
    )
}

// A smart list has How often too (LIST-30): "Recently added, not called" is
// created with Keep in touch.
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "ListConfigScreen, smart list, dark", showBackground = true)
@Composable
private fun ListConfigScreenSmartReadyDarkPreview() {
    ListConfigPreviewHost(
        previewReady(
            name = "Recently added, not called",
            type = ListType.SMART,
            ruleParams = RuleParams.KeepInTouch().withIntervalHours(7 * 24),
            smartRule = SmartListRule.RecentlyAddedNotCalled(daysWindow = 30),
            members = emptyList(),
        ),
    )
}

// No rhythm Orbit can read (no template yet, or an override that no longer
// decodes): How often says so over the slider, which sets one.
@PreviewLightDark
@Composable
private fun ListConfigNoRhythmPreview() {
    ListConfigPreviewHost(previewReady(ruleKind = null, ruleParams = null))
}

// Combined preview for the stateless ListConfigContent
// (THEME-04 / THEME-05 — D-06). Renders at light/dark and 6 font-scale stops.
@PreviewLightDark
@PreviewFontScale
@Composable
private fun ListConfigContentPreview() {
    ListConfigPreviewHost(previewReady())
}

// LIST-25: a window from the old start and end pickers that is none of the
// parts reads back as "Custom: 9pm to 2am", selected, and is left alone.
@PreviewLightDark
@Composable
private fun ListConfigCustomWindowPreview() {
    ListConfigPreviewHost(
        previewReady(
            name = "People who ground me",
            ruleParams = RuleParams.KeepInTouch().withIntervalHours(14 * 24),
            activeHoursStart = LocalTime.of(21, 0),
            activeHoursEnd = LocalTime.of(2, 0),
            notificationsEnabled = false,
            members = listOf(ListConfigContactSnapshot(3L, "Jordan Lee", null)),
        ),
    )
}

// LIST-26: the title in edit mode, at every font scale: the field, Cancel and
// "Save list name" share the bar with nothing else.
@PreviewLightDark
@PreviewFontScale
@Composable
private fun ListConfigRenamingPreview() {
    ListConfigPreviewHost(previewReady(), startRenaming = true)
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
