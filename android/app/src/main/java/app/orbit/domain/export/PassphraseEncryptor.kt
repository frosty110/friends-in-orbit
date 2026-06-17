package app.orbit.domain.export

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * EXPORT-03 — symmetric encryption helper for user-passphrase
 * exports. Stateless utility; never cached, never injected with state.
 *
 * Format (binary, big-endian):
 *
 *     ┌─────────────────────────────────────────────────────────────┐
 *     │ MAGIC (8 bytes)        │  "OrbiExp1"                         │
 *     │ VERSION (1 byte)       │  0x01                                │
 *     │ ITERATIONS (4 bytes)   │  PBKDF2 round count                  │
 *     │ SALT_LEN (1 byte)      │  16                                  │
 *     │ SALT (SALT_LEN bytes)  │  random                              │
 *     │ IV_LEN (1 byte)        │  12                                  │
 *     │ IV (IV_LEN bytes)      │  random                              │
 *     │ CIPHERTEXT (n bytes)   │  AES/GCM/NoPadding(plaintext) + tag │
 *     └─────────────────────────────────────────────────────────────┘
 *
 * - PBKDF2WithHmacSHA256, [DEFAULT_ITERATIONS] rounds (≈120,000), 16-byte
 *   random salt → 256-bit AES key.
 * - AES/GCM/NoPadding, 12-byte random IV, 128-bit auth tag (the cipher
 *   appends the tag to the ciphertext byte stream — same idiom as
 *   [app.orbit.data.keystore.DatabaseKeyProvider]).
 *
 * Throws on every failure path; the caller decides whether to surface
 * a snackbar or propagate up. No silent fallback.
 */
object PassphraseEncryptor {

    private const val MAGIC = "OrbiExp1"           // 8 bytes
    private const val FORMAT_VERSION: Byte = 0x01
    const val DEFAULT_ITERATIONS: Int = 120_000
    private const val SALT_BYTES: Int = 16
    private const val GCM_IV_BYTES: Int = 12
    private const val GCM_TAG_BITS: Int = 128
    private const val AES_KEY_BITS: Int = 256

    suspend fun encrypt(
        plaintext: ByteArray,
        passphrase: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): ByteArray = withContext(Dispatchers.IO) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt, iterations)
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val ciphertext = cipher.doFinal(plaintext)

        val magic = MAGIC.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(
            magic.size + 1 + 4 + 1 + salt.size + 1 + iv.size + ciphertext.size,
        )
        var i = 0
        magic.copyInto(out, i); i += magic.size
        out[i++] = FORMAT_VERSION
        out[i++] = (iterations ushr 24).toByte()
        out[i++] = (iterations ushr 16).toByte()
        out[i++] = (iterations ushr 8).toByte()
        out[i++] = iterations.toByte()
        out[i++] = salt.size.toByte()
        salt.copyInto(out, i); i += salt.size
        out[i++] = iv.size.toByte()
        iv.copyInto(out, i); i += iv.size
        ciphertext.copyInto(out, i)

        out
    }

    /**
     * Provided for symmetry / round-trip testing. The export pipeline does not
     * ship an import path — but the function exists so tests can confirm
     * an export+decrypt round-trip equals the original plaintext.
     */
    suspend fun decrypt(
        envelope: ByteArray,
        passphrase: CharArray,
    ): ByteArray = withContext(Dispatchers.IO) {
        require(envelope.size > MAGIC.length + 1 + 4 + 1 + SALT_BYTES + 1 + GCM_IV_BYTES) {
            "envelope too short"
        }
        val magic = envelope.copyOfRange(0, MAGIC.length).toString(Charsets.US_ASCII)
        require(magic == MAGIC) { "magic mismatch: $magic" }
        var i = MAGIC.length
        val version = envelope[i++]
        require(version == FORMAT_VERSION) { "unsupported format version: $version" }
        val iterations =
            ((envelope[i++].toInt() and 0xFF) shl 24) or
            ((envelope[i++].toInt() and 0xFF) shl 16) or
            ((envelope[i++].toInt() and 0xFF) shl 8) or
            (envelope[i++].toInt() and 0xFF)
        val saltLen = envelope[i++].toInt() and 0xFF
        val salt = envelope.copyOfRange(i, i + saltLen); i += saltLen
        val ivLen = envelope[i++].toInt() and 0xFF
        val iv = envelope.copyOfRange(i, i + ivLen); i += ivLen
        val ciphertext = envelope.copyOfRange(i, envelope.size)

        val key = deriveKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        cipher.doFinal(ciphertext)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, iterations, AES_KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = factory.generateSecret(spec).encoded
        spec.clearPassword()                                // wipe the passphrase from the spec
        return SecretKeySpec(raw, "AES")
    }
}
