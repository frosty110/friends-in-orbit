package app.orbit.nav

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.orbit.MainActivity
import app.orbit.domain.contactFixture
import app.orbit.domain.usecase.WidgetSurfaceData
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How surfaces outside the app open a place inside it (D-17, WIDGET-08,
 * LAUNCH-01).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AppLinksTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private val kai = contactFixture(id = 7L, displayName = "Kai Nakamura")

    @Test
    fun callNext_opensTheDeckOfTheListThatSurfacedThePerson() {
        val next = WidgetSurfaceData(
            primary = kai,
            alternatives = emptyList(),
            listIdByContactId = mapOf(7L to 3L),
        )
        assertEquals(Routes.card("3"), AppLinks.callNextRoute(next))
    }

    @Test
    fun callNext_opensNothingInParticular_whenNobodyIsNext() {
        assertNull(AppLinks.callNextRoute(WidgetSurfaceData(primary = null, alternatives = emptyList())))
    }

    @Test
    fun callNext_opensNothingInParticular_whenThePersonsListIsUnknown() {
        assertNull(AppLinks.callNextRoute(WidgetSurfaceData(primary = kai, alternatives = emptyList())))
    }

    @Test
    fun openRoute_carriesTheRoute_toMainActivity_withoutStackingASecondCopy() {
        val intent = AppLinks.openRoute(context, Routes.card("3"))
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(Routes.card("3"), intent.getStringExtra(AppLinks.EXTRA_NAVIGATE_TO))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    /** The shortcuts build on the same intent, so a warm tap reaches onNewIntent too. */
    @Test
    fun launch_carriesTheSharedFlags_andNoRoute() {
        val intent = AppLinks.launch(context)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertNull(intent.getStringExtra(AppLinks.EXTRA_NAVIGATE_TO))
    }

    // Where a launch lands (the pure half of MainActivity.routeFrom).

    @Test
    fun landingFor_extraWinsOverAction() {
        val intent = AppLinks.openRoute(context, Routes.card("3")).setAction(AppLinks.ACTION_SEARCH)
        // A nudge's route is opened as is, and never waits on the onboarding read.
        assertEquals(AppLinks.Landing.Open(Routes.card("3")), AppLinks.landingFor(intent, onboardingComplete = true))
        assertEquals(AppLinks.Landing.Open(Routes.card("3")), AppLinks.landingFor(intent, onboardingComplete = false))
    }

    @Test
    fun landingFor_callNext_beforeOnboarding_isNothing() {
        val intent = AppLinks.launch(context).setAction(AppLinks.ACTION_CALL_NEXT)
        assertEquals(AppLinks.Landing.Nothing, AppLinks.landingFor(intent, onboardingComplete = false))
        assertEquals(AppLinks.Landing.CallNext, AppLinks.landingFor(intent, onboardingComplete = true))
    }

    @Test
    fun landingFor_search_opensGlobalSearch() {
        val intent = AppLinks.launch(context).setAction(AppLinks.ACTION_SEARCH)
        assertEquals(AppLinks.Landing.Search, AppLinks.landingFor(intent, onboardingComplete = true))
        assertEquals(AppLinks.Landing.Nothing, AppLinks.landingFor(intent, onboardingComplete = false))
    }

    @Test
    fun landingFor_unrelatedAction_isNothing() {
        val icon = AppLinks.launch(context).setAction(Intent.ACTION_MAIN)
        assertEquals(AppLinks.Landing.Nothing, AppLinks.landingFor(icon, onboardingComplete = true))
        assertEquals(AppLinks.Landing.Nothing, AppLinks.landingFor(null, onboardingComplete = true))
    }

    /**
     * Android compares PendingIntents without extras. Two people on one widget
     * opening two lists must not collapse into one PendingIntent.
     */
    @Test
    fun openRoute_intentsForDifferentRoutes_areDistinctToPendingIntent() {
        val a = AppLinks.openRoute(context, Routes.card("3"))
        val b = AppLinks.openRoute(context, Routes.card("4"))
        assertTrue(!a.filterEquals(b))
        assertNotEquals(a.identifier, b.identifier)
    }
}
