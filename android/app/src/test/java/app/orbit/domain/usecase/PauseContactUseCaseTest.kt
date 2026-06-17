package app.orbit.domain.usecase

import app.orbit.domain.FakeContactRepository
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.model.PauseDuration
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Behavioral tests for [PauseContactUseCase] — DOM-08 + Pitfall 7 sentinel guard.
 *
 * The CRITICAL test is `Indefinite sets pausedUntil to the named sentinel — NOT
 * Instant-MAX`. If a future refactor regresses to `Instant.MAX` (the anti-pattern
 * called out in Pitfall 7 — Room's Long-based InstantTypeConverter truncates
 * `Instant.MAX` into nonsense on round-trip), this test fails first.
 */
class PauseContactUseCaseTest {

    private val T0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    // ============================================================================
    // Test 1 — OneWeek = clock.now() + 7 days
    // ============================================================================

    @Test
    fun `OneWeek sets pausedUntil to clock now plus 7 days`() = runTest {
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val useCase = PauseContactUseCase(contactRepo, TestClock(T0))

        useCase(contactId = 1L, duration = PauseDuration.OneWeek)

        val args = contactRepo.setPausedCalls.single()
        assertEquals(1L, args.contactId)
        val expected = T0.plus(Duration.ofDays(7))
        assertEquals(expected, args.pausedUntil)
    }

    // ============================================================================
    // Test 2 — OneMonth = clock.now() + 30 days
    // ============================================================================

    @Test
    fun `OneMonth sets pausedUntil to clock now plus 30 days`() = runTest {
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val useCase = PauseContactUseCase(contactRepo, TestClock(T0))

        useCase(contactId = 1L, duration = PauseDuration.OneMonth)

        val args = contactRepo.setPausedCalls.single()
        assertEquals(1L, args.contactId)
        val expected = T0.plus(Duration.ofDays(30))
        assertEquals(expected, args.pausedUntil)
    }

    // ============================================================================
    // Test 3 — Indefinite = the named 9999 sentinel — NOT Instant.MAX (Pitfall 7)
    // ============================================================================

    @Test
    fun `Indefinite sets pausedUntil to the named sentinel — NOT Instant-MAX`() = runTest {
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val useCase = PauseContactUseCase(contactRepo, TestClock(T0))

        useCase(contactId = 1L, duration = PauseDuration.Indefinite)

        val args = contactRepo.setPausedCalls.single()
        val pausedUntil = args.pausedUntil
        assertNotNull(pausedUntil)
        // Must equal the named sentinel.
        assertEquals(PauseContactUseCase.INDEFINITE_PAUSE_SENTINEL, pausedUntil)
        // CRITICAL Pitfall-7 guard: NEVER Instant.MAX.
        assertNotEquals(Instant.MAX, pausedUntil, "Pitfall 7: must NOT regress to Instant.MAX sentinel")
        // Sanity: the sentinel is the documented 9999-12-31T23:59:59Z value.
        assertEquals(Instant.parse("9999-12-31T23:59:59Z"), pausedUntil)
    }

    // ============================================================================
    // Test 4 — isIndefinite helper detects the sentinel
    // ============================================================================

    @Test
    fun `isIndefinite helper detects the sentinel and only the sentinel`() = runTest {
        // Returns true for the actual sentinel.
        assertTrue(
            PauseContactUseCase.isIndefinite(PauseContactUseCase.INDEFINITE_PAUSE_SENTINEL),
            "isIndefinite must return true for the sentinel",
        )
        // Returns false for a typical OneWeek pause result.
        val oneWeekFromNow = T0.plus(Duration.ofDays(7))
        assertFalse(
            PauseContactUseCase.isIndefinite(oneWeekFromNow),
            "isIndefinite must return false for a finite pause Instant",
        )
        // Returns false for Instant.MAX (proves the helper isn't aliased to a
        // different sentinel — defense-in-depth for the Pitfall-7 guard).
        assertFalse(
            PauseContactUseCase.isIndefinite(Instant.MAX),
            "isIndefinite must return false for Instant.MAX (Pitfall 7 defense)",
        )
    }

    // ============================================================================
    // Test 5 — different clock times produce different OneWeek/OneMonth results
    // ============================================================================

    @Test
    fun `OneWeek result follows clock — different clocks produce different pausedUntil`() = runTest {
        val laterT = T0.plus(Duration.ofDays(100))

        val contactRepo1 = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val useCase1 = PauseContactUseCase(contactRepo1, TestClock(T0))
        useCase1(contactId = 1L, duration = PauseDuration.OneWeek)
        val pausedAtT0 = contactRepo1.setPausedCalls.single().pausedUntil

        val contactRepo2 = FakeContactRepository(listOf(contactFixture(id = 1L)))
        val useCase2 = PauseContactUseCase(contactRepo2, TestClock(laterT))
        useCase2(contactId = 1L, duration = PauseDuration.OneWeek)
        val pausedAtLaterT = contactRepo2.setPausedCalls.single().pausedUntil

        assertEquals(T0.plus(Duration.ofDays(7)), pausedAtT0)
        assertEquals(laterT.plus(Duration.ofDays(7)), pausedAtLaterT)
        assertNotEquals(pausedAtT0, pausedAtLaterT, "different clocks → different pausedUntil")
    }
}
