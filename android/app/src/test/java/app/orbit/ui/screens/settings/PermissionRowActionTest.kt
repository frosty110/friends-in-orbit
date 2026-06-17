package app.orbit.ui.screens.settings

import kotlin.test.assertEquals
import org.junit.Test

/**
 * Pins the permission-row behavior contract:
 *
 *   - Granted rows are quiet ("Allowed", no action — nothing to do).
 *   - Denied rows fire the runtime permission launcher (the OS dialog can
 *     still be shown while shouldShowRequestPermissionRationale is true).
 *   - Permanently-denied rows deep-link to Android Settings (a launcher
 *     request would be silently auto-denied).
 */
class PermissionRowActionTest {

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
        assertEquals("Allowed", PermissionStatus.Granted.label)
        assertEquals("Not allowed", PermissionStatus.Denied.label)
        assertEquals("Off in your phone's settings", PermissionStatus.PermanentlyDenied.label)
    }
}
