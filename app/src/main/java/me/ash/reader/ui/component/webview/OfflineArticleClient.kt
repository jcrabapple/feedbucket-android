package me.ash.reader.ui.component.webview

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * S30a — the hardened client for downloaded articles. `shouldInterceptRequest`
 * delegates ONLY to the [WebViewAssetLoader]; every non-appassets request
 * (including javascript:/data:/file:/intent:/content:) returns an EMPTY
 * response — the upstream raw-HttpURLConnection fetch path (WebViewClient.kt
 * in the online renderer) is structurally unreachable here. Link taps route
 * to the external browser, never in-view navigation.
 */
class OfflineArticleClient(
    private val assetLoader: WebViewAssetLoader,
    private val onOpenLink: (String) -> Unit,
    /** Recording seam: every intercepted URL, in order (tests + diagnostics). */
    val onRequest: ((String) -> Unit)? = null,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        val url = request?.url?.toString() ?: return blocked()
        onRequest?.invoke(url)
        if (!OfflineArticleOrigin.isAppAssetsUrl(url)) return blocked()
        return request?.url?.let { assetLoader.shouldInterceptRequest(it) } ?: blocked()
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
                .addPathHandler(
                    OfflineArticleOrigin.PATH_PREFIX,
                    WebViewAssetLoader.InternalStoragePathHandler(context, articleDir),
                )
                .build()
    }
}
