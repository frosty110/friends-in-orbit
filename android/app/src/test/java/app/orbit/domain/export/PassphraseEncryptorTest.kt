package app.orbit.domain.export

import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * EXPORT-03 — round-trip + tamper-resistance tests for [PassphraseEncryptor].
 *
 * The encryptor is pure `javax.crypto` (PBKDF2WithHmacSHA256 + AES/GCM/NoPadding),
 * so it runs as a plain JVM unit test. Tests use a low iteration count for speed;
 * iteration strength is not what's under test — format and crypto correctness are.
 */
class PassphraseEncryptorTest {

    private val pass get() = "correct horse battery staple".toCharArray()
    private val fastIterations = 1_000

    @Test
    fun `decrypt of encrypt returns the original plaintext`() = runTest {
        val plaintext = "the quick brown fox".toByteArray()
        val envelope = PassphraseEncryptor.encrypt(plaintext, pass, fastIterations)

        val decrypted = PassphraseEncryptor.decrypt(envelope, pass)

        assertTrue(plaintext.contentEquals(decrypted), "round-trip must recover the plaintext byte-for-byte")
    }

    @Test
    fun `round-trips at the default iteration count`() = runTest {
        val plaintext = "default rounds".toByteArray()
        // Exercises the DEFAULT_ITERATIONS path (no explicit iterations arg).
        val envelope = PassphraseEncryptor.encrypt(plaintext, pass)

        assertTrue(plaintext.contentEquals(PassphraseEncryptor.decrypt(envelope, pass)))
    }

    @Test
    fun `empty plaintext round-trips`() = runTest {
        val envelope = PassphraseEncryptor.encrypt(ByteArray(0), pass, fastIterations)

        assertTrue(ByteArray(0).contentEquals(PassphraseEncryptor.decrypt(envelope, pass)))
    }

    @Test
    fun `wrong passphrase fails the GCM auth tag`() = runTest {
        val envelope = PassphraseEncryptor.encrypt("secret".toByteArray(), pass, fastIterations)

        assertFails { PassphraseEncryptor.decrypt(envelope, "wrong passphrase".toCharArray()) }
    }

    @Test
    fun `tampered ciphertext is rejected`() = runTest {
        val envelope = PassphraseEncryptor.encrypt("secret".toByteArray(), pass, fastIterations)
        // Flip the final byte (inside the ciphertext/tag region) — GCM must reject it.
        val tampered = envelope.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertFails { PassphraseEncryptor.decrypt(tampered, pass) }
    }

    @Test
    fun `a too-short envelope is rejected before any crypto runs`() = runTest {
        assertFails { PassphraseEncryptor.decrypt(ByteArray(5), pass) }
    }

    @Test
    fun `each encryption uses fresh salt and IV so envelopes differ`() = runTest {
        val plaintext = "same input".toByteArray()
        val a = PassphraseEncryptor.encrypt(plaintext, pass, fastIterations)
        val b = PassphraseEncryptor.encrypt(plaintext, pass, fastIterations)

        assertFalse(a.contentEquals(b), "random salt + IV must make repeat encryptions diverge")
    }

    @Test
    fun `envelope header carries the magic, version and iteration count`() = runTest {
        val iterations = 4_096
        val envelope = PassphraseEncryptor.encrypt("hdr".toByteArray(), pass, iterations)

        // MAGIC (8 bytes) + VERSION (1) + ITERATIONS (4, big-endian).
        assertEquals("OrbiExp1", envelope.copyOfRange(0, 8).toString(Charsets.US_ASCII))
        assertEquals(0x01.toByte(), envelope[8])
        val encodedIterations =
            ((envelope[9].toInt() and 0xFF) shl 24) or
                ((envelope[10].toInt() and 0xFF) shl 16) or
                ((envelope[11].toInt() and 0xFF) shl 8) or
                (envelope[12].toInt() and 0xFF)
        assertEquals(iterations, encodedIterations)
    }
}
