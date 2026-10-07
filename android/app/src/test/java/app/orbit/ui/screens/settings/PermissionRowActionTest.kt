package app.orbit.ui.screens.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the permission-row behavior contract:
 *
 *   - Granted rows are quiet ("Allowed", no action — nothing to do).
 *   - Denied rows fire the runtime permission launcher (the OS dialog can
 *     still be shown while shouldShowRequestPermissionRationale is true).
 *   - Permanently-denied rows deep-link to Android Settings (a launcher
 *     request would be silently auto-denied).
 *
 * The labels are string resources; they are resolved under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PermissionRowActionTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `granted maps to no action`() {
        assertEquals(PermissionRowAction.None, PermissionStatus.Granted.rowAction())
    }

    @Test
    fun `denied maps to runtime request`() {
        assertEquals(PermissionRowAction.Request, PermissionStatus.Denied.rowAction())
    }

    @Test
    fun `permanently denied maps to Android Settings deep link`() {
        assertEquals(
            PermissionRowAction.OpenSettings,
            PermissionStatus.PermanentlyDenied.rowAction(),
        )
    }

    @Test
    fun `labels stay quiet and honest`() {
        assertEquals("Allowed", context.getString(PermissionStatus.Granted.labelRes))
        assertEquals("Not allowed", context.getString(PermissionStatus.Denied.labelRes))
        assertEquals("Off in your phone's settings", context.getString(PermissionStatus.PermanentlyDenied.labelRes))
    }
}
