package org.jaagruk.safety.ar

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLES30
import android.util.Log
import com.google.ar.core.ArCoreApk
import org.jaagruk.core.assessment.ArPresentation

/**
 * Decides which AR path this handset can actually run.
 *
 * This is not a nice-to-have branch. Roughly a third of mid-range Android stock sold in this market is
 * not ARCore certified, and those are disproportionately the handsets a contract worker in Dhanbad
 * actually owns. An app that requires ARCore would be invisible on Play to exactly the audience the
 * problem statement is about, so ARCore is declared `optional` in the manifest and there are three
 * documented fallbacks below it.
 *
 * The chosen mode is signed into the certificate, so it also has to be honest: a run that fell back to
 * sensor-only tracking must never claim it happened in a site-scanned scene.
 */
object ArAvailability {

    /** What this device can do, most capable first. */
    enum class Capability {
        /** Full ARCore with plane detection and Cloud Anchors. */
        ARCORE_READY,

        /** ARCore is supported but the APK needs installing or updating. */
        ARCORE_NEEDS_INSTALL,

        /** No ARCore. Camera plus rotation sensors: markers on a virtual sphere around the worker. */
        SENSOR_FALLBACK,

        /** No usable camera or no GLES3. Flat pictogram drill; still fully assessable. */
        PICTOGRAM_ONLY,
        ;

        val usesCamera: Boolean get() = this != PICTOGRAM_ONLY

        /**
         * The presentation a run on this device may claim.
         *
         * `SITE_SCANNED` is deliberately absent: it depends on anchors actually resolving at run time,
         * not on device capability, and is decided by [ScenePlacer] once resolution has succeeded.
         */
        fun basePresentation(): ArPresentation = when (this) {
            ARCORE_READY, ARCORE_NEEDS_INSTALL -> ArPresentation.ARCORE_GENERIC
            SENSOR_FALLBACK -> ArPresentation.SENSOR_FALLBACK
            PICTOGRAM_ONLY -> ArPresentation.PICTOGRAM_2D
        }
    }

    /**
     * Synchronous, cheap capability probe.
     *
     * `checkAvailability` can return `UNKNOWN_CHECKING` on first call while the Play Services query is
     * in flight. That is reported as [Capability.SENSOR_FALLBACK] rather than blocking: a worker at the
     * start of a shift should get a drill immediately, and the next probe upgrades them. Blocking a
     * splash screen on a Play Services round trip is how an app becomes unusable on a slow handset.
     */
    fun probe(context: Context): Capability {
        if (!GlCapability.supportsGles3(context)) return Capability.PICTOGRAM_ONLY
        if (!hasCamera(context)) return Capability.PICTOGRAM_ONLY

        return try {
            when (ArCoreApk.getInstance().checkAvailability(context)) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED -> Capability.ARCORE_READY

                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
                -> Capability.ARCORE_NEEDS_INSTALL

                ArCoreApk.Availability.UNKNOWN_CHECKING -> Capability.SENSOR_FALLBACK

                ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE,
                ArCoreApk.Availability.UNKNOWN_ERROR,
                ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
                -> Capability.SENSOR_FALLBACK

                else -> Capability.SENSOR_FALLBACK
            }
        } catch (e: Exception) {
            // Play Services missing entirely, or a stripped ROM. Common on grey-market handsets, and
            // survivable: the sensor path needs neither.
            Log.i(TAG, "ARCore availability check failed; using the sensor fallback", e)
            Capability.SENSOR_FALLBACK
        }
    }

    /**
     * Asks ARCore to install itself.
     *
     * Returns true when the caller should wait for the activity to be resumed and probe again. Never
     * forces the issue: declining leaves the worker on the sensor path with a full drill, not a dead
     * end, which is the difference between a fallback and an excuse.
     */
    fun requestInstall(activity: Activity, userRequestedInstall: Boolean): Boolean = try {
        when (ArCoreApk.getInstance().requestInstall(activity, userRequestedInstall)) {
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> true
            ArCoreApk.InstallStatus.INSTALLED -> false
            else -> false
        }
    } catch (e: Exception) {
        Log.i(TAG, "ARCore install request declined or unavailable", e)
        false
    }

    private fun hasCamera(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    private const val TAG = "ArAvailability"
}

/**
 * GLES3 capability, checked without creating a context.
 *
 * The AR scene is a custom GLES3 billboard renderer. Sceneform is deprecated, Filament and SceneView
 * churn their APIs every few releases, and a glTF pipeline would spend the frame budget this app needs
 * for latency measurement. Billboards are enough: the assessment asks "which of these do you point
 * at", not "does this look photoreal".
 */
object GlCapability {

    fun supportsGles3(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        // reqGlEsVersion packs the major version in the high 16 bits.
        return (manager.deviceConfigurationInfo.reqGlEsVersion shr 16) >= 3
    }

    /**
     * Largest texture edge this GL context supports, queried once a context exists.
     *
     * Pictogram atlases are sized against this. A 2048-pixel cap is real on older Mali parts, and
     * silently exceeding it yields a black quad rather than an error — which in a drill looks like a
     * missing answer option.
     */
    fun maxTextureSize(): Int {
        val result = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, result, 0)
        return result[0].coerceAtLeast(1024)
    }
}
