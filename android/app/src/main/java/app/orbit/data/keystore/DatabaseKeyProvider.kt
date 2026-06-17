package app.orbit.data.keystore

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// The single class in the codebase that ever handles the plaintext SQLCipher passphrase.
// setUserAuthenticationRequired(false) — biometric lock was dropped from v1
// (whole-app review 2026-04-28), so the auth-gated key path is no longer planned.
// Design: features/_foundations/ADRs/0002-sqlcipher-for-room.md

private val Context.keymatDataStore by preferencesDataStore(name = "orbit_keymat")

class DatabaseKeyProvider @Inject constructor(@ApplicationContext private val context: Context) {

    private val mutex = Mutex()

    /**
     * Defence-in-depth — wrap the body in [withContext]([Dispatchers.IO]) so
     * a caller on a Main-class dispatcher (e.g. the `runBlocking` in
     * `DatabaseFactory.create`) does not pin the Main thread on Keystore +
     * crypto operations. The dispatcher swap is outside [mutex.withLock] so
     * the mutex still serialises contention.
     */
    suspend fun getOrCreatePassphrase(): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stored = context.keymatDataStore.data.first()[WRAPPED_KEY_PREF]
            if (stored != null) {
                return@withLock unwrap(stored)
            }
            // SecureRandom() on Android SDK 31+ is CSPRNG-backed by AndroidOpenSSL (seeds from
            // /dev/urandom automatically — no explicit seeding required). Called exactly once
            // per installation; do NOT switch to getInstanceStrong() which blocks on entropy.
            val plaintext = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
            val wrapped = wrap(plaintext)
            context.keymatDataStore.edit { it[WRAPPED_KEY_PREF] = wrapped }
            plaintext
        }
    }

    private fun wrap(plaintext: ByteArray): ByteArray {
        val key = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val ciphertext = cipher.doFinal(plaintext)
        return cipher.iv + ciphertext
    }

    private fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > GCM_IV_BYTES) { "wrapped payload shorter than IV" }
        val iv = wrapped.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = wrapped.copyOfRange(GCM_IV_BYTES, wrapped.size)
        val key = loadKeystoreKey()
            ?: throw KeystoreKeyMissingException()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            cipher.doFinal(ciphertext)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Thrown when the underlying secure-hardware state changes (factory reset,
            // device PIN cleared/changed on some OEMs). With setUserAuthenticationRequired(false)
            // the trigger surface is narrow but real — recovery policy is decided outside
            // this class (v1: force re-onboarding).
            throw KeystoreInvalidatedException(e)
        } catch (e: UserNotAuthenticatedException) {
            // Auth-required keys past the auth window. Not exercised in v1 (we never
            // set setUserAuthenticationRequired(true)), but kept so a future change to
            // auth-gated keys lands without a refactor.
            throw KeystoreAuthExpiredException(e)
        }
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        loadKeystoreKey()?.let { return it }
        return generateKeystoreKey()
    }

    private fun loadKeystoreKey(): SecretKey? {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return store.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun generateKeystoreKey(): SecretKey {
        val useStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        return try {
            buildAndGenerateKey(strongBox = useStrongBox)
        } catch (e: StrongBoxUnavailableException) {
            buildAndGenerateKey(strongBox = false)
        }
    }

    private fun buildAndGenerateKey(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_BITS)
            .setUserAuthenticationRequired(false)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "orbit_db_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PASSPHRASE_BYTES = 32
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val AES_KEY_BITS = 256
        private val WRAPPED_KEY_PREF = byteArrayPreferencesKey("wrapped_db_passphrase")
    }
}

/**
 * Signals the AndroidKeystore-wrapped passphrase cannot be recovered. Callers
 * (`DatabaseFactory.create`) dispatch on the subtype to pick a recovery policy:
 *   - [KeystoreKeyMissingException]       — no key ever existed; likely reinstall scenario
 *   - [KeystoreInvalidatedException]      — key invalidated mid-flight; v1 forces re-onboarding
 *   - [KeystoreAuthExpiredException]      — auth-required key past its auth window
 *
 * All three inherit from this sealed base so crash reports can group by root cause.
 */
sealed class KeystoreUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** No Keystore key exists for [DatabaseKeyProvider]. Typically means app was reinstalled. */
class KeystoreKeyMissingException :
    KeystoreUnavailableException("Keystore key missing; reinstall required")

/** Keystore key was invalidated mid-flight (factory reset, device-PIN change on some OEMs, etc.). */
class KeystoreInvalidatedException(cause: Throwable) :
    KeystoreUnavailableException("Keystore key invalidated; secure-hardware state changed", cause)

/** Auth-required Keystore key is past its user-authentication window. Not exercised in v1. */
class KeystoreAuthExpiredException(cause: Throwable) :
    KeystoreUnavailableException("Keystore key requires re-authentication", cause)
