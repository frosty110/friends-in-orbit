package app.orbit.data.keystore

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for [DatabaseKeyProvider] — the single class that handles
 * the plaintext SQLCipher passphrase behind Orbit's encrypted Room DB.
 *
 * Must run on a device/emulator (NOT a JVM unit test): the provider wraps the
 * passphrase with a real AndroidKeystore-backed AES/GCM key, which only exists
 * on-device. Construction is direct (no Hilt) — the `@Inject` constructor takes
 * a plain [Context]; `@ApplicationContext` is just a Hilt qualifier that has no
 * effect when the class is instantiated by hand. The configured runner is the
 * plain `androidx.test.runner.AndroidJUnitRunner`, so there is no Hilt graph to
 * inject from here.
 *
 * Invariants verified (the ones checkable on a device without simulating
 * secure-hardware invalidation):
 *   1. The passphrase is exactly [PASSPHRASE_BYTES] (= 32) bytes — the documented
 *      256-bit SQLCipher key length — and not all zero.
 *   2. IDEMPOTENCY: a second call on the SAME provider returns a byte-for-byte
 *      identical passphrase (served from the DataStore-wrapped copy via the
 *      Keystore unwrap path). This is the load-bearing property — if it ever
 *      drifted, the encrypted DB would become unopenable.
 *   3. STABILITY ACROSS INSTANCES: a freshly constructed provider returns the
 *      same passphrase, proving persistence survives the in-memory object and
 *      exercises the `unwrap()` (decrypt) branch rather than re-generation.
 *
 * Nothing is wiped between tests. Until 2026-10-06 [setUp] deleted
 * `<dataDir>/datastore/orbit_keymat.preferences_pb`, a path that never exists
 * (`preferencesDataStore` writes under `<filesDir>/datastore/`), and even the
 * right path would not have reset anything, because the production store is a
 * process-wide singleton whose cache serves every later read. So the first
 * test on a fresh install takes the generate-and-wrap branch and every later
 * one the unwrap branch; the invariants above hold the same way on both, which
 * is what makes them worth asserting. The Keystore key itself is left in place
 * (the provider reuses it via `getOrCreateKeystoreKey`).
 */
@RunWith(AndroidJUnit4::class)
class DatabaseKeyProviderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun getOrCreatePassphrase_returnsThirtyTwoNonZeroBytes() = runTest {
        val provider = DatabaseKeyProvider(context)

        val passphrase = provider.getOrCreatePassphrase()

        // 256-bit SQLCipher key: PASSPHRASE_BYTES = 32.
        assertEquals(32, passphrase.size, "passphrase must be 32 bytes (256-bit key)")
        assertTrue(passphrase.any { it != 0.toByte() }, "passphrase must not be all zero")
    }

    @Test
    fun getOrCreatePassphrase_isIdempotentAcrossCalls() = runTest {
        val provider = DatabaseKeyProvider(context)

        val first = provider.getOrCreatePassphrase()
        val second = provider.getOrCreatePassphrase()

        // Stability is mandatory: the DB key must not change between calls or the
        // encrypted database becomes permanently unopenable.
        assertTrue(
            first.contentEquals(second),
            "second call must return the identical passphrase",
        )
    }

    @Test
    fun getOrCreatePassphrase_isStableAcrossProviderInstances() = runTest {
        val first = DatabaseKeyProvider(context).getOrCreatePassphrase()

        // A brand-new instance must recover the SAME passphrase from the wrapped
        // copy in DataStore (exercises the Keystore unwrap/decrypt path).
        val second = DatabaseKeyProvider(context).getOrCreatePassphrase()

        assertTrue(
            first.contentEquals(second),
            "a fresh provider instance must return the persisted passphrase",
        )
    }
}
