package app.orbit.domain.usecase

import app.orbit.data.dao.ListDao
import app.orbit.data.dao.ListMembershipDao
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.clock.Clock
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.rule.toKeepInTouchEvery
import app.orbit.domain.smart.SmartListRule
import javax.inject.Inject

/**
 * LIST-28: New list's "Create". Makes the list, its rhythm and its people in
 * one transaction, so a failure part way (a person who stopped existing
 * between being chosen and Create, a disk error) leaves nothing behind: no
 * list without its people, no people on a list that was never finished. The
 * flow keeps everything the user entered and says "Couldn't save your
 * change"; trying again starts from a clean slate.
 *
 * What the list gets:
 *  - **Name**: trimmed; a blank one is refused (the flow's Next cannot get
 *    past the Name step without one, so this is a loud guard, rules.md Code 3).
 *  - **Position**: last, `max(sortOrder) + 1` over every list, archived ones
 *    included (LIST-02), read inside the transaction.
 *  - **Rhythm**: the Keep in touch template with parameters for the interval
 *    the How often step ended on, built by [toKeepInTouchEvery], the same
 *    helper List settings' day wheel writes through (LIST-30). Never
 *    `cooldownMinHours` alone (the rule engine README's "Interval honesty").
 *    The template is resolved before the transaction: it is seeded, read-only
 *    data, and a missing one is a broken install, refused loudly rather than
 *    leaving a list that surfaces no one.
 *  - **People**: one membership each, due now (`nextDueAt` null, as the
 *    picker's Add writes them), then the list's due count recomputed in the
 *    same transaction (ADR 0006 Rule 2), as `CopyContactsUseCase` does. A
 *    list that fills itself takes none: `SmartListMembershipSync` writes its
 *    rows from its rule, so people handed in for one are refused.
 *  - **Nudges**: what the create sheet set before it: on, any time of day,
 *    and no stored schedule, so the default one applies from the next
 *    re-anchor (`NudgeScheduler.reAnchorAll`).
 *
 * Returns the new list's id. The widget is asked to refresh once people were
 * added (WIDGET-06: who comes up may have changed); an empty list changes
 * nothing it shows.
 */
class CreateListUseCase @Inject constructor(
    private val txRunner: TransactionRunner,
    private val listDao: ListDao,
    private val listMembershipDao: ListMembershipDao,
    private val ruleTemplateRepo: RuleTemplateRepository,
    private val clock: Clock,
    private val widgetRefreshTrigger: WidgetRefreshTrigger = WidgetRefreshTrigger { },
) {

    /** Everything New list collected, in the order its steps ask for it. */
    data class Request(
        val name: String,
        /** The rule of a list that fills itself; null for a regular list. */
        val smartRule: SmartListRule?,
        val intervalHours: Int,
        val memberContactIds: List<Long>,
    )

    suspend operator fun invoke(request: Request): Long {
        val name = request.name.trim()
        require(name.isNotEmpty()) { "a new list needs a name" }
        require(request.smartRule == null || request.memberContactIds.isEmpty()) {
            "a list that fills itself takes its people from its rule"
        }
        val template = checkNotNull(ruleTemplateRepo.getByKind(RuleKind.KEEP_IN_TOUCH)) {
            "the Keep in touch template is missing"
        }
        val json = JsonProvider.json
        val noRhythmYet: RuleParams? = null
        val params = noRhythmYet.toKeepInTouchEvery(request.intervalHours)
        val people = request.memberContactIds.distinct()
        val now = clock.now()

        val listId = txRunner.withTransaction {
            val id = listDao.insert(
                ListEntity(
                    name = name,
                    sortOrder = (listDao.maxSortOrder() ?: -1) + 1,
                    isArchived = false,
                    type = if (request.smartRule == null) ListType.STATIC else ListType.SMART,
                    smartRuleJson = request.smartRule?.let { json.encodeToString(SmartListRule.serializer(), it) },
                    ruleTemplateId = template.id,
                    activeHoursStart = null,
                    activeHoursEnd = null,
                    notificationsEnabled = true,
                    ruleParamsOverrideJson = json.encodeToString(RuleParams.serializer(), params),
                ),
            )
            if (people.isNotEmpty()) {
                listMembershipDao.insertAll(
                    people.map { contactId -> ListMembershipEntity(contactId = contactId, listId = id, addedAt = now) },
                )
                listDao.recomputeDueCount(id, now.toEpochMilli())
            }
            id
        }
        if (people.isNotEmpty()) widgetRefreshTrigger.scheduleRefresh()
        return listId
    }
}
