package app.orbit.launcher

import android.app.Application
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import app.orbit.MainActivity
import app.orbit.nav.AppLinks
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LAUNCH-01: the long-press shortcuts are fixed words that name nobody, and
 * they carry an action for MainActivity to resolve, never a route that could
 * go stale or a list id baked in at publish time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class LauncherShortcutsTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun publishes_callNext_thenSearch_withFixedLabels() {
        LauncherShortcuts.publish(context)

        val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context).sortedBy { it.rank }
        assertEquals(
            listOf(LauncherShortcuts.ID_CALL_NEXT, LauncherShortcuts.ID_SEARCH),
            shortcuts.map { it.id },
        )
        assertEquals(listOf("Call next", "Search"), shortcuts.map { it.shortLabel.toString() })
        assertEquals(
            listOf("Call the next person", "Search your people"),
            shortcuts.map { it.longLabel.toString() },
        )
        assertEquals(
            listOf(AppLinks.ACTION_CALL_NEXT, AppLinks.ACTION_SEARCH),
            shortcuts.map { it.intent.action },
        )
        shortcuts.forEach { shortcut ->
            assertEquals(MainActivity::class.java.name, shortcut.intent.component?.className)
            assertEquals(null, shortcut.intent.getStringExtra(AppLinks.EXTRA_NAVIGATE_TO))
        }
    }

    @Test
    fun publishingTwice_leavesOneSet() {
        LauncherShortcuts.publish(context)
        LauncherShortcuts.publish(context)

        assertEquals(2, ShortcutManagerCompat.getDynamicShortcuts(context).size)
    }
}
