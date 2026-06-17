package app.orbit.domain.model

import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * DOM-08 — the [PauseDuration] sealed catalog. `Indefinite` MUST carry a null
 * duration (the use case maps it to the 9999 sentinel at write time); the finite
 * options carry their exact spans and snackbar labels.
 */
class PauseDurationTest {

    @Test
    fun `OneWeek is seven days`() {
        assertEquals(Duration.ofDays(7), PauseDuration.OneWeek.duration)
        assertEquals("1 week", PauseDuration.OneWeek.displayLabel)
    }

    @Test
    fun `OneMonth is thirty days`() {
        assertEquals(Duration.ofDays(30), PauseDuration.OneMonth.duration)
        assertEquals("1 month", PauseDuration.OneMonth.displayLabel)
    }

    @Test
    fun `Indefinite carries a null duration`() {
        assertNull(PauseDuration.Indefinite.duration, "Indefinite must be null so the use case maps it to the sentinel")
        assertEquals("indefinitely", PauseDuration.Indefinite.displayLabel)
    }
}
