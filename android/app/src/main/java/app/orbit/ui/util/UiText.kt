package app.orbit.ui.util

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext

/**
 * Text a ViewModel (or a worker) wants shown, without needing a Context to
 * build it (UX rubric 3.4: every string in resources, with plurals).
 *
 * ViewModels emit `UiText`; composables resolve it with [asString], and
 * notifications and widgets with [asString] on a Context. Arguments may be
 * plain values or other `UiText`s ("Sarah will come up again {in 2 weeks}"),
 * resolved depth first. Data classes, so tests can compare what a ViewModel
 * meant to say (`UiText.res(R.string.x, "Sarah")`) without resolving it.
 *
 * [Plain] is for text that is data, not copy: a person's or list's name the
 * user typed. Never wrap English copy in [Plain]; that is the bug this type
 * exists to prevent.
 */
sealed interface UiText {

    data class Plain(val value: String) : UiText

    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText

    fun asString(context: Context): String = when (this) {
        is Plain -> value
        is Res -> context.getString(id, *resolve(args, context))
        is Plural -> context.resources.getQuantityString(id, count, *resolve(args, context))
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())

        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText = Plural(id, count, args.toList())

        private fun resolve(args: List<Any>, context: Context): Array<Any> =
            args.map { if (it is UiText) it.asString(context) else it }.toTypedArray()
    }
}

/** Resolves [this] in composition, against the current configuration's locale. */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String = asString(LocalContext.current)
