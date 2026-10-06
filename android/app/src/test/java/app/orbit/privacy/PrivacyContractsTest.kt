package app.orbit.privacy

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import app.orbit.ui.util.dialPhoneNumber
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * PRIV-05: Orbit never places a call itself. Both halves of that promise are
 * checkable on the JVM: the merged manifest holds no CALL_PHONE permission,
 * and the one dialling helper starts ACTION_DIAL with a tel: URI, so the user
 * places the call in the phone's dialer, where they can still change their
 * mind.
 *
 * PRIV-04 (FLAG_SECURE on MainActivity) is not pinned here on purpose: the
 * flag is set only when `BuildConfig.DEBUG` is false (MainActivity.onCreate),
 * and the unit tests run against the debug variant, so a Robolectric
 * assertion would only ever pass on a variant CI never runs. It stays the
 * documented device check in features/privacy-and-lock/README.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PrivacyContractsTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun theMergedManifest_requestsNoCallPhonePermission() {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toList()

        // A guard against an empty read passing vacuously: the manifest does
        // declare the permissions Orbit needs.
        assertTrue(Manifest.permission.READ_CALL_LOG in requested, "the merged manifest was read: $requested")
        assertFalse(Manifest.permission.CALL_PHONE in requested, "Orbit must not be able to place a call itself")
    }

    @Test
    fun dialPhoneNumber_opensTheDialer_withTheNumber_andPlacesNoCall() {
        // Robolectric has no dialer, and the helper checks for one before
        // starting the intent (it toasts "No dialer app installed" otherwise),
        // so register one that answers tel: links.
        val dialer = ComponentName("com.android.dialer", "com.android.dialer.DialtactsActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(dialer)
        shadowOf(app.packageManager).addIntentFilterForActivity(
            dialer,
            IntentFilter(Intent.ACTION_DIAL).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("tel")
            },
        )

        // The helper is only ever called with a composable's LocalContext,
        // which inside MainActivity is the Activity, so it sets no
        // FLAG_ACTIVITY_NEW_TASK; the framework refuses an Application
        // context without that flag. ComponentActivity is in the debug
        // manifest through ui-test-manifest.
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        activity.dialPhoneNumber("+14155551234")

        val started = assertNotNull(shadowOf(activity).nextStartedActivity, "the dialer is opened")
        assertEquals(Intent.ACTION_DIAL, started.action, "ACTION_DIAL, never ACTION_CALL")
        assertEquals("tel", started.data?.scheme)
        assertEquals("+14155551234", started.data?.schemeSpecificPart)
    }
}
