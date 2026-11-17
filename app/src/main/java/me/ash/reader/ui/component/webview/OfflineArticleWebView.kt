package me.ash.reader.ui.component.webview

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import me.ash.reader.domain.model.download.DownloadStore
import me.ash.reader.infrastructure.preference.LocalOpenLink
import me.ash.reader.infrastructure.preference.LocalOpenLinkSpecificBrowser
import me.ash.reader.ui.ext.openURL

/** Routing target for a downloaded (ready) article (S27a/S30a). */
data class OfflineArticleTarget(
    val accountId: Int,
    val articleId: String,
) {
    fun pageUrl(): String = OfflineArticleOrigin.ORIGIN + OfflineArticleOrigin.PATH_PREFIX + "index.html"

    fun articleDir(context: android.content.Context): File =
        File(
            File(context.filesDir, "downloads"),
            "${accountId}/${DownloadStore.articleKey(articleId)}",
        )
}

/**
 * S30a — offline render entry for downloaded articles. Serves
 * `filesDir/downloads/<accountId>/<sha256(articleId)>/` exclusively through
 * the WebViewAssetLoader appassets origin with hardened settings; issues
 * ZERO network calls by construction (every non-appassets request returns an
 * empty response).
 */
@Composable
fun OfflineArticleWebView(
    target: OfflineArticleTarget,
    modifier: Modifier = Modifier,
    /** Recording seam (OfflineRenderTest): every intercepted request URL. */
    requestListener: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val openLink = LocalOpenLink.current
    val openLinkSpecificBrowser = LocalOpenLinkSpecificBrowser.current

    val webView =
        remember(target) {
            WebView(context).apply {
                OfflineArticleClient.harden(settings)
                webViewClient =
                    OfflineArticleClient(
                        assetLoader =
                            OfflineArticleClient.assetLoaderFor(
                                context,
                                target.articleDir(context),
                            ),
                        onOpenLink = { url -> context.openURL(url, openLink, openLinkSpecificBrowser) },
                        onRequest = requestListener,
                    )
            }
        }

    AndroidView(
        modifier = modifier,
        factory = { webView },
        update = { it.loadUrl(target.pageUrl()) },
    )
}
