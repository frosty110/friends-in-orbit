package app.orbit.ui.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One list of import windows and one wording for both screens that offer
 * them (onb-9): Settings and onboarding used to keep their own, with
 * onboarding missing the 30-day window and saying "90 days" where Settings
 * said "3 months".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ImportRangeTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `four windows, from a month to a year`() {
        assertEquals(listOf(30, 90, 180, 365), IMPORT_DAY_OPTIONS)
    }

    @Test
    fun `each window reads in months or years, not days`() {
        assertEquals(
            listOf("1 month", "3 months", "6 months", "1 year"),
            IMPORT_DAY_OPTIONS.map { importRangeLabel(it).asString(context) },
        )
    }

    @Test
    fun `an unknown count falls back to days`() {
        assertEquals("45 days", importRangeLabel(45).asString(context))
        assertEquals("1 day", importRangeLabel(1).asString(context))
    }
}
