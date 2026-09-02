package org.jaagruk.safety.di

import android.content.Context
import android.util.Log
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.jaagruk.safety.BuildConfig
import org.jaagruk.safety.sync.api.AuthInterceptor
import org.jaagruk.safety.sync.api.JaagrukApi
import org.jaagruk.safety.sync.api.RefreshRequest
import org.jaagruk.safety.sync.api.SessionStore
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * HTTP stack.
 *
 * Tuned for a bad uplink rather than a datacentre. The timeouts are long because a mine-site connection
 * genuinely takes fifteen seconds to establish, and a short timeout there turns a working upload into a
 * retry loop that never completes. They are not unbounded, because a half-open connection that never
 * errors would leave the sync worker holding a wakelock.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        // The server adds fields between releases; a handset that has not been updated in six months
        // must keep working rather than fail to parse a bootstrap.
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun sessionStore(@ApplicationContext context: Context): SessionStore = SessionStore(context)

    /**
     * The auth interceptor, wired to a lazily resolved API.
     *
     * `Lazy` breaks a genuine cycle: refreshing a token needs the API, and building the API needs the
     * interceptor. The alternative — a second Retrofit instance purely for refresh — would mean two
     * connection pools and two sets of timeouts to keep in step.
     */
    @Provides
    @Singleton
    fun authInterceptor(
        session: SessionStore,
        api: Lazy<JaagrukApi>,
    ): AuthInterceptor = AuthInterceptor(
        session = session,
        refreshTokens = { refreshToken ->
            val response = api.get().refresh(RefreshRequest(refreshToken))
            if (response.isSuccessful) response.body() else null
        },
        onSessionLost = {
            // Deliberately just a log line. Clearing the queue or prompting here would be wrong: the
            // records are already signed and safe, and the next login delivers them.
            Log.i(TAG, "supervisor session ended; records stay queued until the next sign-in")
        },
    )

    @Provides
    @Singleton
    fun okHttpClient(
        @ApplicationContext context: Context,
        authInterceptor: AuthInterceptor,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // On by default, and worth keeping: a mine-site Wi-Fi drops connections constantly, and a
            // transparent retry of an idempotent upload costs nothing because the batch id makes a
            // replay a no-op server-side.
            .retryOnConnectionFailure(true)
            .addInterceptor(authInterceptor)
            .cache(
                Cache(
                    directory = File(context.cacheDir, "http"),
                    maxSize = HTTP_CACHE_BYTES,
                ),
            )

        if (BuildConfig.VERBOSE_LOGGING) {
            builder.addInterceptor(debugLoggingInterceptor())
        }

        return builder.build()
    }

    /**
     * Minimal request logging for debug builds.
     *
     * Hand-rolled rather than OkHttp's `HttpLoggingInterceptor`, which is a `debugImplementation`
     * dependency and therefore not on the release compile classpath — referencing it from shared code
     * would break the release build. This also logs no bodies, so a token or a worker's name never lands
     * in logcat.
     */
    private fun debugLoggingInterceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val startedAt = System.nanoTime()
        val response = chain.proceed(request)
        val millis = (System.nanoTime() - startedAt) / 1_000_000
        Log.d(
            TAG,
            "${request.method} ${request.url.encodedPath} -> ${response.code} in ${millis}ms",
        )
        response
    }

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(normaliseBaseUrl(BuildConfig.API_BASE_URL))
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun jaagrukApi(retrofit: Retrofit): JaagrukApi = retrofit.create(JaagrukApi::class.java)

    /**
     * Retrofit requires a trailing slash and throws otherwise.
     *
     * Fixed here rather than trusted, because the base URL comes from a Gradle property or
     * `local.properties` that somebody types by hand at a site, and a crash on launch is a poor way to
     * report a missing slash.
     */
    private fun normaliseBaseUrl(raw: String): String =
        if (raw.endsWith('/')) raw else "$raw/"

    private const val TAG = "JaagrukHttp"

    /** Fifteen seconds to connect: a site uplink genuinely takes this long to come up. */
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 60L

    /** Bounded overall, so a half-open connection cannot hold the sync worker's wakelock forever. */
    private const val CALL_TIMEOUT_SECONDS = 120L

    private const val HTTP_CACHE_BYTES = 4L * 1024L * 1024L
}
