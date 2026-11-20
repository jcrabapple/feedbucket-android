package me.ash.reader.aisummary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.ash.reader.infrastructure.aisummary.AndroidKeystoreAiSummarySecretStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real AndroidKeyStore round-trip for the AI-summary API key (emulator
 * only). Pins the security contract: the key survives an encrypt/decrypt
 * cycle, only ciphertext lands in the private SharedPreferences file, a
 * blank/null write clears the entry, and the random GCM IV makes repeated
 * writes of the same key produce different stored blobs.
 */
@RunWith(AndroidJUnit4::class)
class AiSummarySecretStoreInstrumentedTest {

    private lateinit var context: Context
    private lateinit var store: AndroidKeystoreAiSummarySecretStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = AndroidKeystoreAiSummarySecretStore(context)
        store.setApiKey(null) // clean slate for this run
    }

    @After
    fun tearDown() {
        store.setApiKey(null)
    }

    private fun rawStoredValues(): String =
        context
            .getSharedPreferences(
                AndroidKeystoreAiSummarySecretStore.PREFS_NAME,
                Context.MODE_PRIVATE,
            )
            .all
            .values
            .joinToString("|")

    @Test
    fun encryptDecryptRoundTrip() {
        val key = "sk-or-openrouter-test-key-1234567890"
        store.setApiKey(key)
        assertEquals(key, store.getApiKey())
        assertTrue(store.hasApiKey())
    }

    @Test
    fun plaintextNeverTouchesDisk() {
        val key = "sk-plaintext-must-not-be-on-disk"
        store.setApiKey(key)
        val raw = rawStoredValues()
        assertTrue("expected a stored ciphertext entry", raw.isNotEmpty())
        assertFalse("plaintext key found in SharedPreferences", raw.contains(key))
    }

    @Test
    fun randomIvProducesDifferentCiphertextForSameKey() {
        val key = "sk-same-key-twice"
        store.setApiKey(key)
        val first = rawStoredValues()
        store.setApiKey(key)
        val second = rawStoredValues()
        assertNotEquals("GCM IV must randomize the stored blob", first, second)
        assertEquals(key, store.getApiKey())
    }

    @Test
    fun nullAndBlankClearTheStoredKey() {
        store.setApiKey("sk-temporary")
        assertTrue(store.hasApiKey())

        store.setApiKey(null)
        assertNull(store.getApiKey())
        assertFalse(store.hasApiKey())

        store.setApiKey("sk-temporary-2")
        store.setApiKey("   ")
        assertNull(store.getApiKey())
        assertFalse(store.hasApiKey())
    }

    @Test
    fun emptyStoreReadsAsNull() {
        assertNull(store.getApiKey())
        assertFalse(store.hasApiKey())
    }
}
