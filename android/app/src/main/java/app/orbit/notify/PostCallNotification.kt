package app.orbit.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import androidx.annotation.ColorInt
import androidx.core.app.NotificationCompat
import app.orbit.R
import app.orbit.nav.AppLinks
import app.orbit.nav.Routes
import java.time.Duration

/**
 * NOTIF-16: builds the notification after a call: "How was your call with
 * Kai?" / "Add a note while it's fresh." A tap opens the page for writing
 * about that call (NOTE-04), through the same NAVIGATE_TO route a nudge uses
 * ([AppLinks.openRoute]), so a cold start lands on Home with the page on top.
 *
 * ### Lock screen (NOTIF-13's rule, which covers every notification Orbit posts)
 * [NotificationCompat.VISIBILITY_PRIVATE] with a [publicVersion] built from
 * fixed resources: "How was your call?" with the same body, no name. As for
 * the nudge, Android shows the public version on a secure lock screen when
 * the user's settings hide sensitive content.
 *
 * No face and no action: the note is the only thing to do, and it is the tap.
 * No category either: Android's `CATEGORY_REMINDER` means a reminder the user
 * scheduled, which this is not, and none of the others fits.
 *
 * [EXTRA_CONTACT_ID] lets [PostCallNotifier.reconcileShade] tell whose call a
 * notification in the shade is about without decoding its id.
 *
 * ### It times out with the call's window
 * `timeoutAfter` is how long the call still waits by the clock alone: until
 * 24 hours after it started (NOTE-05's window). The shade reconciliation
 * cancels on a note or a dismissal because those change the database; the
 * window closing changes nothing there, so without the timeout a quiet day
 * would leave the notification up after the call stopped waiting.
 */
internal object PostCallNotification {

    const val EXTRA_CONTACT_ID = "app.orbit.extra.POST_CALL_CONTACT_ID"

    fun build(
        context: Context,
        contactId: Long,
        callEventId: Long,
        firstName: String,
        @ColorInt accent: Int,
        timeoutAfter: Duration,
    ): Notification =
        base(context, accent)
            .setContentTitle(NotificationCopy.postCallTitle(firstName).asString(context))
            .setContentText(NotificationCopy.POST_CALL_BODY.asString(context))
            .setContentIntent(tapIntent(context, contactId, callEventId))
            .setAutoCancel(true)
            .setTimeoutAfter(timeoutAfter.toMillis())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, accent))
            .addExtras(Bundle().apply { putLong(EXTRA_CONTACT_ID, contactId) })
            .build()

    /** What the lock screen may show: fixed words, nothing a caller passes. */
    fun publicVersion(context: Context, @ColorInt accent: Int): Notification =
        base(context, accent)
            .setContentTitle(NotificationCopy.POST_CALL_PUBLIC_TITLE.asString(context))
            .setContentText(NotificationCopy.POST_CALL_BODY.asString(context))
            .build()

    private fun base(context: Context, @ColorInt accent: Int): NotificationCompat.Builder =
        NotificationCompat.Builder(context, OrbitNotifications.CHANNEL_AFTER_CALL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(accent)

    /**
     * The note page for this call. requestCode is the person's notification
     * id, and the route doubles as the intent's identifier ([AppLinks.openRoute]),
     * so two people's notifications never open each other's page.
     */
    private fun tapIntent(context: Context, contactId: Long, callEventId: Long): PendingIntent =
        PendingIntent.getActivity(
            context,
            NotificationIds.postCall(contactId),
            AppLinks.openRoute(context, Routes.postCallNote(contactId.toString(), callEventId)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
