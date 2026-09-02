package org.jaagruk.safety.ui.components

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.jaagruk.safety.BuildConfig

/**
 * Resolves a scenario catalog string key to a localised string.
 *
 * Keys come from `:core` at runtime — `step_fire_detect_alarm_prompt`, `opt_raise_alarm`, and 220 more —
 * so there is no compile-time `R.string` symbol to reference. `getIdentifier` is normally a smell; here it
 * is the only option that does not involve a hand-maintained map of 222 entries that would drift the moment
 * somebody adds a scenario option.
 *
 * The safety net is elsewhere and it is real: `:core:dumpCatalogManifest` writes out every key the catalog
 * needs, `MissingTranslation` is a fatal lint check, and [CatalogStrings.audit] fails a debug build that is
 * missing one. So a missing key is caught in the build rather than discovered by a worker looking at a blank
 * button.
 */
object CatalogStrings {

    private val cache = HashMap<String, Int>()

    /**
     * @return the resource id, or 0 when the key has no string.
     *
     * Cached because a nine-step scenario resolves roughly forty keys per screen and `getIdentifier` walks
     * the resource table each time.
     */
    fun resourceId(context: Context, key: String): Int = cache.getOrPut(key) {
        context.resources.getIdentifier(key, "string", context.packageName)
    }

    fun resolve(context: Context, key: String): String {
        val id = resourceId(context, key)
        if (id != 0) return context.getString(id)

        // The key is shown rather than an empty string. A worker seeing `opt_raise_alarm` is confusing; a
        // worker seeing a blank button cannot proceed at all, and a tester seeing the key knows exactly
        // what is missing.
        Log.w(TAG, "no string resource for catalog key '$key'")
        return key
    }

    /**
     * Checks every key the catalog needs.
     *
     * Called from [org.jaagruk.safety.ui.MainActivity] in debug builds only. Two reasons it runs at all
     * rather than being left to lint: lint checks that a *declared* string is translated, not that a key
     * the catalog references was declared; and this runs against the merged resources of the actual
     * variant, so a resource-shrinking mistake surfaces too.
     *
     * @return keys with no string resource. Empty is the expected result.
     */
    fun audit(context: Context, requiredKeys: List<String>): List<String> =
        requiredKeys.filter { resourceId(context, it) == 0 }

    private const val TAG = "CatalogStrings"
}

/** Composable accessor. Remembered per key so a recomposition does not re-resolve. */
@Composable
fun catalogString(key: String): String {
    val context = LocalContext.current
    return remember(key, context) { CatalogStrings.resolve(context, key) }
}

/**
 * Whether to run the resource audit.
 *
 * Debug only. A release build that failed to start because of a missing translation would be a worse
 * outcome than the blank label it was protecting against.
 */
internal val runResourceAudit: Boolean get() = BuildConfig.DEBUG
