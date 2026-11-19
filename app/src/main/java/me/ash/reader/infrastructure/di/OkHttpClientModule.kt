/*
 * Feeder: Android RSS reader app
 * https://gitlab.com/spacecowboy/Feeder
 *
 * Copyright (C) 2022  Jonas Kalderstam
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.ash.reader.infrastructure.di

import android.content.Context
import android.security.KeyChain
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.ash.reader.BuildConfig
import me.ash.reader.domain.model.download.DownloadPolicy
import me.ash.reader.domain.model.extract.ExtractPolicy
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.internal.platform.Platform
import java.io.File
import java.net.Socket
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.X509KeyManager

/** S28 — dedicated client for article downloads (connect 15s/read 30s/call 120s). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadHttpClient

/**
 * E18 (SPEC-EXTRACT-v1) — dedicated client for extraction fetches:
 * connect 15s / read 30s / call 60s, redirects NOT followed (the X3
 * coordinator walks them manually under the per-hop E17 SSRF guard),
 * NO HTTP cache (an extraction is a fresh publisher fetch every time).
 * The EXISTING [UserAgentInterceptor] is attached unchanged — the app's
 * regular UA, never a crawler/spoofed UA (E1 hard wall). TLS uses the
 * platform trust store (full certificate + hostname verification) like
 * every other client — see [cachingHttpClient].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ExtractHttpClient

/**
 * Provides singleton [OkHttpClient] for the application.
 */
@Module
@InstallIn(SingletonComponent::class)
object OkHttpClientModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
    ): OkHttpClient = cachingHttpClient(
        context = context,
        cacheDirectory = context.cacheDir.resolve("http")
    ).newBuilder()
        .addNetworkInterceptor(UserAgentInterceptor)
        .build()

    /**
     * S28 — dedicated download client with the exact download timeouts
     * (connect 15 s, read 30 s, call 120 s). No HTTP cache: downloaded bytes
     * belong to the durable store, not the evictable cache. Redirects are
     * NOT followed automatically: DownloadService walks image-asset hops
     * manually under the E17 SsrfGuard (parity with the extract client), so
     * a redirect into a loopback/LAN range is rejected instead of fetched.
     */
    @Provides
    @Singleton
    @DownloadHttpClient
    fun provideDownloadOkHttpClient(
        @ApplicationContext context: Context,
    ): OkHttpClient =
        cachingHttpClient(
            context = context,
            cacheDirectory = null,
            connectTimeoutSecs = DownloadPolicy.CONNECT_TIMEOUT_SECS,
            readTimeoutSecs = DownloadPolicy.READ_TIMEOUT_SECS,
        ).newBuilder()
            .callTimeout(DownloadPolicy.CALL_TIMEOUT_SECS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addNetworkInterceptor(UserAgentInterceptor)
            .build()

    /**
     * E18 — dedicated extraction client with the exact extraction timeouts
     * (connect 15 s, read 30 s, call 60 s), NO cache, and manual redirects
     * (`followRedirects(false)` + `followSslRedirects(false)` — the X3
     * coordinator walks hops under the per-hop E17 guard). UA interceptor
     * unchanged (E1: the app's existing User-Agent, never a spoofed one).
     */
    @Provides
    @Singleton
    @ExtractHttpClient
    fun provideExtractOkHttpClient(
        @ApplicationContext context: Context,
    ): OkHttpClient =
        cachingHttpClient(
            context = context,
            cacheDirectory = null,
            connectTimeoutSecs = ExtractPolicy.CONNECT_TIMEOUT_SECS,
            readTimeoutSecs = ExtractPolicy.READ_TIMEOUT_SECS,
        ).newBuilder()
            .callTimeout(ExtractPolicy.CALL_TIMEOUT_SECS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addNetworkInterceptor(UserAgentInterceptor)
            .build()
}

/**
 * Builds the shared [OkHttpClient]. Standard HTTPS uses OkHttp's default
 * platform trust store, so certificate AND hostname verification are ON for
 * every caller. There is no insecure / permissive-trust mode: a grep of the
 * codebase found no self-signed-server toggle or dialog, so none is offered.
 * [setupSsl] is invoked ONLY to attach a client certificate for mutual TLS
 * when [clientCertificateAlias] is set; even then the SERVER chain is still
 * verified against the platform trust store.
 */
fun cachingHttpClient(
    context: Context,
    cacheDirectory: File? = null,
    cacheSize: Long = 10L * 1024L * 1024L,
    connectTimeoutSecs: Long = 30L,
    readTimeoutSecs: Long = 30L,
    clientCertificateAlias: String? = null,
): OkHttpClient {
    val builder: OkHttpClient.Builder = OkHttpClient.Builder()

    if (cacheDirectory != null) {
        builder.cache(Cache(cacheDirectory, cacheSize))
    }

    builder
        .connectTimeout(connectTimeoutSecs, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSecs, TimeUnit.SECONDS)
        .followRedirects(true)

    if (!clientCertificateAlias.isNullOrBlank()) {
        builder.setupSsl(context, clientCertificateAlias)
    }

    return builder.build()
}

/**
 * Attaches a client certificate (mutual TLS) from the Android [KeyChain] when
 * [clientCertificateAlias] is set. The SERVER certificate chain is ALWAYS
 * verified against [Platform.get().platformTrustManager]; there is no
 * insecure / permissive-trust branch and no custom hostname verifier, so the
 * default (strict) hostname verification stays in force.
 */
fun OkHttpClient.Builder.setupSsl(
    context: Context,
    clientCertificateAlias: String?,
) {
    try {
        val clientKeyManager = clientCertificateAlias?.let { clientAlias ->
            object : X509KeyManager {
                override fun getClientAliases(keyType: String?, issuers: Array<Principal>?) =
                    throw UnsupportedOperationException("getClientAliases")

                override fun chooseClientAlias(
                    keyType: Array<String>?,
                    issuers: Array<Principal>?,
                    socket: Socket?
                ) = clientCertificateAlias

                override fun getServerAliases(keyType: String?, issuers: Array<Principal>?) =
                    throw UnsupportedOperationException("getServerAliases")

                override fun chooseServerAlias(
                    keyType: String?,
                    issuers: Array<Principal>?,
                    socket: Socket?
                ) = throw UnsupportedOperationException("chooseServerAlias")

                override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
                    return if (alias == clientAlias) KeyChain.getCertificateChain(context, clientAlias) else null
                }

                override fun getPrivateKey(alias: String?): PrivateKey? {
                    return if (alias == clientAlias) KeyChain.getPrivateKey(context, clientAlias) else null
                }
            }
        }

        // Platform trust store: the SERVER certificate chain is always
        // verified. There is no insecure / permissive-trust branch (the app
        // exposes no self-signed opt-in), so standard HTTPS keeps full
        // certificate and hostname verification.
        val trustManager = Platform.get().platformTrustManager()

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(arrayOf(clientKeyManager), arrayOf(trustManager), null)
        val sslSocketFactory = sslContext.socketFactory

        sslSocketFactory(sslSocketFactory, trustManager)
    } catch (e: NoSuchAlgorithmException) {
        // ignore
    } catch (e: KeyManagementException) {
        // ignore
    }
}

object UserAgentInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(
            chain.request()
                .newBuilder()
                .header("User-Agent", USER_AGENT_STRING)
                .build()
        )
    }
}

const val USER_AGENT_STRING = BuildConfig.USER_AGENT_STRING