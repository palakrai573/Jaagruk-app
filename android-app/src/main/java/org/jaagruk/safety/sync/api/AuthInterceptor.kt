package org.jaagruk.safety.sync.api

import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Response
import java.net.HttpURLConnection

/**
 * Attaches the bearer token and refreshes it once on a 401.
 *
 * The refresh is single-flight. A sync pass fires several requests, and because the backend rotates
 * refresh tokens on use, letting each request refresh independently would have all but one burn a
 * token that had already been replaced — logging the supervisor out mid-upload.
 *
 * A second consecutive 401 is not retried. The queue is left intact and the session is cleared, so
 * the next attempt happens after a re-login rather than in a refresh loop.
 */
class AuthInterceptor(
    private val session: SessionStore,
    private val refreshTokens: suspend (String) -> TokenResponse?,
    private val onSessionLost: () -> Unit,
) : Interceptor {

    private companion object {
        const val TAG = "AuthInterceptor"
        const val HEADER = "Authorization"
    }

    private val refreshMutex = Mutex()

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // The login and refresh calls must not carry a stale token, or a rejected refresh would
        // look like an expired access token and recurse.
        val path = original.url.encodedPath
        if (path.endsWith("/auth/login") || path.endsWith("/auth/refresh")) {
            return chain.proceed(original)
        }

        val token = session.accessToken
        val request = if (token.isNullOrBlank()) {
            original
        } else {
            original.newBuilder().header(HEADER, "Bearer $token").build()
        }

        val response = chain.proceed(request)
        if (response.code != HttpURLConnection.HTTP_UNAUTHORIZED) return response

        // Close the 401 body before reissuing, or OkHttp leaks the connection.
        response.close()

        val refreshed = runBlocking {
            refreshMutex.withLock {
                // Another request may have refreshed while this one waited on the lock.
                val current = session.accessToken
                if (current != null && current != token) return@withLock true

                val refreshToken = session.refreshToken ?: return@withLock false
                val newTokens = try {
                    refreshTokens(refreshToken)
                } catch (e: Exception) {
                    Log.w(TAG, "token refresh failed", e)
                    null
                }
                if (newTokens == null) {
                    false
                } else {
                    session.save(newTokens)
                    true
                }
            }
        }

        if (!refreshed) {
            Log.i(TAG, "session lost; clearing tokens but keeping the sync queue")
            session.clear()
            onSessionLost()
            // Reissued without a token so the caller receives a clean 401 to act on.
            return chain.proceed(original.newBuilder().removeHeader(HEADER).build())
        }

        val newToken = session.accessToken
        val retried = original.newBuilder()
            .apply { if (!newToken.isNullOrBlank()) header(HEADER, "Bearer $newToken") }
            .build()
        return chain.proceed(retried)
    }
}
