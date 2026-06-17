package app.orbit.domain.clock

import java.time.Instant
import javax.inject.Inject

/**
 * Production [Clock] implementation. The ONLY file under `app/orbit/domain/` that is
 * allowed to call `Instant.now()` at a use-site. Every other domain class reads time
 * through an injected `Clock` parameter.
 */
class SystemClock @Inject constructor() : Clock {
    override fun now(): Instant = Instant.now()
}
