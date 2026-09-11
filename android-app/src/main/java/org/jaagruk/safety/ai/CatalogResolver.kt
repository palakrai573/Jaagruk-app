package org.jaagruk.safety.ai

import android.content.Context
import android.content.res.Configuration
import org.jaagruk.core.ai.AiLanguage
import org.jaagruk.core.ai.StringResolver
import org.jaagruk.safety.ui.LocaleManager
import org.jaagruk.safety.ui.components.CatalogStrings
import java.util.Locale

/**
 * Resolves `:core` catalog keys to localised text for the AI layer.
 *
 * `:core` stores keys, never prose, so a coaching task has to be handed real words from somewhere. This
 * uses the same [CatalogStrings] lookup the drill UI already uses, which matters: the wording the model
 * is shown is exactly the wording the worker saw on screen. Resolving it a second, separate way would
 * let the two drift, and a coaching answer explaining a differently-worded question is worse than no
 * coaching.
 *
 * ## Why it builds its own configuration context
 *
 * The per-app language is set through `AppCompatDelegate.setApplicationLocales`. On API 33+ the platform
 * applies it to the whole app; below that AppCompat wraps *activity* contexts, and the application
 * context can still be carrying the device locale. A resolver injected with the application context
 * would then feed the model Hindi prompts while the worker is reading Santali, or the reverse. So the
 * locale is taken from [LocaleManager], which reads the authoritative value, and a configuration context
 * is derived from it.
 *
 * ## Why a missing key resolves to empty
 *
 * [CatalogStrings.resolve] echoes the key when there is no string, which is right on screen — a tester
 * seeing `opt_raise_alarm` knows exactly what is missing — and wrong here, because a bare key inside a
 * prompt is a token the model would earnestly try to explain. Empty makes the task factory reject the
 * step instead, so no coaching is offered rather than nonsense coaching.
 */
class CatalogResolver(private val context: Context) : StringResolver {

    private var cachedTag: String? = null
    private var cachedContext: Context = context

    private fun localised(): Context {
        val tag = LocaleManager.current()
        if (tag != cachedTag) {
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocale(Locale.forLanguageTag(tag))
            cachedContext = context.createConfigurationContext(configuration)
            cachedTag = tag
        }
        return cachedContext
    }

    override fun resolve(key: String): String {
        val localised = localised()
        val id = CatalogStrings.resourceId(localised, key)
        return if (id == 0) "" else localised.getString(id)
    }
}

/**
 * The app's current language as the AI layer understands it.
 *
 * Null for Santali, which is the whole point of it being nullable. No model in this size class generates
 * Ol Chiki, and quietly answering in English would put text a Santali speaker cannot read where an answer
 * should be. Null becomes [org.jaagruk.core.ai.AiCapability.LANGUAGE_UNSUPPORTED], and the UI says so
 * while pointing at the authored translations and pictograms, which are complete.
 */
fun currentAiLanguage(): AiLanguage? = AiLanguage.fromTagOrNull(LocaleManager.current())
