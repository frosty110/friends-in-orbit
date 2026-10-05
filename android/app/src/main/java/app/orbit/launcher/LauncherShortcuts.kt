package app.orbit.launcher

import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.orbit.MainActivity
import app.orbit.R
import app.orbit.nav.AppLinks
import timber.log.Timber

/**
 * LAUNCH-01: the shortcuts a long press on Orbit's icon offers. Two, both with
 * fixed words: "Call next" (the person the widgets show first, on their deck)
 * and "Search".
 *
 * ### Why fixed words, and no list or person shortcuts
 * A launcher's long-press menu, and any shortcut pinned from it, sits outside
 * the app, where the privacy curtain cannot reach (features/privacy-and-lock).
 * Anyone holding the unlocked phone can read it, and launchers may also show
 * shortcuts in search and suggestions. List names are the most
 * relationship-revealing words Orbit holds ("Recovery support"), so a
 * shortcut per list would publish exactly what the curtain hides; a shortcut
 * per person would do the same for names. So the labels never change and
 * never name anyone, and "next" is worked out inside Orbit at the moment of
 * the tap ([AppLinks.callNextRoute]), never written into the shortcut.
 *
 * ### Why dynamic, not shortcuts.xml
 * A static shortcut has to spell its target package in XML, and the debug
 * build's package differs (`applicationIdSuffix ".debug"`), so a single XML
 * file would point one of the two builds at an app that is not installed.
 * Published from code, the intent is built against whichever package is
 * running. [publish] runs on every cold start and only writes when the set
 * differs, because background writes count against Android's shortcut rate
 * limit. Raise [VERSION] when an icon or intent changes, so installed copies
 * pick the change up (labels are compared directly).
 */
object LauncherShortcuts {

    const val ID_CALL_NEXT = "call_next"
    const val ID_SEARCH = "search"

    fun publish(context: Context) {
        // The system can refuse (rate limit, a locked work profile); the app is
        // whole without shortcuts, so record it and carry on. Nothing here is
        // user data, so the log line is free of it by construction.
        val published = runCatching {
            val wanted = shortcuts(context)
            val current = ShortcutManagerCompat.getDynamicShortcuts(context)
            current.map(::fingerprint) == wanted.map(::fingerprint) ||
                ShortcutManagerCompat.setDynamicShortcuts(context, wanted)
        }.getOrDefault(false)
        if (!published) Timber.tag(TAG).w("shortcuts_not_published")
    }

    private fun shortcuts(context: Context): List<ShortcutInfoCompat> = listOf(
        shortcut(
            context,
            id = ID_CALL_NEXT,
            shortLabel = R.string.shortcut_call_next_short,
            longLabel = R.string.shortcut_call_next_long,
            icon = R.drawable.ic_shortcut_call_next,
            action = AppLinks.ACTION_CALL_NEXT,
            rank = 0,
        ),
        shortcut(
            context,
            id = ID_SEARCH,
            shortLabel = R.string.shortcut_search_short,
            longLabel = R.string.shortcut_search_long,
            icon = R.drawable.ic_shortcut_search,
            action = AppLinks.ACTION_SEARCH,
            rank = 1,
        ),
    )

    private fun shortcut(
        context: Context,
        id: String,
        shortLabel: Int,
        longLabel: Int,
        icon: Int,
        action: String,
        rank: Int,
    ): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(context.getString(shortLabel))
            .setLongLabel(context.getString(longLabel))
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(Intent(context, MainActivity::class.java).setAction(action))
            .setRank(rank)
            .setExtras(PersistableBundle().apply { putInt(KEY_VERSION, VERSION) })
            .build()

    private fun fingerprint(shortcut: ShortcutInfoCompat): List<Any?> = listOf(
        shortcut.id,
        shortcut.shortLabel.toString(),
        shortcut.longLabel?.toString(),
        shortcut.extras?.getInt(KEY_VERSION),
    )

    private const val KEY_VERSION = "orbit_shortcut_version"
    private const val VERSION = 1
    private const val TAG = "shortcuts"
}
