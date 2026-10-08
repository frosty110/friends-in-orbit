package app.orbit.ui.screens.lists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.R
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
import app.orbit.domain.rule.baseIntervalHours
import app.orbit.domain.rule.toKeepInTouchEvery
import app.orbit.domain.smart.SmartListEngine
import app.orbit.domain.smart.SmartListRule
import app.orbit.domain.undo.UndoStack
import app.orbit.domain.usecase.BulkRemoveFromListUseCase
import app.orbit.notify.NudgeSchedule
import app.orbit.notify.NudgeScheduler
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

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
 *  - Save-on-change setters that dispatch directly to repository methods,
 *    with no local UI mutation. Per the save-on-change semantics there is no
 *    "Save" button for the settings; renaming from the title has its own
 *    "Save list name" (LIST-26), which calls [setName] once.
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
    savedStateHandle: SavedStateHandle
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
    // More than one slot so a second event in the same turn is kept, not
    // dropped by tryEmit (ListsManagerViewModel explains why one slot hid the
    // ungated convert confirmation).
    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = SNACKBAR_EVENT_BUFFER)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    // LIST-22: bumped by [onRetry] to re-subscribe after a failure.
    private val retryCount = MutableStateFlow(0)

    /** The Error state's Try again. */
    fun onRetry() {
        retryCount.update { it + 1 }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ListConfigUiState> =
        retryCount.flatMapLatest {
            sourceFlow().catch { t ->
                if (t is CancellationException) throw t
                emit(ListConfigUiState.Error)
            }
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = ListConfigUiState.Loading
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
            flowOf(
                buildReady(
                    entity,
                    ruleTemplate = templateLookup(entity),
                    members = members.map {
                        it.toUiSnapshot()
                    }
                )
            )
        }
    }

    /**
     * STATIC projection — joins memberships and contacts, ordering by contact
     * id ASC (matches engine determinism). Uncapped (2026-06-09 #26) — the
     * count must be the true total and every member must be removable;
     * [MembersPreview] handles the visual collapse with an honest label.
     */
    private fun staticProjection(entity: ListEntity): Flow<ListConfigUiState> = combine(
        listRepo.observeMembersOfList(entity.id),
        contactRepo.observeAll()
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
        members: List<ListConfigContactSnapshot>
    ): ListConfigUiState {
        val ruleParams: RuleParams? = resolveRuleParams(entity, ruleTemplate)
        val smartRule: SmartListRule? = entity.smartRuleJson?.let { decodeSmartRule(it) }
        val nudgeSchedule = NudgeSchedule.fromStoredJson(entity.nudgeScheduleJson)
        return ListConfigUiState.Ready(
            id = entity.id,
            name = entity.name,
            type = entity.type,
            ruleKind = ruleTemplate?.kind,
            ruleParams = ruleParams,
            smartRule = smartRule,
            notificationsEnabled = entity.notificationsEnabled,
            nudgeSchedule = nudgeSchedule,
            members = members
        )
    }

    /**
     * The per-list override, else the template's defaults, through the
     * resolver this screen shares with the Lists row (the top-level
     * [resolveRuleParams] in RuleParamsResolution.kt). Nothing configured and
     * a blob that does not decode both come back null here: How often has no
     * interval to show for either, so it says the list has no rhythm yet and
     * lets the wheel set one ([setIntervalHours]), and the Lists row is where
     * the unreadable case is named ("Couldn't read this list's rhythm"). The
     * domain's `OverrideResolver.resolveParamsFor` decodes the same JSON
     * without a catch and throws, so a list whose override is unreadable
     * here also fails to surface anyone in the deck and the queue.
     */
    private fun resolveRuleParams(
        entity: ListEntity,
        ruleTemplate: RuleTemplateEntity?
    ): RuleParams? {
        val resolved = resolveRuleParams(
            overrideJson = entity.ruleParamsOverrideJson,
            templateParamsJson = ruleTemplate?.paramsJson,
            json = json
        )
        return when (resolved) {
            is RuleParamsResolution.Decoded -> resolved.params
            RuleParamsResolution.None, RuleParamsResolution.Unreadable -> null
        }
    }

    private fun decodeSmartRule(jsonText: String): SmartListRule? = runCatching {
        json.decodeFromString(SmartListRule.serializer(), jsonText)
    }.getOrNull()

    private fun ContactEntity.toUiSnapshot(): ListConfigContactSnapshot = ListConfigContactSnapshot(
        id = id,
        displayName = displayName,
        photoUri = photoUri
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
     * One caller since LIST-30 took the rhythm choice off List settings: Make
     * your first list gives its new list Keep in touch on first read (the list
     * arrives with no template, and a list with no template surfaces no one).
     * Moving How often goes through [setIntervalHours] instead.
     *
     * The parameter is the [RuleKind], not a raw template id.
     * UI callers previously mapped kind → hardcoded `1L/2L/3L` (which merely
     * mirrored the seed-insert order) and an unknown id silently returned.
     * Resolving through [RuleTemplateRepository.getByKind] makes an unknown id
     * structurally impossible; the only remaining failure (seed never ran) is
     * thrown and surfaces as the [runMutation] "Couldn't save your change"
     * snackbar, never a silent return.
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
     * LIST-30 + LIST-04: How often, for every list. Writes the per-list
     * override as Keep in touch at [hours], built by the domain's
     * [toKeepInTouchEvery] through `withIntervalHours` (the one honest entry
     * point: both cooldown bounds move together), and gives the list the Keep
     * in touch template when it has another or none. So a Late night or
     * Energize list becomes an ordinary list at the chosen interval, with Keep
     * in touch's reset percentages and skip penalty (the helper's KDoc lists
     * every number that changes); a Keep in touch list keeps its other numbers.
     * Nothing else about the list changes: its people, each person's next
     * turn, its time of day and its nudges.
     *
     * Settling the wheel at the interval the list already has writes
     * nothing, so a Late night list stays Late night until the interval moves.
     * A list whose rhythm cannot be read (no template, or an override that no
     * longer decodes) has no interval to compare, so any choice is written:
     * this is how such a list gets a rhythm back, now that the rhythm choice
     * that used to clear a broken override is gone.
     *
     * Order matters because the two writes are separate (the repository has
     * no single write for both; [setRuleTemplate] makes the same trade). The
     * Keep in touch template is looked up first, so a missing seed row fails
     * before anything is written. The override goes next: if the template
     * write then fails, the engine already runs the chosen interval (engines
     * are picked by the parameters' type, not the template) and the screen
     * shows it, with "Couldn't save your change". The other order would leave
     * the Keep in touch template over no override, an every-2-days list that
     * is neither what it was nor what was chosen.
     */
    fun setIntervalHours(hours: Int) {
        val id = listId ?: return
        viewModelScope.launch {
            runMutation {
                val entity = listRepo.getById(id) ?: return@runMutation
                val template = templateLookup(entity)
                val current = resolveRuleParams(entity, template)
                if (current != null && current.baseIntervalHours == hours) return@runMutation
                val keepInTouchTemplate = if (template?.kind == RuleKind.KEEP_IN_TOUCH) {
                    null
                } else {
                    checkNotNull(ruleTemplateRepo.getByKind(RuleKind.KEEP_IN_TOUCH)) {
                        "rule_templates seed row missing for kind KEEP_IN_TOUCH"
                    }
                }
                val next = current.toKeepInTouchEvery(hours)
                listRepo.setRuleParamsOverrideJson(id, json.encodeToString(RuleParams.serializer(), next))
                keepInTouchTemplate?.let { listRepo.updateRuleTemplate(id, it.id) }
            }
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
     * then calls [NudgeScheduler.schedule] with the list's stored active-hours
     * window forwarded, as the chain's other callers do. That window is null for
     * every list since LIST-25's fold (2026-10-08), so the chain runs [schedule]
     * exactly; it is passed rather than dropped so this call and
     * [NudgeScheduler.scheduleFromEntity] cannot disagree.
     */
    fun onNudgeScheduleChange(schedule: NudgeSchedule) {
        val id = listId ?: return
        viewModelScope.launch {
            runMutation {
                val encoded = json.encodeToString(NudgeSchedule.serializer(), schedule)
                listRepo.setNudgeScheduleJson(id, encoded)
                // Re-read the entity to obtain the authoritative window for D-09.
                val entity = listRepo.getById(id) ?: return@runMutation
                nudgeScheduler.schedule(
                    id,
                    schedule,
                    entity.activeHoursStart,
                    entity.activeHoursEnd
                )
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
     *
     * A converted list keeps a cadence. Smart lists used to have none, so a
     * converted one landed with Cadence unselected and surfaced no one until
     * the user noticed; it now inherits Keep in touch when it has no rhythm.
     *
     * "This is now a regular list." is emitted here, only once the write is
     * in. The body used to show it the moment the dialog was confirmed, so a
     * failed convert said "Couldn't save your change" and then that the list
     * was regular (rules.md Code 3).
     */
    fun confirmConvert() {
        val id = listId ?: return
        viewModelScope.launch {
            val saved = runMutation {
                listRepo.convertSmartToStatic(id)
                if (listRepo.getById(id)?.ruleTemplateId == null) {
                    val keepInTouch = ruleTemplateRepo.getByKind(RuleKind.KEEP_IN_TOUCH)
                        ?: error("KEEP_IN_TOUCH seed row missing")
                    listRepo.updateRuleTemplate(id, keepInTouch.id)
                }
            }
            if (saved) _snackbarEvents.tryEmit(SnackbarEvent(UiText.res(R.string.lists_converted_snackbar)))
        }
    }

    /**
     * ONB-11 / ONB-24 / LIST-26: atomic single-column write of the list name.
     * Mirrors the H3-fix setter family ([setRuleTemplate],
     * [setNotificationsEnabled]). Two callers: the onboarding first-list
     * wrapper's name TextField, so the onboarding flow can satisfy ONB-11 (no
     * empty/unnamed lists can leave onboarding) without a getById → copy →
     * update round trip; and List settings' title, which renames in place and
     * commits once, on "Save list name" or the keyboard's Done (LIST-26). A
     * blank name keeps the old one; a failed write says "Couldn't save your
     * change" through [runMutation]. New list also names a list, but
     * through `CreateListUseCase`, before this screen ever opens (LIST-28).
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
                val result = bulkRemoveFromListUseCase(
                    listId = id,
                    contactIds = listOf(contactId),
                )
                undoStack.put(UndoStack.PendingUndo(result.inverse))
                _snackbarEvents.tryEmit(
                    SnackbarEvent.undoable(
                        if (contactName.isBlank()) {
                            UiText.res(R.string.lists_snackbar_member_removed_unnamed)
                        } else {
                            UiText.res(R.string.lists_snackbar_member_removed, contactName)
                        }
                    )
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
     * surface. Without it an exception inside `viewModelScope.launch` is not
     * dropped: viewModelScope installs no CoroutineExceptionHandler, so the
     * exception reaches the thread's uncaught handler and crashes the app.
     * The wrapper exists so a failed write tells the user instead (rules.md
     * Code 3). `CancellationException` is rethrown so structured concurrency
     * cancellation still propagates correctly when the screen leaves the back
     * stack.
     *
     * The failure copy is the shared "Couldn't save your change"
     * (strings_components.xml), the same words Home and Lists use for the same
     * kind of failure; this screen had its own "Couldn't update list" until
     * 2026-10-06. Returns true when [block] completed, false when it threw, so
     * a caller announces success only when there was one (rules.md Code 3).
     */
    private suspend fun runMutation(
        failureLabel: UiText = UiText.res(R.string.components_snackbar_save_failed),
        block: suspend () -> Unit
    ): Boolean {
        return try {
            block()
            true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _snackbarEvents.tryEmit(SnackbarEvent(failureLabel))
            false
        }
    }

    private companion object {
        /** Snackbar events a turn can queue before tryEmit drops one; see [_snackbarEvents]. */
        const val SNACKBAR_EVENT_BUFFER = 8
    }
}
