package app.orbit.logging

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import timber.log.Timber

/**
 * End-to-end gate for CALL-07: plants a capturing tree alongside the scrubber
 * logic and proves the sink does NOT receive raw PII.
 *
 * Test strategy — we do NOT plant the real [OrbitDebugTree] because its
 * `super.log()` delegates to [Timber.DebugTree] which writes to
 * `android.util.Log`; `android.util.Log` isn't available in pure JVM unit tests
 * without Robolectric. The inner [CapturingScrubbingTree] mirrors the
 * production Tree's behaviour exactly — same [PiiSanitizer.scrub] call on the
 * same message flow — but captures into a local list instead of calling super.
 *
 * The production path (real [OrbitDebugTree] over Android Logcat) is exercised
 * on-device via `adb logcat` grep.
 */
class OrbitDebugTreeTest {

    private val captured = mutableListOf<String>()

    /**
     * Test-only Tree — captures the scrubbed message without writing to Logcat.
     * Mirrors [OrbitDebugTree.log] but writes to [captured] instead of super.
     */
    private inner class CapturingScrubbingTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            captured += PiiSanitizer.scrub(message)
        }
    }

    @BeforeTest
    fun setUp() {
        Timber.uprootAll()
        Timber.plant(CapturingScrubbingTree())
    }

    @AfterTest
    fun tearDown() {
        Timber.uprootAll()
        captured.clear()
    }

    @Test
    fun planted_tree_scrubs_phone_number() {
        Timber.d("phone +14155551234 called")
        assertEquals(1, captured.size)
        assertFalse(captured[0].contains("+14155551234"), "leaked: ${captured[0]}")
        assertTrue(captured[0].contains("[phone]"), "no redaction marker: ${captured[0]}")
    }

    @Test
    fun planted_tree_scrubs_template_name_placeholder() {
        // In production this would be Timber.d("Synced ${contact.name}") and the
        // runtime would interpolate the real name. Here we pass the literal string
        // form to exercise the template-regex path.
        Timber.d("Synced \${contact.name}")
        assertEquals(1, captured.size)
        assertTrue(captured[0].contains("[contact]"), "no redaction: ${captured[0]}")
    }

    @Test
    fun planted_tree_preserves_non_pii_content() {
        Timber.d("sync_complete scanned=3400 inserted=12")
        assertEquals(1, captured.size)
        assertEquals("sync_complete scanned=3400 inserted=12", captured[0])
    }

    @Test
    fun only_one_tree_is_planted_by_setUp() {
        // The production OrbitApp.onCreate guards Timber.plant(OrbitDebugTree())
        // with a BuildConfig.DEBUG check and does not double-plant. This test
        // verifies our test harness doesn't drift — the setUp method must
        // uproot before planting.
        assertEquals(1, Timber.treeCount)
    }

    @Test
    fun production_tree_class_exists_and_extends_debug_tree() {
        // Structural check — the class we ship must be a Timber.DebugTree so
        // super.log() routes to android.util.Log in the release debug variant.
        // (We can't instantiate + exercise it here without Android runtime;
        // that is left to on-device verification.)
        // Reflection dodges Kotlin's "always true" warning on an `is` check when
        // the compiler statically knows the type hierarchy — the runtime check
        // is still the invariant under test.
        assertTrue(
            Timber.DebugTree::class.java.isAssignableFrom(OrbitDebugTree::class.java),
            "OrbitDebugTree must extend DebugTree",
        )
    }
}
