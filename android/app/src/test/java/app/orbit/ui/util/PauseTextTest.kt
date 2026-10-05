package app.orbit.ui.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.domain.model.PauseDuration
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How a pause reads in its snackbar, single and bulk ([pausedSnackbar],
 * [pausedPeopleSnackbar]). These assertions lived on `PauseDuration` and
 * `BulkPauseUseCase` while the domain built the English; the sentence is now
 * one resource per duration (strings_components.xml), resolved here against
 * the real English resources.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PauseTextTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun UiText.text(): String = asString(context)

    @Test
    fun `one person reads for 1 week, for 1 month, or indefinitely`() {
        assertEquals("Paused Sam for 1 week", pausedSnackbar("Sam", PauseDuration.OneWeek).text())
        assertEquals("Paused Sam for 1 month", pausedSnackbar("Sam", PauseDuration.OneMonth).text())
        assertEquals("Paused Sam indefinitely", pausedSnackbar("Sam", PauseDuration.Indefinite).text())
    }

    @Test
    fun `several people read with an honest singular`() {
        assertEquals("Paused 2 people for 1 week", pausedPeopleSnackbar(2, PauseDuration.OneWeek).text())
        // "1 person", never "1 people".
        assertEquals("Paused 1 person for 1 month", pausedPeopleSnackbar(1, PauseDuration.OneMonth).text())
    }

    // Regression: the bulk label was built as "for {label}", which read
    // "Paused 3 contacts for indefinitely".
    @Test
    fun `an indefinite pause takes no preposition`() {
        assertEquals("Paused 3 people indefinitely", pausedPeopleSnackbar(3, PauseDuration.Indefinite).text())
    }

    // A name the screen doesn't have yet nests as a UiText stand-in.
    @Test
    fun `a stand-in name resolves inside the sentence`() {
        val standIn = UiText.res(app.orbit.R.string.components_curtain_contact)
        assertEquals("Paused Contact for 1 week", pausedSnackbar(standIn, PauseDuration.OneWeek).text())
    }
}
