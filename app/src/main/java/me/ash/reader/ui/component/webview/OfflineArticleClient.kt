package me.ash.reader.ui.component.webview

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import me.ash.reader.R

/**
 * S30a — the hardened client for downloaded articles. `shouldInterceptRequest`
 * delegates ONLY to the [WebViewAssetLoader]; every non-appassets request
 * (including javascript:/data:/file:/intent:/content:) returns an EMPTY
 * response — the upstream raw-HttpURLConnection fetch path (WebViewClient.kt
 * in the online renderer) is structurally unreachable here. Link taps route
 * to the external browser, never in-view navigation.
 *
 * Card t_0f68461a (reading fonts): when [pageStyle] is non-null, the served
 * `index.html` gets that CSS injected before the head close tag so downloaded
 * articles render in the same bundled reading font as the live renderers.
 * The font files themselves are served from APK resources under the appassets
 * origin by [BundledReadingFontHandler] — zero network by construction.
 */
class OfflineArticleClient(
    private val assetLoader: WebViewAssetLoader,
    private val onOpenLink: (String) -> Unit,
    /** Recording seam: every intercepted URL, in order (tests + diagnostics). */
    val onRequest: ((String) -> Unit)? = null,
    /** CSS injected into the served `index.html` (bundled reading font). */
    private val pageStyle: String? = null,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        val url = request?.url?.toString() ?: return blocked()
        onRequest?.invoke(url)
        if (!OfflineArticleOrigin.isAppAssetsUrl(url)) return blocked()
        val response = request?.url?.let { assetLoader.shouldInterceptRequest(it) } ?: blocked()
        if (
            pageStyle != null &&
            response?.data != null &&
            url.endsWith(OfflineArticleOrigin.PATH_PREFIX + "index.html")
        ) {
            return injectStyle(response, pageStyle)
        }
        return response
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?,
    ): Boolean {
        // Never navigate in-view: any tap goes to the external browser.
        request?.url?.toString()?.let(onOpenLink)
        return true
    }

    private fun blocked(): WebResourceResponse = WebResourceResponse(null, null, null)

    companion object {
        /**
         * S30a HARDENED settings — the upstream reading pipeline runs JS with
         * a JavascriptInterface bridge and conditional file access; this
         * configuration is deliberately distinct and MUST NOT be reused there.
         */
        fun harden(settings: WebSettings) {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
        }

        fun assetLoaderFor(
            context: Context,
            articleDir: java.io.File,
        ): WebViewAssetLoader =
            WebViewAssetLoader.Builder()
                .setHttpAllowed(false)
                // Registered BEFORE the article-dir handler: WebViewAssetLoader
                // tries handlers in registration order, so the longer bundled
                // font prefix wins over the general article prefix.
                .addPathHandler(
                    OfflineArticleOrigin.FONTS_PATH_PREFIX,
                    BundledReadingFontHandler(context),
                )
                .addPathHandler(
                    OfflineArticleOrigin.PATH_PREFIX,
                    WebViewAssetLoader.InternalStoragePathHandler(context, articleDir),
                )
                .build()

        /**
         * Rewrites the served `index.html` body with [style] injected before
         * the head close tag (prepended when the document has no head close
         * marker), preserving the original response metadata.
         */
        internal fun injectStyle(response: WebResourceResponse, style: String): WebResourceResponse {
            val html = response.data.use { it.readBytes() }.toString(Charsets.UTF_8)
            val block = "<style>\n$style</style>\n"
            val marker = "</head>"
            val idx = html.indexOf(marker)
            val out =
                if (idx >= 0) html.substring(0, idx) + block + html.substring(idx) else block + html
            val reason = response.reasonPhrase?.takeIf { it.isNotEmpty() } ?: "OK"
            return WebResourceResponse(
                response.mimeType ?: "text/html",
                response.encoding ?: "utf-8",
                response.statusCode,
                reason,
                response.responseHeaders ?: emptyMap(),
                ByteArrayInputStream(out.toByteArray(Charsets.UTF_8)),
            )
        }
    }
}

/**
 * Serves ONLY the whitelisted bundled reading-font files (APK `res/font`)
 * under [OfflineArticleOrigin.FONTS_PATH_PREFIX]. Any other path returns
 * null, so the loader falls through to the article-dir handler and missing
 * files stay blocked. Local resources only — structurally zero network.
 */
internal class BundledReadingFontHandler(
    private val context: Context,
) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val resId = FONT_RESOURCES[path] ?: return null
        return WebResourceResponse(
            "font/ttf",
            null,
            200,
            "OK",
            emptyMap(),
            context.resources.openRawResource(resId),
        )
    }

    companion object {
        /** Whitelist: served file name to bundled font resource. */
        val FONT_RESOURCES: Map<String, Int> =
            mapOf(
                "google_sans_flex.ttf" to R.font.google_sans_flex,
                "literata.ttf" to R.font.literata,
                "literata_italic.ttf" to R.font.literata_italic,
                "source_serif_4.ttf" to R.font.source_serif_4,
                "source_serif_4_italic.ttf" to R.font.source_serif_4_italic,
                "atkinson_hyperlegible.ttf" to R.font.atkinson_hyperlegible,
                "atkinson_hyperlegible_italic.ttf" to R.font.atkinson_hyperlegible_italic,
                "lexend.ttf" to R.font.lexend,
            )
    }
}
