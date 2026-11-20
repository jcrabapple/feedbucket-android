package me.ash.reader.infrastructure.aisummary

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.ash.reader.ui.ext.dataStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Non-secret AI-summary configuration: the OpenAI-compatible base URL and
 * the model id. Deliberately NOT here: the API key, which lives encrypted
 * via [AiSummarySecretStore] and must never reach the DataStore.
 *
 * Defaults: the OpenRouter base URL is pre-filled so the provider chips and
 * the recommended-model dialog work out of the box; the model stays blank
 * until the user chooses or enters one, which keeps the "not configured"
 * check unambiguous (blank model = not configured).
 */
data class AiSummarySettings(
    val baseUrl: String = AiSummarySettingsStore.DEFAULT_BASE_URL,
    val model: String = "",
)

@Singleton
class AiSummarySettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val settingsFlow: Flow<AiSummarySettings> =
        context.dataStore.data.map { prefs ->
            AiSummarySettings(
                baseUrl = prefs[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
                model = prefs[KEY_MODEL].orEmpty(),
            )
        }

    suspend fun snapshot(): AiSummarySettings = settingsFlow.first()

    suspend fun save(baseUrl: String, model: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = baseUrl.trim()
            prefs[KEY_MODEL] = model.trim()
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"

        // Names match the PreferencesKey entries registered in DataStoreExt
        // so settings export/import round-trips these two values.
        val KEY_BASE_URL = stringPreferencesKey("aiSummaryBaseUrl")
        val KEY_MODEL = stringPreferencesKey("aiSummaryModel")
    }
}
