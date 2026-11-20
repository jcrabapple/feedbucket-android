package me.ash.reader.infrastructure.aisummary

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

/**
 * Secret storage seam for the AI-summary API key. JVM tests fake this
 * interface; the production binding is [AndroidKeystoreAiSummarySecretStore].
 *
 * Contract: implementations must NEVER log the key, NEVER include it in
 * exception messages, and NEVER persist it in plaintext.
 */
interface AiSummarySecretStore {

    /** The stored API key, or null when none is set (or decryption failed). */
    fun getApiKey(): String?

    /** Stores [apiKey]; null or blank clears any stored key. */
    fun setApiKey(apiKey: String?)

    fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()
}

/**
 * Production [AiSummarySecretStore]: the key is encrypted with an
 * `AES/GCM/NoPadding` SecretKey that lives (non-exportable) in the
 * AndroidKeyStore under [KEY_ALIAS], and only the ciphertext
 * (`Base64(iv || ciphertext)`) is written to a dedicated private
 * SharedPreferences file ([PREFS_NAME], overridable for test isolation).
 * The plaintext key never touches disk and never appears in a log line or
 * an exception message; a decryption failure degrades to "no key stored"
 * instead of throwing.
 */
class AndroidKeystoreAiSummarySecretStore @Inject constructor(
    @ApplicationContext context: Context,
) : AiSummarySecretStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getApiKey(): String? {
        val stored = prefs.getString(PREF_KEY_CIPHER, null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            if (raw.size <= GCM_IV_BYTES) return null
            val iv = raw.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = raw.copyOfRange(GCM_IV_BYTES, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            // Corrupt entry or unavailable keystore: behave as "no key".
            // Never rethrow with payload details.
            null
        }
    }

    override fun setApiKey(apiKey: String?) {
        if (apiKey.isNullOrBlank()) {
            prefs.edit().remove(PREF_KEY_CIPHER).apply()
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv ?: ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
            val ciphertext = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
            val blob = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, blob, 0, iv.size)
            System.arraycopy(ciphertext, 0, blob, iv.size, ciphertext.size)
            prefs.edit()
                .putString(PREF_KEY_CIPHER, Base64.encodeToString(blob, Base64.NO_WRAP))
                .apply()
        } catch (_: Exception) {
            // Encryption unavailable: do NOT fall back to plaintext. Drop
            // any stale entry so hasApiKey() stays honest, never rethrow
            // payload details.
            prefs.edit().remove(PREF_KEY_CIPHER).apply()
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val PREFS_NAME = "ai_summary_secrets"
        const val KEY_ALIAS = "ai_summary_api_key"
        private const val PREF_KEY_CIPHER = "api_key_cipher"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
