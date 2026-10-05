package app.orbit.ui.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.R
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UiText resolves against real resources, plurals included, nested args first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class UiTextTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `plain text is returned as is`() {
        assertEquals("Inner orbit", UiText.Plain("Inner orbit").asString(context))
    }

    @Test
    fun `string resources resolve`() {
        assertEquals("Orbit", UiText.res(R.string.app_name).asString(context))
    }

    @Test
    fun `plurals pick the right form`() {
        assertEquals("1 call", UiText.plural(R.plurals.onb_sync_calls, 1, 1).asString(context))
        assertEquals("12 calls", UiText.plural(R.plurals.onb_sync_calls, 12, 12).asString(context))
    }

    @Test
    fun `equal content compares equal, so tests can assert intent`() {
        assertEquals(UiText.res(R.string.app_name), UiText.Res(R.string.app_name, emptyList()))
    }
}
