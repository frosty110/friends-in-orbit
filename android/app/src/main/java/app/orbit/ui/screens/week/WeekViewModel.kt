package app.orbit.ui.screens.week

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.domain.clock.Clock
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * HOME-13: one list's calls, week by week, on a time axis.
 *
 * Reads the route's `listId` from [SavedStateHandle] and the same three
 * sources Home's strip reads for that list (`HomeFeed.enrichOne`): the list's
 * calls, its members (for names and faces) and, here, the list itself for the
 * app bar. The days go through the strip's own bucketing ([weekPages] over
 * `bucketRhythm`), so this week on this screen is the strip, call for call.
 *
 * Not through `HomeFeed`: the feed is process-scoped and eager on purpose
 * (ADR 0006) and holds seven days per list; a year of weeks for one list the
 * user may never open does not belong there. The call read is the one the
 * strip already makes (`observeRecentForListContacts`), so Room shares it
 * while Home is on the stack.
 *
 * Today is taken on every resume ([onResumed]), as Home does (HOME-12), so a
 * screen left open across midnight moves "This week" on with the strip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WeekViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listRepo: ListRepository,
    private val callEventRepo: CallEventRepository,
    private val contactRepo: ContactRepository,
    private val clock: Clock,
) : ViewModel() {

    // A list id that does not parse is a caller bug: a loud Error with Go
    // back, never an empty week (rules.md Code 3, Browse's precedent).
    private val listId: Long? = savedStateHandle.get<String>(ARG_LIST_ID)?.toLongOrNull()

    // Read once, as CallLogViewModel and PostCallNoteViewModel do: the zone
    // the days and the hour positions are in.
    private val zone: ZoneId = ZoneId.systemDefault()

    private val today = MutableStateFlow(clock.now().atZone(zone).toLocalDate())
    private val retry = MutableStateFlow(0)

    /** The screen resumed: re-read today, so the weeks move on after a midnight. A no-op on the same day. */
    fun onResumed() {
        today.value = clock.now().atZone(zone).toLocalDate()
    }

    /** The Error state's Try again: re-subscribes every source. */
    fun onRetry() = retry.update { it + 1 }

    val uiState: StateFlow<WeekUiState> = retry
        .flatMapLatest {
            val id = listId ?: return@flatMapLatest flowOf(WeekUiState.Error(canRetry = false))
            combine(
                listRepo.observeById(id),
                callEventRepo.observeRecentForListContacts(id),
                contactRepo.observeForListMembers(id),
                today,
            ) { list, calls, members, today ->
                if (list == null) {
                    // Deleted, or never there: nothing a retry could bring back.
                    WeekUiState.Error(canRetry = false)
                } else {
                    WeekUiState.Ready(
                        listName = list.name,
                        today = today,
                        weeks = weekPages(calls, members.associateBy { it.id }, today, zone),
                    )
                }
            }.catch { emit(WeekUiState.Error(canRetry = true)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), WeekUiState.Loading)

    companion object {
        /** The route argument ([app.orbit.nav.Routes.Week]). */
        const val ARG_LIST_ID = "listId"
    }
}
