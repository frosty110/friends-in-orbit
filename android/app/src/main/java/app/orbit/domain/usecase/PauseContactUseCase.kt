package app.orbit.domain.usecase

import app.orbit.data.repository.ContactRepository
import app.orbit.domain.clock.Clock
import app.orbit.domain.model.PauseDuration
import java.time.Instant
import javax.inject.Inject

/**
 * Writes `Contact.pausedUntil` to hide a contact from surfacing (DOM-08).
 *
 * `PauseDuration.Indefinite` maps to a named far-future sentinel
 * ([INDEFINITE_PAUSE_SENTINEL]) NOT `Instant.MAX`. Rationale: `Instant.MAX` is
 * `+1000000000-12-31T23:59:59.999999999Z` — valid as an `Instant` but roundtrips
 * oddly through Room's Long epoch-millis TypeConverter on some JVMs. The 9999
 * sentinel is far enough out for every practical v1 scenario and encodes cleanly.
 *
 * Companion helper [isIndefinite] classifies a stored `pausedUntil` Instant —
 * the unpause prompt UI uses this rather than comparing to `Instant.MAX`.
 */
class PauseContactUseCase @Inject constructor(
    private val contactRepo: ContactRepository,
    private val clock: Clock,
) {

    suspend operator fun invoke(contactId: Long, duration: PauseDuration) {
        // PauseDuration.duration is null only for Indefinite; let-fallback preserves
        // the sealed-class contract without an `!!` (banned by project convention).
        val pausedUntil: Instant = duration.duration
            ?.let { clock.now().plus(it) }
            ?: INDEFINITE_PAUSE_SENTINEL
        contactRepo.setPausedUntil(contactId, pausedUntil)
    }

    companion object {
        /**
         * Sentinel for [PauseDuration.Indefinite]. Use [isIndefinite] to classify a
         * stored Instant rather than comparing directly.
         */
        val INDEFINITE_PAUSE_SENTINEL: Instant = Instant.parse("9999-12-31T23:59:59Z")

        /** True when `pausedUntil` is the indefinite-pause sentinel. */
        fun isIndefinite(pausedUntil: Instant): Boolean = pausedUntil == INDEFINITE_PAUSE_SENTINEL
    }
}
