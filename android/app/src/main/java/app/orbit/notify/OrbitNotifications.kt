package app.orbit.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

/**
 * Notification channels. Registered on app start via [OrbitApp.onCreate].
 *
 * ### Channel rotation (D-16 / RESEARCH Pattern 5)
 * Android's importance level is immutable once a channel has been seen by the user:
 * silently recreating a deleted channel under the same ID restores the user's old
 * (possibly low-importance) settings. The solution is to retire old IDs and create
 * new ones.
 *
 * `ensureChannels()` explicitly deletes the two retired channels on every app start
 * before creating the new ones. Delete-then-create is safe and idempotent:
 * - Deleting a channel that doesn't exist is a no-op.
 * - Creating a channel that already exists with identical parameters is a no-op.
 *
 * ### Channel ABI
 * Channel IDs are part of the app's ABI — users can mute a channel in system
 * Settings and we must never rename or silently re-create an existing one.
 * [CHANNEL_LIST_PROMPT] is a new ID (v1 ships before public release, so no user
 * has a muted version of this channel).
 */
object OrbitNotifications {

    /** Channel ID for per-list nudge notifications (IMPORTANCE_DEFAULT). */
    const val CHANNEL_LIST_PROMPT = "orbit.list_prompt"

    /**
     * NOTIF-16: the notification after a call ("How was your call with
     * Kai?"). Its own channel, so it can be turned off in Android's settings
     * without touching the nudges; the same importance as the nudges.
     */
    const val CHANNEL_AFTER_CALL = "orbit.after_call"

    /**
     * Ensures the current channel set exists and retires legacy channels.
     *
     * Safe to call on every app start (idempotent). Called from [OrbitApp.onCreate].
     */
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService<NotificationManager>() ?: return

        // --- Delete retired channels (RESEARCH Pitfall 6: importance immutable) ---
        nm.deleteNotificationChannel("orbit.digest")
        nm.deleteNotificationChannel("orbit.incoming_followup")
        // Retired 2026-07-03: incoming-call follow-up notifications were removed
        // (Orbit only sends user-defined reminders). Delete the channel so dogfood
        // installs stop showing it in system settings.
        nm.deleteNotificationChannel("orbit.incoming_followup.v2")

        // --- Create current channels ---
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LIST_PROMPT,
                NotificationCopy.CHANNEL_LABEL_LIST_PROMPTS.asString(context),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = NotificationCopy.CHANNEL_DESC_LIST_PROMPTS.asString(context) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AFTER_CALL,
                NotificationCopy.CHANNEL_LABEL_AFTER_CALL.asString(context),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = NotificationCopy.CHANNEL_DESC_AFTER_CALL.asString(context) }
        )
    }
}
