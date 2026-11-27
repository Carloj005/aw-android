package com.carloj005.awanime.data.network

import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp infrastructure.
 * All specialized clients derive from [baseClient] via `newBuilder()`, sharing a single [Dispatcher]
 * thread pool while preserving cookie isolation and dedicated media/download connection pools.
 */
class HttpClients {

    val sharedDispatcher = Dispatcher()
    val dohDns = DohFallbackDns(sharedDispatcher)
    private val sessionCookieJar = MemoryCookieJar()

    private val httpsOnlyInterceptor = Interceptor { chain ->
        val req = chain.request()
        if (req.url.scheme != "https") {
            throw IOException("Connessione HTTP non cifrata bloccata: ${req.url.host}")
        }
        chain.proceed(req)
    }

    val baseClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(sharedDispatcher)
        .dns(dohDns)
        .cookieJar(CookieJar.NO_COOKIES)
        .addInterceptor(httpsOnlyInterceptor)
        .followRedirects(true)
        .followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Client con cookie di sessione isolati per AnimeUnity (Laravel / Inertia). */
    val sessionClient: OkHttpClient = baseClient.newBuilder()
        .cookieJar(sessionCookieJar)
        .build()

    /** Client senza auto-redirect per ispezionare header `Location` di download diretti. */
    val noRedirectSessionClient: OkHttpClient = sessionClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Client isolato SENZA cookie per domini terzi (VixCloud). */
    val externalClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()

    /** Client rapido SENZA cookie per API di skip (AniSkip / Anime-Skip). */
    val fastExternalClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    /** Client dedicato a Media3 ExoPlayer (pool compatto anti-503 su CDN nginx). */
    val mediaClient: OkHttpClient = baseClient.newBuilder()
        .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Client separato per download MP4 in background (evita contesa col player). */
    val downloadClient: OkHttpClient = baseClient.newBuilder()
        .connectionPool(ConnectionPool(2, 1, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    /** Client per caricamento locandine/banner via Coil. */
    val imageClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}
