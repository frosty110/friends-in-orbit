package app.orbit.ui.screens.home

import androidx.compose.runtime.Immutable
import app.orbit.data.entity.ListType

/**
 * Home state contract (ARCH-02). Sealed interface; every variant `@Immutable`
 * for Compose skipping.
 *
 * Notes:
 *   - Due-count hydration depends on `ListMembership.nextDueAt` vs.
 *     `clock.now()` projection — the call-log ingestion + rule engine pipeline
 *     feeds that, and the badge display flows through the full surfacing queue.
 *   - Home stays read-only over Room, and the permission gate lives in Settings +
 *     Onboarding. `AppViewModel` owns the live permission flag; Home
 *     does not gate on it.
 *   - `Empty` = zero lists present in Room, i.e. Onboarding has not produced a
 *     single OrbitList yet. The tile-grid empty-state renders the
 *     `OnboardingHintTile` only.
 */
sealed interface HomeUiState {

    /**
     * Pre-first-database-answer window only. On a cold start with a slow
     * SQLCipher first-open, [app.orbit.data.feed.HomeFeed.tiles] still holds
     * its `emptyList()` placeholder — indistinguishable by value from a
     * genuinely empty database. Loading covers exactly that window so Home
     * never flashes the "Create your first list" CTA at a user who has lists
     * (ADR 0006 reserves that CTA for the genuine first-install case). The
     * screen renders Loading as quiet chrome — app bar over background,
     * no skeleton (ADR 0006 §Skeleton policy forbids skeletons on
     * steady-state navigation).
     */
    @Immutable data object Loading : HomeUiState

    @Immutable
    data class Ready(
        val lists: List<ListTileState>,
        val hasPermissions: Boolean,
        // Distinct contacts due across the visible lists.
        // Drives the "N people ready" header. Summing per-tile `dueCount`
        // double-counts a contact who is due on more than one list; this is
        // the union count, derived in HomeViewModel from
        // `ListRepository.observeMembersOfList` with the same due predicate
        // as `ListDao.recomputeDueCount` (nextDueAt IS NULL OR <= now).
        val dueContactCount: Int = 0,
    ) : HomeUiState

    @Immutable data object Empty : HomeUiState
}

/**
 * Compact read-only projection of a [app.orbit.data.entity.ListEntity] for the
 * Home tile grid. Populates `id` + `name` from the entity; `dueCount`
 * hydrates from the nextDueAt projection.
 *
 * LIST-07 — `type` carries `ListType.SMART` or `ListType.STATIC` so the
 * renderer can show a "shuffle-angular" auto glyph inline with the due-count
 * badge for smart tiles only. Privacy invariant: the glyph stays visible under
 * the privacy curtain because type isn't a name.
 */
@Immutable
data class ListTileState(
    val id: Long,
    val name: String,
    val dueCount: Int,
    val type: ListType,
    // Drives the home tile long-press menu's "Mute prompts" vs "Unmute prompts"
    // entry. Sourced straight from `ListEntity.notificationsEnabled` in
    // `HomeFeed.toTileState`. Defaults true so preview fixtures and any future
    // call site that doesn't care about prompts compile unchanged.
    val notificationsEnabled: Boolean = true,
    // Tile subtitle ("4 people"). Hydrated by HomeViewModel from the existing
    // `ListRepository.observeMemberCountsByListId()` projection (same flow
    // Lists Manager combines) — HomeFeed.toTileState leaves it at the default.
    // Null = not yet hydrated (the cache-first initial value renders before
    // the counts query answers); the renderer keeps the subtitle line quiet
    // rather than showing a wrong "no one yet".
    val memberCount: Int? = null,
)
