package me.ash.reader.ui.component.webview

/**
 * S30a — the offline render origin contract as pure predicates (unit-tested
 * on the JVM; the instrumented OfflineRenderTest asserts them again on the
 * real WebView request path).
 *
 * Downloaded articles render ONLY from
 * `https://appassets.androidplatform.net/app/...`, served by
 * [androidx.webkit.WebViewAssetLoader] + `InternalStoragePathHandler` rooted
 * at `filesDir/downloads/<accountId>/<sha256(articleId)>/`. Everything else
 * is blocked with an empty response; dangerous schemes never load nor
 * navigate.
 */
object OfflineArticleOrigin {

    const val ORIGIN = "https://appassets.androidplatform.net"
    const val PATH_PREFIX = "/app/"

    /** The only URLs the hardened renderer may serve. */
    fun isAppAssetsUrl(url: String): Boolean = url.startsWith(ORIGIN + PATH_PREFIX)

    /**
     * S30a scheme block: these never load and never navigate on the offline
     * path. (Any non-appassets URL is already blocked; this documents and
     * unit-pins the dangerous schemes called out by the spec.)
     */
    val BLOCKED_SCHEMES = listOf("javascript:", "data:", "file:", "intent:", "content:")

    fun isBlockedScheme(url: String): Boolean =
        BLOCKED_SCHEMES.any { url.lowercase().startsWith(it) }
}
