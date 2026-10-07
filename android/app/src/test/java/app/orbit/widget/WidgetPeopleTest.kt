package app.orbit.widget

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import app.orbit.MainActivity
import app.orbit.R
import app.orbit.domain.contactFixture
import app.orbit.domain.usecase.WidgetSurfaceData
import app.orbit.nav.AppLinks
import app.orbit.nav.Routes
import app.orbit.ui.theme.ThemeSettings
import app.orbit.ui.theme.orbitWidgetAvatarTones
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * [widgetPeople] is the one path both widgets take from [WidgetSurfaceData]
 * to what they draw: the name, the face, and what a tap does. These pin it
 * where the Glance composition cannot be inspected on the JVM (WIDGET-04,
 * WIDGET-08). Until 2026-10-06 the widget tests exercised a parallel copy of
 * this logic that production never called.
 *
 * Robolectric for the Context: the masked name is a string resource, the
 * dial intent resolves against the package manager, and the monogram is a
 * bitmap. People here have no photo, so the face is the monogram path and no
 * content resolver is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WidgetPeopleTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val tones = orbitWidgetAvatarTones(ThemeSettings.DEFAULT)

    private val alice = contactFixture(id = 1L, displayName = "Alice Example")
    private val bob = contactFixture(id = 2L, displayName = "Bob Example")
    private val carol = contactFixture(id = 3L, displayName = "Carol Example")

    private val three = WidgetSurfaceData(
        primary = alice,
        alternatives = listOf(bob, carol),
        listIdByContactId = mapOf(1L to 10L, 2L to 20L, 3L to 10L),
    )

    private suspend fun people(
        data: WidgetSurfaceData,
        max: Int,
        minimalMode: Boolean = false,
    ) = widgetPeople(
        context = context,
        data = data,
        max = max,
        minimalMode = minimalMode,
        tones = tones,
    )

    // ─── WIDGET-04: minimal mode ─────────────────────────────────────────

    /**
     * Every name is the curtain's word, from the same resource the in-app
     * curtain reads; every face is gone.
     */
    @Test
    fun minimalMode_masksEveryNameWithTheCurtainWord_andDropsEveryFace() = runBlocking {
        val masked = context.getString(R.string.components_curtain_contact)

        val people = people(three, max = 3, minimalMode = true)

        assertEquals(3, people.size)
        people.forEach { person ->
            assertEquals(masked, person.name)
            assertNull(person.face, "minimal mode draws a silhouette, never a face")
        }
        val realNames = listOf(alice, bob, carol).map { it.displayName }
        assertTrue(
            people.none { it.name in realNames },
            "no real name may reach the widget in minimal mode",
        )
    }

    /** Off, the widget writes the display name and prepares a face. */
    @Test
    fun minimalModeOff_writesTheDisplayName_andPreparesAFace() = runBlocking {
        val people = people(three, max = 3)

        assertEquals(
            listOf("Alice Example", "Bob Example", "Carol Example"),
            people.map { it.name },
        )
        people.forEach { person ->
            val face = assertNotNull(person.face, "a face for ${person.name}")
            assertNull(face.photo, "nobody here has a photo")
            assertNotNull(face.monogram, "so the monogram is drawn")
        }
    }

    // ─── max: "Next call" takes one, "Call suggestions" up to three ──────

    @Test
    fun max_truncatesToTheLeadForNextCall_andKeepsOrderForSuggestions() = runBlocking {
        val one = people(three, max = 1)
        val all = people(three, max = 3)

        assertEquals(listOf("Alice Example"), one.map { it.name })
        assertEquals(
            listOf("Alice Example", "Bob Example", "Carol Example"),
            all.map { it.name },
        )
    }

    @Test
    fun nobodySurfaced_givesNoPeople() = runBlocking {
        val nobody = WidgetSurfaceData(primary = null, alternatives = emptyList())

        val people = people(nobody, max = 3)

        assertTrue(people.isEmpty())
    }

    // ─── WIDGET-08: only Call dials, and only where a dialer exists ──────

    /**
     * Robolectric ships no dialer: the widget then offers no Call control
     * rather than one that does nothing.
     */
    @Test
    fun dialIntent_isNull_withoutADialer() = runBlocking {
        val people = people(three, max = 3)

        people.forEach { assertNull(it.dialIntent, "no dialer, no Call for ${it.name}") }
    }

    @Test
    fun dialIntent_opensTheDialerWithTheNumber_onceADialerIsRegistered() = runBlocking {
        registerDialer()

        val lead = people(three, max = 1).single()

        val dial = assertNotNull(lead.dialIntent, "a Call control once ACTION_DIAL resolves")
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("tel", dial.data?.scheme)
        assertEquals(alice.phoneNumber, dial.data?.schemeSpecificPart)
    }

    // ─── WIDGET-08: a tap opens the list's deck, or Orbit when unknown ───

    @Test
    fun openIntent_carriesTheSurfacingListsDeck_whenTheListIsKnown() = runBlocking {
        val people = people(three, max = 3)

        val routes = people.map { it.openIntent.getStringExtra(AppLinks.EXTRA_NAVIGATE_TO) }
        assertEquals(listOf(Routes.card("10"), Routes.card("20"), Routes.card("10")), routes)
        people.forEach {
            assertEquals(MainActivity::class.java.name, it.openIntent.component?.className)
        }
    }

    @Test
    fun openIntent_isThePlainMainActivityIntent_whenNoListSurfacedThem() = runBlocking {
        val data = WidgetSurfaceData(
            primary = alice,
            alternatives = emptyList(),
            listIdByContactId = emptyMap(),
        )

        val lead = people(data, max = 1).single()

        assertEquals(MainActivity::class.java.name, lead.openIntent.component?.className)
        assertFalse(
            lead.openIntent.hasExtra(AppLinks.EXTRA_NAVIGATE_TO),
            "no route: Orbit opens where it starts",
        )
    }

    /**
     * Robolectric has no dialer; register one so ACTION_DIAL resolves (as
     * ListPromptWorkerTest does).
     */
    private fun registerDialer() {
        val pm = Shadows.shadowOf(context.packageManager)
        val dialer = ComponentName("com.example.dialer", "com.example.dialer.DialActivity")
        pm.addActivityIfNotPresent(dialer)
        pm.addIntentFilterForActivity(
            dialer,
            IntentFilter(Intent.ACTION_DIAL).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("tel")
            },
        )
    }
}
