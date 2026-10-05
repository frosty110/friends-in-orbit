package app.orbit.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.ColorInt
import androidx.core.app.NotificationCompat
import app.orbit.R
import app.orbit.data.entity.ContactEntity
import app.orbit.nav.AppLinks
import app.orbit.nav.Routes

/**
 * Builds a list's nudge. The only notification Orbit posts (ADR 0009), so the
 * lock-screen rule below covers every notification the app can show.
 *
 * ### Lock screen (NOTIF-13, UX rubric gate G6)
 * Every nudge is [NotificationCompat.VISIBILITY_PRIVATE] with a public version
 * ([publicVersion]) that names no person and no list, shows no face and offers
 * no action. On a secure lock screen Android shows that public version
 * whenever the user's lock-screen setting hides sensitive content (the global
 * "sensitive notifications" switch, or "hide sensitive content" for this
 * channel). Where the user has chosen to show everything, Android shows the
 * full nudge: the choice is theirs, and Orbit marks the content as sensitive
 * so the system can honour it. Before, no public version was set, so the
 * redacted form fell back to Android's generic placeholder at best.
 *
 * ### Named nudge (NOTIF-14)
 * When [subject] is set the nudge hands over that person: their face as the
 * large icon, "Kai is ready when you are" as the body, and a "Call Kai"
 * action that opens the dialer with their number ([Intent.ACTION_DIAL], no
 * CALL_PHONE; the user places the call there). The body tap still opens the
 * list's deck, where [subject] is the person on top. The action is left off
 * on a device with no dialer, as [app.orbit.ui.util.dialPhoneNumber] would
 * find, rather than offering a button that does nothing.
 *
 * [subject] null means the name-free nudge (no face, no action), either
 * because nobody can be named or because NOTIF-15 holds the name back.
 *
 * The words come from [NotificationCopy] as [app.orbit.ui.util.UiText] and
 * are resolved here against [Context], in the user's language
 * (strings_notify.xml).
 */
internal object NudgeNotification {

    fun build(
        context: Context,
        listId: Long,
        listName: String,
        dueCount: Int,
        subject: ContactEntity?,
        face: Bitmap?,
        @ColorInt accent: Int,
    ): Notification {
        val builder = base(context, accent)
            .setContentTitle(NotificationCopy.nudgeTitle(listName))
            .setContentIntent(tapIntent(context, listId))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, accent))

        if (subject == null) {
            builder.setContentText(NotificationCopy.nudgeBody(listName, dueCount).asString(context))
        } else {
            val firstName = NotificationCopy.firstNameOf(subject.displayName)
            builder
                .setContentText(NotificationCopy.nudgeNamedBody(firstName).asString(context))
                .setLargeIcon(face)
            val dial = dialIntent(subject)
            if (dial.resolveActivity(context.packageManager) != null) {
                builder.addAction(
                    R.drawable.ph_phone,
                    NotificationCopy.callActionLabel(firstName).asString(context),
                    PendingIntent.getActivity(
                        context,
                        NotificationIds.listPrompt(listId),
                        dial,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
        }
        return builder.build()
    }

    /**
     * NOTIF-13: what the lock screen may show. Built from fixed resources
     * only, so nothing a caller passes (a list name, a person, a note) can
     * reach it.
     */
    fun publicVersion(context: Context, @ColorInt accent: Int): Notification =
        base(context, accent)
            .setContentTitle(NotificationCopy.PUBLIC_TITLE.asString(context))
            .setContentText(NotificationCopy.PUBLIC_BODY.asString(context))
            .build()

    /** What the full and the public version share: channel, icon, colour, kind. */
    private fun base(context: Context, @ColorInt accent: Int): NotificationCompat.Builder =
        NotificationCompat.Builder(context, OrbitNotifications.CHANNEL_LIST_PROMPT)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(accent)
            // A reminder the user scheduled (ADR 0009), which is what the
            // system's "reminders" category means.
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

    /**
     * Body tap: the list's deck.
     *
     * - Flags: [PendingIntent.FLAG_IMMUTABLE] | [PendingIntent.FLAG_UPDATE_CURRENT] (T-10-09)
     * - requestCode = [NotificationIds.listPrompt] so per-list intents do not collide.
     */
    private fun tapIntent(context: Context, listId: Long): PendingIntent =
        PendingIntent.getActivity(
            context,
            NotificationIds.listPrompt(listId),
            AppLinks.openRoute(context, Routes.card(listId.toString())),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * [Uri.fromParts], not `Uri.parse`, so a number holding `#` or pause
     * characters is encoded whole instead of being cut at the fragment
     * delimiter (the same rule as the widget's dial intent, review WR-04).
     */
    private fun dialIntent(contact: ContactEntity): Intent =
        Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", contact.phoneNumber, null))
}
