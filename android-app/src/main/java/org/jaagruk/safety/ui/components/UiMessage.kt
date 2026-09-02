package org.jaagruk.safety.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * A message a view model wants shown, as a resource reference rather than text.
 *
 * View models never build user-facing strings. If they did, every error message in the app would be English
 * only — and the workers this is for read Hindi or Santali, or do not read at all. Holding a resource id
 * means the string is resolved in the composable, against the locale currently in force, and `MissingTranslation`
 * catches an untranslated one at build time.
 *
 * [args] carries the substitutions. Kept as `List<Any>` because the platform's own formatter takes varargs of
 * `Any`, and constraining it further would mean a wrapper per argument type for no benefit.
 */
data class UiMessage(
    @StringRes val resId: Int,
    val args: List<Any> = emptyList(),
    val tone: BannerTone = BannerTone.INFO,
) {
    companion object {
        fun info(@StringRes resId: Int, vararg args: Any) =
            UiMessage(resId, args.toList(), BannerTone.INFO)

        fun success(@StringRes resId: Int, vararg args: Any) =
            UiMessage(resId, args.toList(), BannerTone.SUCCESS)

        fun warning(@StringRes resId: Int, vararg args: Any) =
            UiMessage(resId, args.toList(), BannerTone.WARNING)

        fun error(@StringRes resId: Int, vararg args: Any) =
            UiMessage(resId, args.toList(), BannerTone.ERROR)
    }
}

@Composable
fun UiMessage.resolve(): String =
    if (args.isEmpty()) stringResource(resId) else stringResource(resId, *args.toTypedArray())

/** Renders a [UiMessage] as a banner, or nothing when there is no message. */
@Composable
fun MessageBanner(message: UiMessage?, contentDescription: String) {
    if (message == null) return
    StatusBanner(
        text = message.resolve(),
        tone = message.tone,
        pictogramDescription = contentDescription,
    )
}
