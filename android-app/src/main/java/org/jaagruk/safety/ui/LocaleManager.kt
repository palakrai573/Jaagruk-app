package org.jaagruk.safety.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Per-app language, independent of the device setting.
 *
 * This matters more here than in most apps. A shared site handset is passed between workers during a shift
 * — one reads Hindi, the next only speaks Santali — and changing the *device* language for each of them is
 * both slow and rude to whoever picks it up next. `AppCompatDelegate.setApplicationLocales` changes only
 * this app, persists across restarts, and on Android 13+ is handled by the platform itself.
 *
 * `sat` is Santali, written in Ol Chiki. The plain `sat` tag is used rather than `sat-Olck` because a script
 * subtag makes some devices fail to match the resource folder and fall back to English — for the users who can
 * least afford it. Ol Chiki glyphs come from Noto Sans Ol Chiki, which AOSP has shipped since Android 10, and
 * that is one of the reasons `minSdk` is 29.
 */
object LocaleManager {

    const val ENGLISH = "en"
    const val HINDI = "hi"
    const val SANTALI = "sat"

    val supported: List<String> = listOf(ENGLISH, HINDI, SANTALI)

    /** Display name for each language, written in that language. */
    fun endonym(tag: String): String = when (tag) {
        HINDI -> "हिन्दी"
        SANTALI -> "ᱥᱟᱱᱛᱟᱲᱤ"
        else -> "English"
    }

    fun apply(tag: String) {
        val normalised = if (tag in supported) tag else ENGLISH
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(normalised))
    }

    /** The tag currently in force, or [ENGLISH] when the app has not chosen one. */
    fun current(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return ENGLISH
        val language = locales[0]?.language ?: return ENGLISH
        return if (language in supported) language else ENGLISH
    }

    /** Follows the device language again. Used by the "reset" action in settings. */
    fun clear() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }
}
