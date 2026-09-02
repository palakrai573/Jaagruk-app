package org.jaagruk.safety.ar

import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Session
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jaagruk.safety.BuildConfig
import org.jaagruk.safety.data.db.SiteAnchorEntity
import kotlin.coroutines.resume

/**
 * Hosts and resolves the site anchors a supervisor places during a scan.
 *
 * **What this delivers and what it does not**, stated plainly because the difference is the difference
 * between a real claim and a demo:
 *
 *  * With an ARCore Cloud Anchor API key configured, an anchor a supervisor places is hosted and can be
 *    resolved by *any* handset at that site, for as long as the TTL allows. That is the version where a
 *    site scan is done once and every worker's drill uses it.
 *  * With no key — which is the default, because a Google Cloud key cannot be committed to a public
 *    repository — anchors are **session-scoped**. The supervisor scans, and drills run against those
 *    anchors until the AR session ends. The scan does not survive an app restart or move to another
 *    phone.
 *
 * The app reports which of the two it is doing, and the certificate's `SITE_SCANNED_AR` flag is only set
 * when anchors genuinely resolved for that run. A session-scoped scan still earns the flag for drills in
 * that session, because the drill really did happen against the real doorway — it just cannot be
 * repeated tomorrow without re-scanning.
 */
class AnchorResolver {

    /** Result of asking for an anchor to be hosted. */
    sealed interface HostResult {
        /** Hosted in the cloud. [cloudAnchorId] is resolvable from other handsets. */
        data class Hosted(val cloudAnchorId: String, val anchor: Anchor) : HostResult

        /**
         * Placed locally only. Valid for this session; not resolvable elsewhere.
         *
         * Returned when no API key is configured, and when hosting fails for any reason. Falling back is
         * right: a supervisor standing in a haulage road with no signal should still be able to scan, and
         * losing the whole scan because a cloud call failed would be the worse outcome.
         */
        data class SessionScoped(val anchor: Anchor, val reason: String) : HostResult

        data class Failed(val reason: String) : HostResult
    }

    val cloudAnchorsEnabled: Boolean get() = BuildConfig.CLOUD_ANCHORS_ENABLED

    /**
     * Hosts [anchor] for [ttlDays].
     *
     * 365 days, the ARCore maximum, because a site layout changes on the order of months and a scan that
     * silently expired after a day would leave a site "scanned" in the database and unanchored in
     * reality — the worst of both, because nobody would know to re-scan.
     */
    suspend fun host(session: Session, anchor: Anchor, ttlDays: Int = MAX_TTL_DAYS): HostResult {
        if (!cloudAnchorsEnabled) {
            return HostResult.SessionScoped(
                anchor,
                "no ARCore Cloud Anchor key is configured in this build",
            )
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                session.hostCloudAnchorAsync(anchor, ttlDays) { cloudAnchorId, state ->
                    if (!continuation.isActive) return@hostCloudAnchorAsync
                    if (state == Anchor.CloudAnchorState.SUCCESS && !cloudAnchorId.isNullOrBlank()) {
                        continuation.resume(HostResult.Hosted(cloudAnchorId, anchor))
                    } else {
                        Log.w(TAG, "cloud anchor hosting returned $state")
                        continuation.resume(
                            HostResult.SessionScoped(anchor, describe(state)),
                        )
                    }
                }
            } catch (e: Exception) {
                // Thrown when Cloud Anchor mode was not enabled on the session, or Play Services is
                // stripped. Both are device facts, not user errors.
                Log.w(TAG, "cloud anchor hosting unavailable", e)
                if (continuation.isActive) {
                    continuation.resume(
                        HostResult.SessionScoped(anchor, e.localizedMessage ?: "hosting unavailable"),
                    )
                }
            }
        }
    }

    /** Resolves one stored anchor. Null means it could not be resolved on this device right now. */
    suspend fun resolve(session: Session, cloudAnchorId: String): Anchor? {
        if (!cloudAnchorsEnabled || cloudAnchorId.isBlank()) return null

        return suspendCancellableCoroutine { continuation ->
            try {
                session.resolveCloudAnchorAsync(cloudAnchorId) { anchor, state ->
                    if (!continuation.isActive) return@resolveCloudAnchorAsync
                    if (state == Anchor.CloudAnchorState.SUCCESS && anchor != null) {
                        continuation.resume(anchor)
                    } else {
                        Log.i(TAG, "resolve of $cloudAnchorId returned $state")
                        continuation.resume(null)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "cloud anchor resolve unavailable", e)
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    /**
     * Resolves a site's stored anchors, reporting each outcome.
     *
     * Partial success is normal and useful. Three of five anchors resolving still makes the drill more
     * site-specific than none, and [ScenePlacer] decides per step whether the resolved set is enough to
     * claim a site-scanned presentation — so a partial resolve degrades honestly instead of either
     * failing outright or overclaiming.
     */
    suspend fun resolveAll(
        session: Session,
        anchors: List<SiteAnchorEntity>,
        onResolved: (SiteAnchorEntity, Anchor) -> Unit,
        onFailed: (SiteAnchorEntity) -> Unit,
    ): Int {
        var resolved = 0
        for (stored in anchors) {
            val cloudId = stored.cloudAnchorId
            if (cloudId.isNullOrBlank()) {
                // Session-scoped anchors from a previous session are simply gone. Reported so the UI can
                // prompt for a re-scan rather than silently running a generic drill at a site the
                // database claims is scanned.
                onFailed(stored)
                continue
            }
            val anchor = resolve(session, cloudId)
            if (anchor != null) {
                onResolved(stored, anchor)
                resolved++
            } else {
                onFailed(stored)
            }
        }
        return resolved
    }

    private fun describe(state: Anchor.CloudAnchorState?): String = when (state) {
        Anchor.CloudAnchorState.ERROR_NOT_AUTHORIZED ->
            "the ARCore Cloud Anchor key was rejected"

        Anchor.CloudAnchorState.ERROR_RESOURCE_EXHAUSTED ->
            "the Cloud Anchor quota for this project is exhausted"

        Anchor.CloudAnchorState.ERROR_HOSTING_DATASET_PROCESSING_FAILED ->
            "not enough visual detail to host an anchor here; scan more of the surroundings"

        Anchor.CloudAnchorState.ERROR_HOSTING_SERVICE_UNAVAILABLE ->
            "no connection to the Cloud Anchor service"

        Anchor.CloudAnchorState.ERROR_INTERNAL ->
            "ARCore reported an internal error while hosting"

        null -> "hosting did not report a result"

        else -> "hosting failed ($state)"
    }

    private companion object {
        const val TAG = "AnchorResolver"

        /** ARCore's maximum TTL. */
        const val MAX_TTL_DAYS = 365
    }
}
