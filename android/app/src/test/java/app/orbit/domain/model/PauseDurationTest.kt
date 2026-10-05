package app.orbit.domain.model

import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * DOM-08 — the [PauseDuration] sealed catalog. `Indefinite` MUST carry a null
 * duration (the use case maps it to the 9999 sentinel at write time); the finite
 * options carry their exact spans. How each one reads in a snackbar ("for 1
 * week", "indefinitely") is copy, so it moved to string resources with the
 * snackbar sentence; app.orbit.ui.util.PauseTextTest holds those assertions.
 */
class PauseDurationTest {

    @Test
    fun `OneWeek is seven days`() {
        assertEquals(Duration.ofDays(7), PauseDuration.OneWeek.duration)
    }

    @Test
    fun `OneMonth is thirty days`() {
        assertEquals(Duration.ofDays(30), PauseDuration.OneMonth.duration)
    }

    @Test
    fun `Indefinite carries a null duration`() {
        assertNull(
            PauseDuration.Indefinite.duration,
            "Indefinite must be null so the use case maps it to the sentinel"
        )
    }
}
