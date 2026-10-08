package app.orbit.ui.screens.home

import androidx.compose.runtime.Immutable
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.ListType
import app.orbit.ui.util.UiText

/**
 * Home state contract (ARCH-02). Sealed interface; every variant `@Immutable`
 * for Compose skipping.
 *
 * Notes:
 *   - `dueCount` on a tile is `ListEntity.dueCount`, the denormalized column
 *     the mutator use cases keep fresh (ADR 0006 Rule 2). Nothing on Home
 *     renders it as a number (HOME-6); it rides along for the feed's
 *     consumers and tests.
 *   - Home stays read-only over Room, and the permission gate lives in Settings +
 *     Onboarding. `AppViewModel` owns the live permission flag; Home
 *     does not gate on it, so the state carries no permission field.
 *   - `Empty` = zero lists present in Room, i.e. Onboarding has not produced a
 *     single list yet. The screen renders the first-install CTA (HOME-11).
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

    /**
     * HOME-10: the lists could not be read. Says so with Try again, instead of
     * the stream dying and Home sitting on stale or empty chrome (rubric D6).
     */
    @Immutable data object Error : HomeUiState

    /**
     * One card per visible list, in Lists Manager order. This is the whole
     * contract: HOME-6 retired the "N people ready" header, so there is no
     * count here, and the per-list membership observers that computed it
     * (one Room query per list) are gone with it.
     */
    @Immutable
    data class Ready(
        val lists: List<ListTileState>,
    ) : HomeUiState

    @Immutable data object Empty : HomeUiState
}

/**
 * Compact read-only projection of a [app.orbit.data.entity.ListEntity] for the
 * Home tile grid. Populates `id` + `name` from the entity; `dueCount`
 * hydrates from the nextDueAt projection.
 *
 * LIST-07 — `type` carries `ListType.SMART` or `ListType.STATIC` so the
 * renderer can show a "shuffle-angular" glyph beside the name of a smart list
 * (announced as "Smart list") and leave "Add people" out of its menu.
 * Privacy invariant: the glyph stays visible under the privacy curtain
 * because type isn't a name.
 */
@Immutable
data class ListTileState(
    val id: Long,
    val name: String,
    val dueCount: Int,
    val type: ListType,
    // HOME-3 — the head of this list's queue: who tapping the card would surface.
    // Null until the per-list enrichment (HomeFeed.enrichment) hydrates, or when
    // the list has nobody surfaceable.
    val nextUp: NextUp? = null,
    // HOME-7 — the last 7 days of qualifying calls for this list's members,
    // index 0 = six days ago, index 6 = today. Bars/colors are derived in the UI
    // (relative scaling + per-person color); this is the raw per-day data.
    val rhythm: List<RhythmDay> = emptyList(),
    // Drives the home tile long-press menu's "Pause nudges" vs "Resume nudges"
    // entry (voice.md glossary). Sourced straight from
    // `ListEntity.notificationsEnabled` in `HomeFeed.toTileState`. Defaults
    // true so preview fixtures and any future call site that doesn't care
    // about nudges compile unchanged.
    val notificationsEnabled: Boolean = true,
    // Tile subtitle ("4 people"). Hydrated by HomeViewModel from the existing
    // `ListRepository.observeMemberCountsByListId()` projection (same flow
    // Lists Manager combines) — HomeFeed.toTileState leaves it at the default.
    // Null = not yet hydrated (the cache-first initial value renders before
    // the counts query answers); the renderer keeps the subtitle line quiet
    // rather than showing a wrong "no one yet".
    val memberCount: Int? = null,
)

/**
 * HOME-3 — the always-on recommendation on a list card: the head of the list's
 * queue, with a warm, neutral [why] line (recency context, never shame framing).
 * [why] is [UiText] (resolved in the composable) so it can be translated.
 * [photoUri] is the contact's photo when present; the renderer falls back to
 * initials. No phone number: the row's call button went on 2026-10-08
 * (HOME-9), and nothing else on Home dials.
 */
@Immutable
data class NextUp(
    val contactId: Long,
    val name: String,
    val photoUri: String?,
    val why: UiText,
)

/**
 * One qualifying call in the 7-day rhythm (HOME-7). Sub-3-min calls are
 * filtered upstream.
 *
 * HOME-8 — the strip is tappable, so a bar now carries everything the day
 * sheet renders: who, which way, how long, when. `contactName` / `photoUri`
 * are hydrated in `HomeFeed.enrichOne` from the list's member contacts;
 * [durationLabel] and [timeLabel] are pre-formatted there too, so the
 * composables stay free of `Instant` and the JVM clock (the same B3 invariant
 * `CallLogRow` follows). [durationLabel] is [UiText] (strings_time.xml);
 * [timeLabel] is digits and the locale's am/pm marker, so a String.
 * [contactName] is null for someone no longer on the list; the sheet says
 * "Someone".
 *
 * Manual "Logged" connections never reach here: they're written with
 * `durationSeconds = 0` and the 3-minute rhythm floor drops them, so
 * [direction] is always a real carrier-observed direction.
 *
 * HOME-13: [minuteOfDay] is where the Week screen draws the call on its time
 * axis: the wall-clock minute it started in the device's zone (0 to 1439,
 * 18:40 is 1120), the same moment [timeLabel] words. Wall clock, not time
 * elapsed since midnight: on a day the clocks change, elapsed time would
 * draw a 6:40pm call an hour off its label. A number, so the composables
 * still never see an `Instant`.
 */
@Immutable
data class RhythmCall(
    val callEventId: Long,
    val contactId: Long,
    val contactName: String?,
    val photoUri: String?,
    val durationSeconds: Int,
    val direction: CallDirection,
    val durationLabel: UiText,   // "14 min"
    val timeLabel: String,       // "4:30pm"
    val minuteOfDay: Int,        // 1120 for 6:40pm
)

/**
 * One day of the 7-day rhythm strip, and one column of the Week screen
 * (HOME-13): the qualifying calls placed that day, oldest first.
 */
@Immutable
data class RhythmDay(val calls: List<RhythmCall>)
