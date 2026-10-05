package app.orbit.ui.screens.onboarding

import app.orbit.data.AppPrefs
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListType
import app.orbit.data.repository.ListRepository
import app.orbit.domain.clock.Clock
import app.orbit.domain.clock.SystemClock
import kotlinx.coroutines.flow.first

/**
 * ONB-19 / ONB-09: creates the list onboarding is building, or comes back
 * to it.
 *
 * Onboarding used to create a new list every time Preview's buttons ran. The
 * user can reach Preview twice: system back from the first-list step lands on
 * Sync, and a cold-start resume from that step lands on Sync too (a start
 * destination cannot carry the list id). Each pass left another list behind.
 *
 * The invariant now: one onboarding list at a time, remembered in
 * [AppPrefs.onboardingListId] until Done clears it.
 *  - [pendingListId] lets Sync continue straight into that list.
 *  - [startOrResume] (Preview's two exits) reuses it when it exists, so even
 *    a path that reaches Preview again cannot duplicate it.
 *  - [startAnother] ("Add another list") is the one deliberate new list; the
 *    finished one is kept and the pointer moves on.
 *
 * A plain class, built by the nav graph from the [ListRepository] and
 * [AppPrefs] it already holds (see OrbitNavHost's KDoc for why those are
 * threaded rather than injected there).
 */
class OnboardingListStarter(
    private val listRepo: ListRepository,
    private val appPrefs: AppPrefs,
    private val clock: Clock = SystemClock()
) {
    /** The list a returning onboarding should reopen, or null if there is none. */
    suspend fun pendingListId(): Long? = pendingList()?.id

    /**
     * Preview's "Make this my first list" ([memberContactIds] = the suggested
     * people) and "Start blank" (none). Reusing the pending list keeps
     * whatever the user already did on it: a name they typed is not replaced,
     * and members are only ever added.
     */
    suspend fun startOrResume(defaultName: String, memberContactIds: List<Long>): Long {
        val pending = pendingList()
        val listId = if (pending == null) {
            createList(defaultName)
        } else {
            if (pending.name.isBlank() && defaultName.isNotBlank()) {
                listRepo.updateName(pending.id, defaultName)
            }
            pending.id
        }
        addMembers(listId, memberContactIds)
        return listId
    }

    /** "Add another list": always a new, empty list. */
    suspend fun startAnother(): Long = createList(name = "")

    private suspend fun pendingList(): ListEntity? {
        // A DataStore read failure means "no list": the cost is one extra
        // list, which the user can delete, not a stuck onboarding.
        val id = runCatching { appPrefs.onboardingListId.first() }.getOrNull() ?: return null
        return listRepo.getById(id)?.takeUnless { it.isArchived }
    }

    private suspend fun createList(name: String): Long {
        val list = ListEntity(
            id = 0L, // auto-generated
            name = name,
            sortOrder = 0, // ListRepositoryImpl renumbers on create
            isArchived = false,
            type = ListType.STATIC,
            smartRuleJson = null,
            ruleTemplateId = null, // ListConfigViewModel applies its default on first read
            activeHoursStart = null,
            activeHoursEnd = null,
            notificationsEnabled = true,
            ruleParamsOverrideJson = null,
            dueCount = 0
        )
        val newListId = listRepo.create(list)
        appPrefs.setOnboardingListId(newListId)
        return newListId
    }

    private suspend fun addMembers(listId: Long, contactIds: List<Long>) {
        val now = clock.now()
        // addMember is insert-or-ignore, so re-adding someone already on a
        // reused list is a no-op.
        contactIds.forEach { contactId ->
            listRepo.addMember(listId = listId, contactId = contactId, addedAt = now)
        }
        // Fresh memberships have nextDueAt = null (due now) but the
        // denormalized ListEntity.dueCount stays 0 until a choke-point
        // recompute. Home's live header counts them while the tile badge reads
        // the column; recompute so the first Home render is consistent.
        listRepo.recomputeDueCountForList(listId, now)
    }
}
