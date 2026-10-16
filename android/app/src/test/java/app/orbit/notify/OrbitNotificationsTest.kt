package app.orbit.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NOTIF-17 (rules.md Design 9): no dot on Orbit's icon. Both channels are
 * created with `setShowBadge(false)` under new IDs, so current installs get it
 * too, and the user's choices on the old channels carry over once.
 *
 * The old IDs are written out here rather than read from the code: they are
 * what is on people's phones, so a test that followed a renamed constant
 * would stop checking them.
 *
 * Robolectric's notification manager keeps the channel objects it is given
 * and, like Android, lets a channel that already exists change only its name,
 * its description and a lower importance, so a second start that would lower
 * a carried importance shows up here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OrbitNotificationsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager = context.getSystemService()!!

    private fun oldChannel(id: String, importance: Int = NotificationManager.IMPORTANCE_DEFAULT, setUp: NotificationChannel.() -> Unit = {}) {
        manager.createNotificationChannel(NotificationChannel(id, "Old", importance).apply(setUp))
    }

    private fun nudges(): NotificationChannel =
        assertNotNull(manager.getNotificationChannel(OrbitNotifications.CHANNEL_LIST_PROMPT), "the nudge channel")

    private fun afterCall(): NotificationChannel =
        assertNotNull(manager.getNotificationChannel(OrbitNotifications.CHANNEL_AFTER_CALL), "the after-call channel")

    // ── no dot ────────────────────────────────────────────────────────────────

    @Test
    fun `after an update every channel has no dot and the old IDs are gone`() {
        oldChannel(OLD_LIST_PROMPT)
        oldChannel(OLD_AFTER_CALL)

        OrbitNotifications.ensureChannels(context)

        assertEquals(
            setOf("orbit.list_prompt.v2", "orbit.after_call.v2"),
            manager.notificationChannels.map { it.id }.toSet(),
        )
        assertTrue(manager.notificationChannels.none { it.canShowBadge() }, "no channel puts a dot on the icon")
        assertNull(manager.getNotificationChannel(OLD_LIST_PROMPT))
        assertNull(manager.getNotificationChannel(OLD_AFTER_CALL))
    }

    @Test
    fun `a new install gets both channels at the defaults, with no dot`() {
        OrbitNotifications.ensureChannels(context)

        for (channel in listOf(nudges(), afterCall())) {
            assertFalse(channel.canShowBadge(), channel.id)
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance, channel.id)
            assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, channel.sound, channel.id)
        }
        assertEquals("List nudges", nudges().name.toString())
        assertEquals("A gentle nudge when you have someone to call.", nudges().description)
        assertEquals("After a call", afterCall().name.toString())
        assertEquals("A prompt to write a note after a call with someone on your lists.", afterCall().description)
    }

    @Test
    fun `the nudge and the notification after a call carry no number and no badge icon`() {
        OrbitNotifications.ensureChannels(context)
        val nudge = NudgeNotification.build(
            context, listId = 3L, listName = "Family", dueCount = 2, subject = null, face = null, accent = 0,
        )
        val afterCall = PostCallNotification.build(
            context, contactId = 1L, callEventId = 2L, firstName = "Kai", accent = 0, timeoutAfter = Duration.ofHours(1),
        )

        for (posted in listOf(nudge, nudge.publicVersion, afterCall, afterCall.publicVersion)) {
            assertEquals(0, posted.number)
            assertEquals(Notification.BADGE_ICON_NONE, posted.badgeIconType)
        }
    }

    // ── importance carries ────────────────────────────────────────────────────

    @Test
    fun `an after-call channel turned off stays off under its new ID`() {
        oldChannel(OLD_AFTER_CALL, NotificationManager.IMPORTANCE_NONE)

        OrbitNotifications.ensureChannels(context)

        assertEquals(NotificationManager.IMPORTANCE_NONE, afterCall().importance)
        // The gate PostCallNotifier asks before it posts.
        assertFalse(context.isChannelEnabled(OrbitNotifications.CHANNEL_AFTER_CALL))
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, nudges().importance, "the nudges are not touched")
    }

    @Test
    fun `a lowered nudge channel stays lowered`() {
        oldChannel(OLD_LIST_PROMPT, NotificationManager.IMPORTANCE_LOW)

        OrbitNotifications.ensureChannels(context)

        assertEquals(NotificationManager.IMPORTANCE_LOW, nudges().importance)
    }

    @Test
    fun `a raised nudge channel keeps its level on every later start`() {
        oldChannel(OLD_LIST_PROMPT, NotificationManager.IMPORTANCE_HIGH)

        OrbitNotifications.ensureChannels(context)
        OrbitNotifications.ensureChannels(context)

        // Submitting the channel again at the default would lower it here.
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nudges().importance)
    }

    // ── the other settings carry ──────────────────────────────────────────────

    @Test
    fun `vibration switched off stays off, even with a pattern set`() {
        // Setting a pattern switches vibration on by itself, so the order of
        // the carry is what keeps the user's off.
        oldChannel(OLD_LIST_PROMPT) {
            vibrationPattern = PATTERN
            enableVibration(false)
        }

        OrbitNotifications.ensureChannels(context)

        assertFalse(nudges().shouldVibrate(), "vibration off stays off")
        assertContentEquals(PATTERN, nudges().vibrationPattern)
    }

    @Test
    fun `a silent sound, vibration on and lights carry`() {
        oldChannel(OLD_LIST_PROMPT) {
            setSound(null, null)
            enableVibration(true)
            enableLights(true)
            lightColor = GREEN
        }

        OrbitNotifications.ensureChannels(context)

        val nudges = nudges()
        assertNull(nudges.sound, "a silent channel stays silent")
        assertTrue(nudges.shouldVibrate())
        assertTrue(nudges.shouldShowLights())
        assertEquals(GREEN, nudges.lightColor)
        assertFalse(nudges.canShowBadge())
    }

    @Test
    fun `a picked sound and a vibration pattern carry`() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        oldChannel(OLD_AFTER_CALL) {
            setSound(PICKED_SOUND, attributes)
            vibrationPattern = PATTERN
        }

        OrbitNotifications.ensureChannels(context)

        val afterCall = afterCall()
        assertEquals(PICKED_SOUND, afterCall.sound)
        assertEquals(attributes, afterCall.audioAttributes)
        assertTrue(afterCall.shouldVibrate())
        assertContentEquals(PATTERN, afterCall.vibrationPattern)
    }

    @Test
    fun `a sound Android refuses resets to the default and the rest still carries`() {
        oldChannel(OLD_AFTER_CALL, NotificationManager.IMPORTANCE_LOW) {
            setSound(PICKED_SOUND, Notification.AUDIO_ATTRIBUTES_DEFAULT)
            enableVibration(true)
        }

        OrbitNotifications.ensureChannels(context) { channel ->
            if (channel.sound == PICKED_SOUND) throw SecurityException("no read access")
            createNotificationChannel(channel)
        }

        val afterCall = afterCall()
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, afterCall.sound)
        assertEquals(NotificationManager.IMPORTANCE_LOW, afterCall.importance)
        assertTrue(afterCall.shouldVibrate())
        assertFalse(afterCall.canShowBadge())
        assertNull(manager.getNotificationChannel(OLD_AFTER_CALL))
    }

    // ── idempotent ────────────────────────────────────────────────────────────

    @Test
    fun `a second start changes nothing`() {
        oldChannel(OLD_LIST_PROMPT, NotificationManager.IMPORTANCE_LOW) {
            vibrationPattern = PATTERN
            enableLights(true)
            lightColor = GREEN
        }
        oldChannel(OLD_AFTER_CALL, NotificationManager.IMPORTANCE_NONE) {
            setSound(PICKED_SOUND, Notification.AUDIO_ATTRIBUTES_DEFAULT)
        }
        OrbitNotifications.ensureChannels(context)
        val first = manager.notificationChannels.map { Snapshot(it) }.toSet()

        OrbitNotifications.ensureChannels(context)

        assertEquals(first, manager.notificationChannels.map { Snapshot(it) }.toSet())
        assertEquals(2, first.size)
    }

    /** What a start could change on a channel, copied: Robolectric hands back the stored object. */
    private data class Snapshot(
        val id: String,
        val name: String,
        val description: String?,
        val importance: Int,
        val showBadge: Boolean,
        val sound: Uri?,
        val vibrate: Boolean,
        val pattern: List<Long>?,
        val lights: Boolean,
        val lightColor: Int,
    ) {
        constructor(channel: NotificationChannel) : this(
            id = channel.id,
            name = channel.name.toString(),
            description = channel.description,
            importance = channel.importance,
            showBadge = channel.canShowBadge(),
            sound = channel.sound,
            vibrate = channel.shouldVibrate(),
            pattern = channel.vibrationPattern?.toList(),
            lights = channel.shouldShowLights(),
            lightColor = channel.lightColor,
        )
    }

    private companion object {
        const val OLD_LIST_PROMPT = "orbit.list_prompt"
        const val OLD_AFTER_CALL = "orbit.after_call"
        const val GREEN = 0xFF00C853.toInt()
        val PICKED_SOUND: Uri = Uri.parse("content://media/internal/audio/media/42")
        val PATTERN = longArrayOf(0L, 250L, 250L, 250L)
    }
}
