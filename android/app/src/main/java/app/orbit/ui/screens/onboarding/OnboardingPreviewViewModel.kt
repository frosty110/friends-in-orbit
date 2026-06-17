package app.orbit.ui.screens.onboarding

import android.text.format.DateUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.data.entity.ContactEntity
import app.orbit.data.repository.CallAgg
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * ONB-19 — ranks contacts by recency × frequency and surfaces 5–10
 * candidates for the H/β preview. Score formula (locked):
 *
 *   score = (call_count_30d × 1.0) + max(0, 30 − days_since_last_call) / 30.0
 *
 * The aggregate from `observeAggregatesAll()` gives total
 * count + lastAt per contact. We approximate `call_count_30d` as `count`
 * for v1 (since the call-log import window already targets ~90 days
 * and the locked formula is intentionally simple). The
 * `days_since_last_call` arm is computed from `lastAt`.
 *
 * Filter: drop candidates with `lastAt == null` (never called — score = 0).
 * Sort: score DESC, then displayName ASC tiebreaker.
 * Take: at most 10. Emit empty list when fewer than 3 score > 0.
 */
@HiltViewModel
class OnboardingPreviewViewModel @Inject constructor(
    private val contactRepo: ContactRepository,
    private val callEventRepo: CallEventRepository,
) : ViewModel() {

    val uiState: StateFlow<OnboardingPreviewUiState> =
        combine(
            contactRepo.observeAll(),               // Flow<List<ContactEntity>>
            callEventRepo.observeAggregatesAll(),   // Flow<Map<Long, CallAgg>>
        ) { contacts, aggregate ->
            val now = Instant.now()
            val ranked = rankCandidates(contacts, aggregate, now)
            OnboardingPreviewUiState.Ready(
                candidates = if (ranked.size >= 3) ranked.take(10) else emptyList(),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = OnboardingPreviewUiState.Loading,
        )

    private fun rankCandidates(
        contacts: List<ContactEntity>,
        aggregate: Map<Long, CallAgg>,
        now: Instant,
    ): List<PreviewCandidate> {
        // Filter to live, non-ignored, non-archived contacts that have at
        // least one recorded call. Contacts never called score 0 and are
        // dropped before sorting.
        val scored = contacts.asSequence()
            .filterNot { it.isIgnored || it.isArchived }
            .mapNotNull { c ->
                val agg = aggregate[c.id] ?: return@mapNotNull null
                val lastAt = agg.lastAt ?: return@mapNotNull null
                val daysSinceLastCall = Duration.between(lastAt, now).toDays().coerceAtLeast(0L)
                val recencyArm = if (daysSinceLastCall >= 30) {
                    0.0
                } else {
                    (30.0 - daysSinceLastCall.toDouble()) / 30.0
                }
                val score = agg.count.toDouble() + recencyArm
                if (score <= 0.0) null
                else Triple(c, lastAt, score)
            }
            .toList()

        return scored
            .sortedWith(
                compareByDescending<Triple<ContactEntity, Instant, Double>> { it.third }
                    .thenBy { it.first.displayName },
            )
            .map { (c, lastAt, _) ->
                PreviewCandidate(
                    contactId = c.id,
                    displayName = c.displayName,
                    lastCallRelative = relativeTime(lastAt, now),
                )
            }
    }

    private fun relativeTime(lastAt: Instant, now: Instant): String {
        val rel = DateUtils.getRelativeTimeSpanString(
            lastAt.toEpochMilli(),
            now.toEpochMilli(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
        // Voice-rule prefix for the row meta line. Sentence case, no
        // exclamation; "Called {rel}" → e.g. "Called 4 days ago".
        return "Called $rel"
    }
}
