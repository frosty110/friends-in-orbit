package app.orbit.ui.screens.note

import android.app.Application
import android.app.NotificationManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.NotificationCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.data.entity.CallDirection
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeNoteRepository
import app.orbit.domain.callEventFixture
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.usecase.AddNoteUseCase
import app.orbit.notify.NotificationIds
import app.orbit.ui.screens.picker.PickerCommitBus
import app.orbit.ui.theme.OrbitTheme
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * NOTIF-16: the note page is the question the notification after a call
 * asks, so opening it withdraws that person's notification, whichever way
 * it was reached (the card after a call, Home's stack, the notification).
 * Until 2026-10-07 only the card withdrew it, so opening the page from Home
 * left the same question in the shade. Another person's notification stays.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class PostCallNoteScreenTest {

    @get:Rule val compose = createComposeRule()

    private val now: Instant = Instant.parse("2026-10-07T18:00:00Z")
    private val clock = TestClock(now)

    @Test
    fun opening_the_page_withdraws_that_persons_notification_only() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NotificationIds.postCall(7L), notification(context))
        manager.notify(NotificationIds.postCall(8L), notification(context))
        val vm = PostCallNoteViewModel(
            SavedStateHandle(
                mapOf(PostCallNoteViewModel.ARG_CONTACT_ID to "7", PostCallNoteViewModel.ARG_CALL_EVENT_ID to "41"),
            ),
            FakeContactRepository(listOf(contactFixture(id = 7L, displayName = "Kai Mensah"))),
            FakeCallEventRepository(
                listOf(
                    callEventFixture(
                        id = 41L,
                        contactId = 7L,
                        occurredAt = now.minus(Duration.ofMinutes(30)),
                        direction = CallDirection.OUTGOING,
                        durationSeconds = 14 * 60,
                    ),
                ),
            ),
            AddNoteUseCase(FakeNoteRepository(), clock),
            PickerCommitBus(),
            clock,
        )

        compose.setContent { OrbitTheme { PostCallNoteScreen(onLeave = {}, vm = vm) } }
        compose.waitForIdle()

        assertEquals(
            listOf(NotificationIds.postCall(8L)),
            Shadows.shadowOf(manager).activeNotifications.map { it.id },
            "the page's person's notification is withdrawn; another person's stays",
        )
    }

    private fun notification(context: Application) =
        NotificationCompat.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("A call")
            .build()
}
