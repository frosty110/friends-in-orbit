package app.orbit.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.core.content.getSystemService
import timber.log.Timber

/**
 * Notification channels. Registered on app start via [OrbitApp.onCreate].
 *
 * ### No dot on Orbit's icon (NOTIF-17)
 * Android puts a dot on an app's icon (a number, on some launchers) while one
 * of its notifications is showing, unless that notification's channel says
 * not to. The dot is a badge, and rules.md Design 9 allows none: on
 * 2026-10-08 the owner chose to turn it off. So every channel is made by
 * [channel], with `setShowBadge(false)`, and the builders ([NudgeNotification],
 * [PostCallNotification]) set no number and no badge icon type.
 * `scripts/check-conventions.py` check G fails a channel built without it.
 *
 * ### Channel rotation (D-16 / RESEARCH Pattern 5)
 * Android fixes most of a channel's settings once it exists. An app may still
 * change an existing channel's name and description, and lower its importance
 * until the user changes anything on it, but not its sound, vibration, lights
 * or dot. `setShowBadge` is one of the fixed ones, and recreating a deleted
 * channel under the same ID brings its old settings back. So a setting that has to reach current installs needs a new
 * ID, and the old one is retired: deleted on every start, which is a no-op
 * once it is gone.
 *
 * ### Channel ABI
 * Channel IDs are part of the app's ABI: the user's choices for a channel in
 * Android's settings live under its ID, so an ID is never renamed in passing.
 * 2026-10-08 is the one sanctioned rename, because nothing else takes the dot
 * off current installs: `orbit.list_prompt` became [CHANNEL_LIST_PROMPT] and
 * `orbit.after_call` became [CHANNEL_AFTER_CALL]. The user's choices move with
 * them. On the first start with the new IDs each old channel is read before it
 * is deleted, and its replacement is created with ([carrySettingsFrom]):
 * - its importance, so a channel turned off stays off ([PostCallNotifier]
 *   checks) and a lowered or raised one keeps its level;
 * - its sound and audio attributes, so a silent channel stays silent and a
 *   picked sound carries. If Android refuses a picked sound when the channel
 *   is created (a file Orbit may not read), the channel gets the default sound
 *   instead, rather than let Orbit fail to start;
 * - vibration on or off and its pattern, and lights on or off and their colour.
 *
 * Two settings cannot carry. Lockscreen visibility: Android lets only the
 * system set it on a channel, so the new one starts at Android's default (a
 * locked phone shows NOTIF-13's public version either way). Do Not Disturb
 * bypass: an app needs Do Not Disturb access to set it, and Orbit has none.
 * The dot starts off whatever the old channel had. Deleting an old channel also
 * takes away a notification showing on it, once.
 *
 * Every later start finds the new channel and submits it again at its own
 * importance, which updates the name and description (the phone's language
 * may have changed) and nothing else.
 */
object OrbitNotifications {

    /**
     * Channel ID for per-list nudge notifications (IMPORTANCE_DEFAULT when
     * new). Replaced [RETIRED_LIST_PROMPT] on 2026-10-08 (NOTIF-17).
     */
    const val CHANNEL_LIST_PROMPT = "orbit.list_prompt.v2"

    /**
     * NOTIF-16: the notification after a call ("How was your call with
     * Kai?"). Its own channel, so it can be turned off in Android's settings
     * without touching the nudges; the same importance as the nudges.
     * Replaced [RETIRED_AFTER_CALL] on 2026-10-08 (NOTIF-17).
     */
    const val CHANNEL_AFTER_CALL = "orbit.after_call.v2"

    /** The IDs NOTIF-17 retired, read once for the user's choices and then deleted. */
    private const val RETIRED_LIST_PROMPT = "orbit.list_prompt"
    private const val RETIRED_AFTER_CALL = "orbit.after_call"

    private const val TAG = "channels"

    /**
     * Ensures the current channel set exists and retires legacy channels.
     *
     * Safe to call on every app start (idempotent). Called from [OrbitApp.onCreate].
     */
    fun ensureChannels(context: Context) {
        ensureChannels(context) { createNotificationChannel(it) }
    }

    /**
     * [ensureChannels] with the call that submits a channel passed in. A test
     * hook, like [PostCallNotifier]'s Do Not Disturb one: Robolectric's
     * notification manager never refuses a sound, so a test supplies the refusal.
     */
    internal fun ensureChannels(context: Context, submit: NotificationManager.(NotificationChannel) -> Unit) {
        val nm = context.getSystemService<NotificationManager>() ?: return

        // --- Delete retired channels (RESEARCH Pitfall 6: importance immutable) ---
        nm.deleteNotificationChannel("orbit.digest")
        nm.deleteNotificationChannel("orbit.incoming_followup")
        // Retired 2026-07-03: incoming-call follow-up notifications were removed
        // (Orbit only sends user-defined reminders). Delete the channel so dogfood
        // installs stop showing it in system settings.
        nm.deleteNotificationChannel("orbit.incoming_followup.v2")

        // --- Create current channels, each from the one it replaces (NOTIF-17) ---
        nm.ensureChannel(
            id = CHANNEL_LIST_PROMPT,
            replaces = RETIRED_LIST_PROMPT,
            name = NotificationCopy.CHANNEL_LABEL_LIST_PROMPTS.asString(context),
            description = NotificationCopy.CHANNEL_DESC_LIST_PROMPTS.asString(context),
            submit = submit,
        )
        nm.ensureChannel(
            id = CHANNEL_AFTER_CALL,
            replaces = RETIRED_AFTER_CALL,
            name = NotificationCopy.CHANNEL_LABEL_AFTER_CALL.asString(context),
            description = NotificationCopy.CHANNEL_DESC_AFTER_CALL.asString(context),
            submit = submit,
        )
    }

    private fun NotificationManager.ensureChannel(
        id: String,
        replaces: String,
        name: String,
        description: String,
        submit: NotificationManager.(NotificationChannel) -> Unit,
    ) {
        val current = getNotificationChannel(id)
        if (current != null) {
            // Every later start. At the channel's own importance this changes
            // only the name and description: Android lowers a channel the user
            // has not touched to a lower importance passed here, so the default
            // would undo a carried "Pop on screen".
            submit(channel(id, name, description, current.importance))
        } else {
            // The first start on this ID. Read the old channel now: once it is
            // deleted below, Android no longer returns it, and the user's
            // choices would be lost.
            val old = getNotificationChannel(replaces)
            val next = channel(id, name, description, old?.importance ?: NotificationManager.IMPORTANCE_DEFAULT)
            if (old == null) {
                submit(next)
            } else {
                next.carrySettingsFrom(old)
                try {
                    submit(next)
                } catch (_: SecurityException) {
                    // A picked sound is the one carried setting that names a file
                    // outside Orbit. Android documents no check of it here, and this
                    // was not tried on a device, but a throw from Application.onCreate
                    // would stop Orbit starting at all. So that one setting resets,
                    // as the owner was told a custom sound would. The id is not PII.
                    Timber.tag(TAG).w("channel_sound_not_carried channel=%s", id)
                    next.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, Notification.AUDIO_ATTRIBUTES_DEFAULT)
                    submit(next)
                }
            }
        }
        // After the new channel exists, so a failure above leaves the old one,
        // and the user's choices, for the next start to carry.
        deleteNotificationChannel(replaces)
    }

    /**
     * Every channel Orbit creates. No dot on the app's icon (NOTIF-17,
     * rules.md Design 9); the user may still turn one on in Android's settings.
     */
    private fun channel(id: String, name: String, description: String, importance: Int): NotificationChannel =
        NotificationChannel(id, name, importance).apply {
            this.description = description
            setShowBadge(false)
        }

    /**
     * NOTIF-17: what the user may have changed on [old] that an app may still
     * set on a new channel. The importance is passed to [channel]; what cannot
     * carry is in this object's KDoc.
     */
    private fun NotificationChannel.carrySettingsFrom(old: NotificationChannel) {
        setSound(old.sound, old.audioAttributes)
        // The pattern first: setting one switches vibration on or off by
        // itself, and the user's switch is what counts.
        vibrationPattern = old.vibrationPattern
        enableVibration(old.shouldVibrate())
        enableLights(old.shouldShowLights())
        lightColor = old.lightColor
    }
}
