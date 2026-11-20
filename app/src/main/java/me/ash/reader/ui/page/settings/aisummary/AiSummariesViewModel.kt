package me.ash.reader.ui.page.settings.aisummary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.aisummary.AiSummaryOutcome
import me.ash.reader.infrastructure.aisummary.AiSummarySecretStore
import me.ash.reader.infrastructure.aisummary.AiSummaryService
import me.ash.reader.infrastructure.aisummary.AiSummarySettingsStore
import me.ash.reader.infrastructure.di.IODispatcher
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

/** Curated OpenRouter catalog for fast, low-cost summarization. */
data class RecommendedModel(val id: String, val note: String)

val recommendedModels =
    listOf(
        RecommendedModel("google/gemini-2.5-flash-lite", "Fast, low-cost, 1M context"),
        RecommendedModel("deepseek/deepseek-v4-flash", "Fast & inexpensive, 1M context"),
        RecommendedModel("z-ai/glm-5.3-flash", "Fast synthesis, 1M context"),
        RecommendedModel("anthropic/claude-haiku-5.5", "Crisp summaries, 1M context"),
        RecommendedModel("openai/gpt-5-nano", "Ultra-low cost OpenAI tier"),
        RecommendedModel("openai/gpt-oss-120b", "High-throughput open weights"),
    )

sealed interface AiSummaryTestState {
    data object Idle : AiSummaryTestState
    data object Running : AiSummaryTestState
    data class Pass(val model: String, val latencyMs: Long) : AiSummaryTestState
    data class Fail(val message: String) : AiSummaryTestState
}

@HiltViewModel
class AiSummariesViewModel @Inject constructor(
    private val settingsStore: AiSummarySettingsStore,
    private val secretStore: AiSummarySecretStore,
    private val service: AiSummaryService,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    val baseUrl = MutableStateFlow("")
    val model = MutableStateFlow("")
    val apiKey = MutableStateFlow("")
    val loaded = MutableStateFlow(false)

    private val _testState = MutableStateFlow<AiSummaryTestState>(AiSummaryTestState.Idle)
    val testState: StateFlow<AiSummaryTestState> = _testState.asStateFlow()

    private val _savedTick = MutableStateFlow(0)

    /** Bumped on every successful persist so the UI can give feedback. */
    val savedTick: StateFlow<Int> = _savedTick.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsStore.snapshot()
            val key = withContext(ioDispatcher) { secretStore.getApiKey() }
            baseUrl.value = settings.baseUrl
            model.value = settings.model
            apiKey.value = key.orEmpty()
            loaded.value = true
        }
    }

    /** Persists URL/model (DataStore) and the key (encrypted store). */
    fun save(onSaved: (() -> Unit)? = null) {
        viewModelScope.launch {
            settingsStore.save(baseUrl.value, model.value)
            withContext(ioDispatcher) { secretStore.setApiKey(apiKey.value) }
            _savedTick.value += 1
            onSaved?.invoke()
        }
    }

    fun applyRecommendedModel(id: String) {
        model.value = id
        if (baseUrl.value.isBlank()) {
            baseUrl.value = AiSummarySettingsStore.DEFAULT_BASE_URL
        }
    }

    /** Saves first, then probes the configured endpoint via the service. */
    fun testConnection() {
        if (_testState.value == AiSummaryTestState.Running) return
        _testState.value = AiSummaryTestState.Running
        viewModelScope.launch {
            settingsStore.save(baseUrl.value, model.value)
            withContext(ioDispatcher) { secretStore.setApiKey(apiKey.value) }
            _savedTick.value += 1
            val outcome =
                service.testConnection(
                    baseUrl = baseUrl.value,
                    apiKey = apiKey.value,
                    model = model.value,
                )
            _testState.value =
                when (outcome) {
                    is AiSummaryOutcome.Success ->
                        AiSummaryTestState.Pass(outcome.model, outcome.latencyMs)
                    is AiSummaryOutcome.Failure -> AiSummaryTestState.Fail(outcome.message)
                }
        }
    }
}
