package me.ash.reader.ui.page.home.reading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.summary.ArticleSummary
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.ArticleSummaryDao
import me.ash.reader.infrastructure.aisummary.AiSummaryOutcome
import me.ash.reader.infrastructure.aisummary.AiSummarySecretStore
import me.ash.reader.infrastructure.aisummary.AiSummaryService
import me.ash.reader.infrastructure.aisummary.AiSummarySetupGap
import me.ash.reader.infrastructure.aisummary.AiSummarySettingsStore
import me.ash.reader.infrastructure.aisummary.ArticleSummaryInput
import me.ash.reader.infrastructure.di.IODispatcher
import javax.inject.Inject

sealed interface AiSummaryUiState {
    data object Loading : AiSummaryUiState
    data object SetupNeeded : AiSummaryUiState
    data object Generating : AiSummaryUiState

    data class Success(
        val summary: String,
        val model: String,
        val fromCache: Boolean,
    ) : AiSummaryUiState

    data class Error(val message: String) : AiSummaryUiState
}

/**
 * Reader-overlay summary state machine: Room cache first, setup prompt
 * when unconfigured, else generate via [AiSummaryService] and persist.
 *
 * Stale async completions are guarded with a synchronously bumped
 * `@Volatile` generation token (bumped on every open/regenerate/close),
 * re-checked after every suspension — an article switch, a regenerate or
 * a dismiss can never write an old result into the current UI state. The
 * in-flight job is cancelled on close/cleared.
 */
@HiltViewModel
class AiSummaryViewModel @Inject constructor(
    private val articleDao: ArticleDao,
    private val summaryDao: ArticleSummaryDao,
    private val settingsStore: AiSummarySettingsStore,
    private val secretStore: AiSummarySecretStore,
    private val service: AiSummaryService,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AiSummaryUiState>(AiSummaryUiState.Loading)
    val uiState: StateFlow<AiSummaryUiState> = _uiState.asStateFlow()

    @Volatile
    private var generation = 0

    private var job: Job? = null
    private var lastArticleId: String? = null
    private var lastIsFullContent: Boolean = false

    fun open(articleId: String, isFullContent: Boolean, force: Boolean = false) {
        val gen = ++generation
        job?.cancel()
        lastArticleId = articleId
        lastIsFullContent = isFullContent
        _uiState.value = AiSummaryUiState.Loading
        job =
            viewModelScope.launch {
                if (!force) {
                    val cached = summaryDao.getByArticleId(articleId)
                    if (gen != generation) return@launch
                    if (cached != null) {
                        _uiState.value =
                            AiSummaryUiState.Success(cached.summary, cached.model, fromCache = true)
                        return@launch
                    }
                }
                val settings = settingsStore.snapshot()
                val apiKey = withContext(ioDispatcher) { secretStore.getApiKey() }.orEmpty()
                if (gen != generation) return@launch
                val gap =
                    AiSummaryService.setupGap(settings.baseUrl, settings.model, apiKey.isNotBlank())
                if (gap != AiSummarySetupGap.NONE) {
                    _uiState.value = AiSummaryUiState.SetupNeeded
                    return@launch
                }
                _uiState.value = AiSummaryUiState.Generating
                val articleWithFeed = articleDao.queryById(articleId)
                if (gen != generation) return@launch
                if (articleWithFeed == null) {
                    _uiState.value = AiSummaryUiState.Error("Article is no longer available")
                    return@launch
                }
                // Summarize the extracted full content when viewing it or
                // when it is available; otherwise the feed description.
                val article = articleWithFeed.article
                val fullContent = article.fullContent
                val sourceIsFullContent = fullContent != null
                val html = if (sourceIsFullContent) fullContent else article.rawDescription
                val outcome =
                    service.summarize(
                        baseUrl = settings.baseUrl,
                        apiKey = apiKey,
                        model = settings.model,
                        input =
                            ArticleSummaryInput(
                                title = article.title,
                                feedName = articleWithFeed.feed.name,
                                author = article.author,
                                html = html,
                                sourceIsFullContent = sourceIsFullContent,
                            ),
                    )
                if (gen != generation) return@launch
                when (outcome) {
                    is AiSummaryOutcome.Success -> {
                        summaryDao.upsert(
                            ArticleSummary(
                                articleId = articleId,
                                summary = outcome.text,
                                model = outcome.model,
                                sourceIsFullContent = sourceIsFullContent,
                                createdAt = System.currentTimeMillis(),
                            )
                        )
                        if (gen != generation) return@launch
                        _uiState.value =
                            AiSummaryUiState.Success(
                                outcome.text,
                                outcome.model,
                                fromCache = false,
                            )
                    }
                    is AiSummaryOutcome.Failure -> _uiState.value = AiSummaryUiState.Error(outcome.message)
                }
            }
    }

    /** Explicit user intent: bypass the cache and generate a fresh summary. */
    fun regenerate() {
        val id = lastArticleId ?: return
        open(id, lastIsFullContent, force = true)
    }

    fun retry() = regenerate()

    /** Overlay dismissed / navigated away: invalidate + cancel in-flight work. */
    fun close() {
        generation++
        job?.cancel()
        job = null
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }
}
