package me.ash.reader.infrastructure.aisummary

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.ash.reader.infrastructure.di.IODispatcher
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the model saw when a summary was produced. */
data class ArticleSummaryInput(
    val title: String?,
    val feedName: String?,
    val author: String?,
    val html: String?,
    val sourceIsFullContent: Boolean,
)

/** Structured result of a summarize / test-connection call. */
sealed interface AiSummaryOutcome {
    data class Success(
        val text: String,
        val model: String,
        val latencyMs: Long,
    ) : AiSummaryOutcome

    /** Human-presentable, sanitized error; never contains the API key. */
    data class Failure(val message: String) : AiSummaryOutcome
}

/** Which required setting is missing (NONE = configured). */
enum class AiSummarySetupGap { NONE, BASE_URL, MODEL, API_KEY }

/**
 * OpenAI-compatible chat-completions client for AI summaries.
 *
 * Derives its transport from the app-wide platform-TLS [OkHttpClient]
 * (certificate + hostname verification stay ON; only the read timeout is
 * extended). Endpoints are normalized to `.../chat/completions`; plain
 * `http://` is accepted ONLY for loopback / private-LAN / `.local` hosts so
 * a local Ollama-style server works, while public endpoints must be
 * `https://` with a non-blank Bearer key.
 *
 * The summarizer prompt is hard-coded ([SYSTEM_PROMPT]): a one-sentence
 * TL;DR followed by 3-5 concise bullet takeaways, grounded strictly in the
 * provided article text.
 */
@Singleton
class AiSummaryService @Inject constructor(
    okHttpClient: OkHttpClient,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val client: OkHttpClient =
        okHttpClient.newBuilder()
            .readTimeout(READ_TIMEOUT_SECS, TimeUnit.SECONDS)
            .build()

    /**
     * Summarizes one article. [html] is the active article HTML: the
     * extracted full content when viewing/available, else the feed
     * description; it is stripped to plain text (Jsoup) and capped before
     * it ever reaches the network.
     */
    suspend fun summarize(
        baseUrl: String,
        apiKey: String,
        model: String,
        input: ArticleSummaryInput,
    ): AiSummaryOutcome =
        withContext(ioDispatcher) {
            val endpoint =
                try {
                    normalizeEndpoint(baseUrl)
                } catch (e: IllegalArgumentException) {
                    return@withContext AiSummaryOutcome.Failure(e.message ?: "Invalid base URL")
                }
            if (model.isBlank()) {
                return@withContext AiSummaryOutcome.Failure("No model configured")
            }
            if (isHttps(endpoint) && apiKey.isBlank()) {
                return@withContext AiSummaryOutcome.Failure(
                    "An API key is required for HTTPS endpoints"
                )
            }
            val userMessage = buildUserMessage(input)
            if (userMessage.substringAfter("Article text:\n", "").isBlank()) {
                return@withContext AiSummaryOutcome.Failure(
                    "Nothing to summarize: the article body is empty"
                )
            }
            postChat(
                endpoint = endpoint,
                apiKey = apiKey,
                model = model,
                systemPrompt = SYSTEM_PROMPT,
                userMessage = userMessage,
                maxTokens = MAX_TOKENS,
                temperature = TEMPERATURE,
            )
        }

    /**
     * Minimal `/chat/completions` probe for the settings screen: same
     * endpoint/auth rules as [summarize], tiny token budget, fixed prompt.
     * Success carries the responding model + latency; failure carries a
     * sanitized message.
     */
    suspend fun testConnection(
        baseUrl: String,
        apiKey: String,
        model: String,
    ): AiSummaryOutcome =
        withContext(ioDispatcher) {
            val endpoint =
                try {
                    normalizeEndpoint(baseUrl)
                } catch (e: IllegalArgumentException) {
                    return@withContext AiSummaryOutcome.Failure(e.message ?: "Invalid base URL")
                }
            if (model.isBlank()) {
                return@withContext AiSummaryOutcome.Failure("No model configured")
            }
            if (isHttps(endpoint) && apiKey.isBlank()) {
                return@withContext AiSummaryOutcome.Failure(
                    "An API key is required for HTTPS endpoints"
                )
            }
            postChat(
                endpoint = endpoint,
                apiKey = apiKey,
                model = model,
                systemPrompt = null,
                userMessage = TEST_PROMPT,
                maxTokens = TEST_MAX_TOKENS,
                temperature = TEMPERATURE,
            )
        }

    private suspend fun postChat(
        endpoint: String,
        apiKey: String,
        model: String,
        systemPrompt: String?,
        userMessage: String,
        maxTokens: Int,
        temperature: Double,
    ): AiSummaryOutcome {
        val requestBody =
            buildRequestBody(model, systemPrompt, userMessage, maxTokens, temperature)
        val startedAt = System.nanoTime()
        return try {
            execute(endpoint, apiKey, requestBody).use { response ->
                val latencyMs = (System.nanoTime() - startedAt) / 1_000_000L
                val raw =
                    try {
                        response.body.string()
                    } catch (_: Exception) {
                        ""
                    }
                parseCompletion(raw, response.code, model, latencyMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // OkHttp exception messages carry the URL, never request
            // headers, so the API key cannot leak through here.
            AiSummaryOutcome.Failure("Network error: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            AiSummaryOutcome.Failure("Unexpected error: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun execute(
        endpoint: String,
        apiKey: String,
        body: String,
    ): Response =
        suspendCancellableCoroutine { cont ->
            val request =
                Request.Builder()
                    .url(endpoint)
                    .header("Content-Type", "application/json")
                    .apply {
                        if (apiKey.isNotBlank()) {
                            header("Authorization", "Bearer $apiKey")
                        }
                    }
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (cont.isActive) {
                            cont.resume(response)
                        } else {
                            response.close()
                        }
                    }
                }
            )
        }

    companion object {

        const val MAX_TOKENS = 700
        const val TEST_MAX_TOKENS = 16
        const val TEMPERATURE = 0.2
        const val MAX_BODY_CHARS = 24_000
        const val READ_TIMEOUT_SECS = 60L
        const val TEST_PROMPT = "Reply with OK."

        /** Hard-coded summarizer prompt (contract, AI Summaries C4). */
        const val SYSTEM_PROMPT =
            "You are a meticulous summarization assistant. Summarize the article the user " +
                "provides in clean Markdown (plain text is fine). Structure your answer as: " +
                "first a single-sentence TL;DR, then 3 to 5 concise bullet-point takeaways. " +
                "Add brief key figures or caveats only when the article states them. Ground " +
                "every statement strictly in the provided article text; never invent facts, " +
                "numbers, quotes or context that are not present."

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** UI-side configuration check: which required setting is missing. */
        fun setupGap(
            baseUrl: String,
            model: String,
            hasApiKey: Boolean,
        ): AiSummarySetupGap =
            when {
                baseUrl.isBlank() -> AiSummarySetupGap.BASE_URL
                model.isBlank() -> AiSummarySetupGap.MODEL
                isHttpsBaseUrl(baseUrl) && !hasApiKey -> AiSummarySetupGap.API_KEY
                else -> AiSummarySetupGap.NONE
            }

        private fun isHttps(endpoint: String): Boolean = endpoint.startsWith("https://")

        private fun isHttpsBaseUrl(baseUrl: String): Boolean =
            baseUrl.trim().lowercase().startsWith("https://")

        /**
         * Trims trailing slashes, appends `/chat/completions` when missing,
         * and enforces the scheme policy: only http/https, and plain http
         * only for loopback / private-LAN / `.local` hosts.
         */
        internal fun normalizeEndpoint(baseUrl: String): String {
            val trimmed = baseUrl.trim()
            if (trimmed.isEmpty()) throw IllegalArgumentException("Base URL is empty")
            val base = trimmed.trimEnd('/')
            val uri =
                try {
                    URI(base)
                } catch (_: Exception) {
                    throw IllegalArgumentException("Base URL is not a valid URL")
                }
            val scheme =
                uri.scheme?.lowercase()
                    ?: throw IllegalArgumentException("Base URL must start with http:// or https://")
            if (scheme != "http" && scheme != "https") {
                throw IllegalArgumentException("Only http and https endpoints are supported")
            }
            val host =
                uri.host
                    ?: throw IllegalArgumentException("Base URL has no host")
            if (scheme == "http" && !isLocalHost(host)) {
                throw IllegalArgumentException(
                    "http:// is only allowed for loopback, LAN or .local hosts; use https:// for public endpoints"
                )
            }
            return if (base.endsWith("/chat/completions")) base else base + "/chat/completions"
        }

        /**
         * Literal-host check (no DNS): loopback, RFC1918 private ranges,
         * `localhost`, `*.local`, and IPv4-mapped IPv6 loopback.
         */
        internal fun isLocalHost(rawHost: String): Boolean {
            val host = rawHost.trim().removePrefix("[").removeSuffix("]").lowercase()
            if (host.isEmpty()) return false
            if (host == "localhost" || host.endsWith(".localhost")) return true
            if (host.endsWith(".local")) return true
            if (host == "::1") return true
            if (host.startsWith("::ffff:")) return isLocalHost(host.removePrefix("::ffff:"))
            if (host == "127.0.0.1" || host.startsWith("127.")) return true
            if (host.startsWith("10.")) return true
            if (host.startsWith("192.168.")) return true
            if (host.startsWith("172.")) {
                val second = host.removePrefix("172.").substringBefore('.').toIntOrNull()
                if (second != null && second in 16..31) return true
            }
            return false
        }

        /** Strips HTML to clean plain text (Jsoup); null/blank-safe. */
        internal fun htmlToPlainText(html: String?): String {
            if (html.isNullOrBlank()) return ""
            return try {
                val parsed = Jsoup.parse(html)
                (parsed.body()?.text() ?: parsed.text()).ifBlank { parsed.text() }
            } catch (_: Exception) {
                html
            }
        }

        /** Caps the model input body without cutting trailing whitespace mid-word-ish. */
        internal fun capBody(text: String): String =
            if (text.length <= MAX_BODY_CHARS) text else text.take(MAX_BODY_CHARS).trimEnd()

        /** Title/feed/author context lines + capped plain-text article body. */
        internal fun buildUserMessage(input: ArticleSummaryInput): String {
            val sb = StringBuilder()
            input.title?.takeIf { it.isNotBlank() }?.let { sb.append("Title: ").append(it).append('\n') }
            input.feedName?.takeIf { it.isNotBlank() }?.let { sb.append("Feed: ").append(it).append('\n') }
            input.author?.takeIf { it.isNotBlank() }?.let { sb.append("Author: ").append(it).append('\n') }
            sb.append("\nArticle text:\n")
            sb.append(capBody(htmlToPlainText(input.html)))
            return sb.toString()
        }

        internal fun buildRequestBody(
            model: String,
            systemPrompt: String?,
            userMessage: String,
            maxTokens: Int,
            temperature: Double,
        ): String {
            val messages =
                buildJsonArray {
                    if (!systemPrompt.isNullOrBlank()) {
                        addJsonObject {
                            put("role", "system")
                            put("content", systemPrompt)
                        }
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", userMessage)
                    }
                }
            return buildJsonObject {
                    put("model", model)
                    put("temperature", temperature)
                    put("max_tokens", maxTokens)
                    put("messages", messages)
                }
                .toString()
        }

        private fun JsonElement?.stringOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        /**
         * Parses `choices[0].message.content` (string OR text-parts array),
         * surfaces `error.message`, and degrades gracefully on empty or
         * unparseable payloads. The API key is never part of any message.
         */
        internal fun parseCompletion(
            raw: String,
            httpCode: Int,
            requestModel: String,
            latencyMs: Long,
        ): AiSummaryOutcome {
            val root =
                try {
                    Json.parseToJsonElement(raw).jsonObject
                } catch (_: Exception) {
                    return AiSummaryOutcome.Failure(
                        if (httpCode in 200..299) {
                            "Unparseable response from server"
                        } else {
                            "HTTP $httpCode"
                        }
                    )
                }
            val errorElement = root["error"]
            val errorMessage =
                if (errorElement is JsonObject) {
                    errorElement["message"].stringOrNull()
                } else {
                    errorElement.stringOrNull()
                }
            if (errorMessage != null) {
                return AiSummaryOutcome.Failure(
                    if (httpCode in 200..299) errorMessage else "HTTP $httpCode: $errorMessage"
                )
            }
            if (httpCode !in 200..299) {
                return AiSummaryOutcome.Failure("HTTP $httpCode")
            }
            val message =
                (root["choices"] as? JsonArray)
                    ?.firstOrNull()
                    ?.let { (it as? JsonObject)?.get("message") as? JsonObject }
            val text: String? =
                when (val content = message?.get("content")) {
                    null, JsonNull -> null
                    is JsonPrimitive -> content.stringOrNull() ?: content.toString()
                    is JsonArray ->
                        content
                            .mapNotNull { part ->
                                val obj = part as? JsonObject ?: return@mapNotNull null
                                val type = obj["type"].stringOrNull()
                                if (type == null || type == "text") {
                                    obj["text"].stringOrNull()
                                } else {
                                    null
                                }
                            }
                            .joinToString("")
                            .ifBlank { null }
                    is JsonObject -> content["text"].stringOrNull()
                }
            if (text.isNullOrBlank()) {
                return AiSummaryOutcome.Failure("Model returned an empty response")
            }
            val usedModel = root["model"].stringOrNull() ?: requestModel
            return AiSummaryOutcome.Success(text.trim(), usedModel, latencyMs)
        }
    }
}
