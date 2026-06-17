package app.orbit.ui.screens.lists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.data.entity.RuleTemplateEntity
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.smart.SmartListEngine
import app.orbit.domain.smart.SmartListRule
import app.orbit.domain.undo.UndoStack
import app.orbit.domain.usecase.BulkRemoveFromListUseCase
import app.orbit.notify.NudgeSchedule
import app.orbit.notify.NudgeScheduler
import app.orbit.ui.screens.picker.SnackbarEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * List Configuration ViewModel.
 *
 * Replaces an earlier read-only stub (which derived a single-list view from
 * `observeAll().map { firstOrNull }` and stubbed every write). Now wires:
 *
 *  - `listRepo.observeById(listId)` as the spine.
 *  - For STATIC lists: combine with `listRepo.observeMembersOfList(listId)` +
 *    `contactRepo.observeAll()` to project the Members preview.
 *  - For SMART lists: combine with `smartListEngine.membership(rule)` to project
 *    a Flow-driven members preview that re-emits when rule params change
 *    (SMART-06).
 *  - Five save-on-change setters that dispatch directly to repository methods —
 *    no local UI mutation. Per the save-on-change semantics no "Save"
 *    button exists.
 *
 * `ruleParams` resolution mirrors `OverrideResolver` (per-list override beats
 * template default). Per-contact override is irrelevant for List Configuration
 * — that's a concern at the contact-detail surface.
 *
 * `stateIn(WhileSubscribed(5_000L))` preserves state across rotation + dark-mode
 * toggle (ARCH-02 config-change survival).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ListConfigViewModel @Inject constructor(
    private val listRepo: ListRepository,
    private val ruleTemplateRepo: RuleTemplateRepository,
    private val contactRepo: ContactRepository,
    private val smartListEngine: SmartListEngine,
    private val bulkRemoveFromListUseCase: BulkRemoveFromListUseCase,
    private val undoStack: UndoStack,
    private val nudgeScheduler: NudgeScheduler,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val json = JsonProvider.json

    /**
     * `listId` arrives via [SavedStateHandle] as a String (current nav type). If
     * it doesn't parse to a Long (e.g. the `"new"` sentinel from the create
     * flow), the VM routes to [ListConfigUiState.NotFound] by emitting a
     * null-carrying Flow downstream.
     */
    private val listId: Long? = savedStateHandle.get<String>("listId")?.toLongOrNull()

    // H4 fix — VM-owned snackbar surface so [runMutation] can emit a failure
    // toast when a setter throws. The screen subscribes via [snackbarEvents].
    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    val uiState: StateFlow<ListConfigUiState> =
        sourceFlow()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = ListConfigUiState.Loading,
            )

    // ────────────────────────────────────────────────────────────────────────
    // Source pipeline
    // ────────────────────────────────────────────────────────────────────────

    private fun sourceFlow(): Flow<ListConfigUiState> {
        val id = listId ?: return flowOf(ListConfigUiState.NotFound)
        return listRepo.observeById(id).flatMapLatest { entity ->
            if (entity == null) {
                flowOf(ListConfigUiState.NotFound)
            } else {
                projectionFor(entity)
            }
        }
    }

    private fun projectionFor(entity: ListEntity): Flow<ListConfigUiState> {
        return when (entity.type) {
            ListType.SMART -> smartProjection(entity)
            ListType.STATIC -> staticProjection(entity)
        }
    }

    /**
     * SMART projection — combines the entity's smart rule (decoded each emission;
     * upstream emits whenever `setSmartRuleJson` writes) with the engine's
     * reactive membership. SMART-06 lock: editing rule params naturally causes
     * a new entity emission → flatMapLatest cancels the previous engine
     * subscription → new membership Flow starts.
     */
    private fun smartProjection(entity: ListEntity): Flow<ListConfigUiState> {
        val rule = entity.smartRuleJson?.let { decodeSmartRule(it) }
        val membersFlow: Flow<List<ContactEntity>> = if (rule == null) {
            flowOf(emptyList())
        } else {
            smartListEngine.membership(rule)
        }
        return membersFlow.flatMapLatest { members ->
            // Resolve template lazily — the rule-kind dropdown still renders for
            // SMART rows even though Interval is hidden.
            //
            // 2026-06-09 #26 — no `.take(20)` here anymore: the old cap made
            // MembersPreview report "20 people" for a 50-person list and left
            // rows 21+ unremovable. The full list flows down; MembersPreview
            // owns the (honestly labeled) visual collapse.
            flowOf(buildReady(entity, ruleTemplate = templateLookup(entity), members = members.map { it.toUiSnapshot() }))
        }
    }

    /**
     * STATIC projection — joins memberships and contacts, ordering by contact
     * id ASC (matches engine determinism). Uncapped (2026-06-09 #26) — the
     * count must be the true total and every member must be removable;
     * [MembersPreview] handles the visual collapse with an honest label.
     */
    private fun staticProjection(entity: ListEntity): Flow<ListConfigUiState> =
        combine(
            listRepo.observeMembersOfList(entity.id),
            contactRepo.observeAll(),
        ) { memberships, contacts ->
            val byId = contacts.associateBy { it.id }
            val members = memberships
                .mapNotNull { byId[it.contactId] }
                .sortedBy { it.id }
                .map { it.toUiSnapshot() }
            buildReady(entity, ruleTemplate = templateLookup(entity), members = members)
        }

    // The template lookup is suspend-only on the repository; we resolve
    // synchronously on each emission inside `combine`/`flatMapLatest` via a
    // tiny suspend hop wrapped in flow construction. To keep the projection
    // pure-Flow without re-entering coroutines we inline a one-shot suspend
    // resolver: each emission re-resolves the template; cost is bounded since
    // the template table holds three rows seeded at first launch.
    private suspend fun templateLookup(entity: ListEntity): RuleTemplateEntity? =
        entity.ruleTemplateId?.let { ruleTemplateRepo.getById(it) }

    private suspend fun buildReady(
        entity: ListEntity,
        ruleTemplate: RuleTemplateEntity?,
        members: List<ListConfigContactSnapshot>,
    ): ListConfigUiState {
        val ruleParams: RuleParams? = resolveRuleParams(entity, ruleTemplate)
        val smartRule: SmartListRule? = entity.smartRuleJson?.let { decodeSmartRule(it) }
        val nudgeSchedule = entity.nudgeScheduleJson
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(NudgeSchedule.serializer(), it) }.getOrNull() }
            ?: NudgeSchedule.DEFAULT
        return ListConfigUiState.Ready(
            id = entity.id,
            name = entity.name,
            type = entity.type,
            ruleKind = ruleTemplate?.kind,
            ruleParams = ruleParams,
            smartRule = smartRule,
            activeHoursStart = entity.activeHoursStart,
            activeHoursEnd = entity.activeHoursEnd,
            notificationsEnabled = entity.notificationsEnabled,
            nudgeSchedule = nudgeSchedule,
            members = members,
        )
    }

    private fun resolveRuleParams(
        entity: ListEntity,
        ruleTemplate: RuleTemplateEntity?,
    ): RuleParams? {
        entity.ruleParamsOverrideJson?.let { override ->
            return runCatching {
                json.decodeFromString(RuleParams.serializer(), override)
            }.getOrNull()
        }
        return ruleTemplate?.paramsJson?.let { paramsJson ->
            runCatching {
                json.decodeFromString(RuleParams.serializer(), paramsJson)
            }.getOrNull()
        }
    }

    private fun decodeSmartRule(jsonText: String): SmartListRule? = runCatching {
        json.decodeFromString(SmartListRule.serializer(), jsonText)
    }.getOrNull()

    private fun ContactEntity.toUiSnapshot(): ListConfigContactSnapshot =
        ListConfigContactSnapshot(
            id = id,
            displayName = displayName,
            photoUri = photoUri,
        )

    // ────────────────────────────────────────────────────────────────────────
    // Save-on-change setters (LIST-04, LIST-05, LIST-06, SMART-04, SMART-06)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * H3 fix — switches the rule template via the new atomic single-column
     * setter on [ListRepository]. Replaces the previous `getById → copy →
     * update` round trip; two overlapping setter taps on different columns no
     * longer clobber one another.
     *
     * The parameter is the [RuleKind], not a raw template id.
     * UI callers previously mapped kind → hardcoded `1L/2L/3L` (which merely
     * mirrored the seed-insert order) and an unknown id silently returned.
     * Resolving through [RuleTemplateRepository.getByKind] makes an unknown id
     * structurally impossible; the only remaining failure (seed never ran) is
     * thrown and surfaces as the [runMutation] "Couldn't update list" snackbar
     * — never a silent return.
     *
     * Rule-correctness fix — switching to a *different* template also clears
     * `ruleParamsOverrideJson`. The override is interval tuning for the
     * previous template; [resolveRuleParams] (and the engine-side
     * `resolveParamsFor`) return it unconditionally, so keeping it would make
     * the switch a silent no-op — pick "Late night" and the old keep-in-touch
     * tuning still runs. The interval choice is not meaningfully portable
     * (only keep in touch exposes the slider), so the list resets to the new
     * template's defaults. The clear runs before the template write: if the
     * second write fails, the list shows its old template with default params
     * — consistent — rather than a new template silently running old tuning.
     * Re-selecting the already-active template is a no-op and preserves the
     * user's tuning.
     */
    fun setRuleTemplate(kind: RuleKind) {
        val id = listId ?: return
        viewModelScope.launch {
            runMutation {
                val template = checkNotNull(ruleTemplateRepo.getByKind(kind)) {
                    "rule_templates seed row missing for kind $kind"
                }
                val current = listRepo.getById(id) ?: return@runMutation
                if (current.ruleTemplateId == template.id) return@runMutation
                listRepo.setRuleParamsOverrideJson(id, null)
                listRepo.updateRuleTemplate(id, template.id)
            }
        }
    }

    /**
     * LIST-04 — write the per-list rule-params override JSON. Pass `null` to
     * clear (revert to template default). Caller encodes via
     * `JsonProvider.json.encodeToString(RuleParams.serializer(), params)`.
     */
    fun setRuleParamsOverrideJson(jsonText: String?) {
        val id = listId ?: return
        viewModelScope.launch {
            runMutation { listRepo.setRuleParamsOverrideJson(id, jsonText) }
        }
    }

    /**
     * LIST-05 + H3 fix — atomic single-column write of (activeHoursStart,
     * activeHoursEnd). Both nulls = always active. Both non-null = the window
     * (start may equal end for the inactive case; UI blocks identical times).
     */
    fun setActiveHours(start: LocalTime?, end: LocalTime?) {
        val id = listId ?: return
        viewModelScope.launch {
            runMutation { listRepo.updateActiveHours(id, start, end) }
        }
    }

    /** LIST-05 + H3 fix — atomic single-column flip of `notificationsEnabled`. */
    fun setNotificationsEnabled(enabled: Boolean) {
        val id = listId ?: return
        viewModelScope.launch {
            runMutation { listRepo.updateNotificationsEnabled(id, enabled) }
        }
    }

    /**
     * NOTIF-10/11 — save-on-change setter for the per-list nudge schedule.
     *
     * Encodes [schedule] to JSON, persists via [ListRepository.setNudgeScheduleJson],
     * then calls [NudgeScheduler.schedule] with the list's current [activeHoursStart]
     * forwarded so the D-09 implicit active-hours slot survives a config save. Using
     * the 3-arg overload (schedule + activeHoursStart) is required — calling the
     * 2-arg overload would drop the injected slot until the next cold-start reAnchorAll.
     */
    fun onNudgeScheduleChange(schedule: NudgeSchedule) {
        val id = listId ?: return
        viewModelScope.launch {
            runMutation {
                val encoded = json.encodeToString(NudgeSchedule.serializer(), schedule)
                listRepo.setNudgeScheduleJson(id, encoded)
                // Re-read the entity to obtain the authoritative activeHoursStart for D-09.
                val entity = listRepo.getById(id) ?: return@runMutation
                nudgeScheduler.schedule(id, schedule, entity.activeHoursStart)
            }
        }
    }

    /**
     * SMART-06 — write the smart-rule JSON. Pass `null` to clear (e.g. as part
     * of convert-to-static flow; that path uses
     * [ListRepository.convertSmartToStatic] which clears as part of the
     * transaction). Caller encodes via
     * `JsonProvider.json.encodeToString(SmartListRule.serializer(), rule)`.
     */
    fun setSmartRuleJson(jsonText: String?) {
        val id = listId ?: return
        viewModelScope.launch {
            runMutation { listRepo.setSmartRuleJson(id, jsonText) }
        }
    }

    /**
     * LIST-08 — one-way SMART → STATIC conversion.
     *
     * Atomicity is owned by [ListRepository.convertSmartToStatic], which wraps
     * its DAO writes in `db.withTransaction`. This
     * VM bridge is intentionally thin: it dispatches into `viewModelScope` and
     * lets the upstream [ListRepository.observeById] Flow re-emission flip the
     * `Ready.type` to STATIC, which causes [ListConfigScreen] to re-render
     * without the Smart-rule and Convert sections.
     *
     * No local mutation: writing through the repository is the single source of
     * truth. No-op when `listId` is null (sentinel route, e.g. `"new"`).
     */
    fun confirmConvert() {
        val id = listId ?: return
        viewModelScope.launch {
            runMutation { listRepo.convertSmartToStatic(id) }
        }
    }

    /**
     * ONB-11 / ONB-24 — atomic single-column write of the list name.
     * Mirrors the H3-fix setter family ([setRuleTemplate], [setActiveHours],
     * [setNotificationsEnabled]). Used by the onboarding first-list wrapper's
     * name TextField so the onboarding flow can satisfy ONB-11 (no empty/
     * unnamed lists can leave onboarding) without a getById → copy → update
     * round trip. Production callers don't invoke this directly — production
     * list names are set inline by `CreateListBottomSheet.commit` before
     * navigation to ListConfig.
     */
    fun setName(name: String) {
        val id = listId ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runMutation { listRepo.updateName(id, trimmed) }
        }
    }

    /**
     * F-6 — inline remove of a single member from the list. Reuses
     * [BulkRemoveFromListUseCase] (single-element list) so the snapshot
     * inverse + dueCount recompute stay atomic. The inverse closure is
     * stashed on [UndoStack] (depth-1) and a [SnackbarEvent] with the "Undo"
     * action label is emitted; the screen wires the action tap to [onUndo].
     */
    fun onRemoveMember(contactId: Long, contactName: String) {
        val id = listId ?: return
        viewModelScope.launch {
            runMutation {
                val sourceListName = listRepo.getById(id)?.name.orEmpty()
                val result = bulkRemoveFromListUseCase(
                    listId = id,
                    contactIds = listOf(contactId),
                    sourceListName = sourceListName,
                )
                undoStack.put(UndoStack.PendingUndo(result.inverse, result.label))
                _snackbarEvents.tryEmit(
                    SnackbarEvent("Removed ${contactName.ifBlank { "contact" }}", "Undo"),
                )
            }
        }
    }

    /** F-6 — snackbar "Undo" tap: pops [UndoStack] and runs the inverse closure. */
    fun onUndo() {
        viewModelScope.launch {
            undoStack.take()?.inverse?.invoke()
        }
    }

    /**
     * H4 fix — wraps a mutation block with a uniform try/catch + snackbar
     * surface. Without this, an exception inside `viewModelScope.launch` is
     * silently dropped (the coroutine's uncaught handler on a viewModelScope is
     * a no-op for non-Throwable types) and the UI shows stale optimistic state.
     * `CancellationException` is rethrown so structured concurrency cancellation
     * still propagates correctly when the screen leaves the back stack.
     */
    private suspend fun runMutation(
        failureLabel: String = "Couldn't update list",
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _snackbarEvents.tryEmit(SnackbarEvent(failureLabel))
        }
    }

}
