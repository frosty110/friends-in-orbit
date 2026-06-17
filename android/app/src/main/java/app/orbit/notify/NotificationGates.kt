package app.orbit.notify

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService

/**
 * Reusable fire-time gate helpers — NOTIF-06 substrate (D-10).
 *
 * Both helpers are [Context] extension functions so callers (workers, schedulers) can
 * write `if (!areNotificationsEnabled() || isDndBlocking()) return` at the top of
 * [doWork] without importing a manager directly.
 *
 * Gate helpers touch the Android framework and are validated on-device;
 * they are NOT covered by JVM unit tests.
 */

/**
 * Returns `true` when the app has POST_NOTIFICATIONS permission AND the user has not
 * globally silenced Orbit in system Settings → App Notifications.
 *
 * Delegates to [NotificationManagerCompat.areNotificationsEnabled] which covers both
 * the permission check and the system-level mute toggle.
 */
fun Context.areNotificationsEnabled(): Boolean =
    NotificationManagerCompat.from(this).areNotificationsEnabled()

/**
 * Returns `true` when the device's Do Not Disturb mode would block a default-importance
 * notification, matching D-10: "INTERRUPTION_FILTER_ALL required to post".
 *
 * ### Semantics (D-10)
 * Only [NotificationManager.INTERRUPTION_FILTER_ALL] (interruptions fully allowed)
 * permits the nudge to fire. All other filters — PRIORITY, ALARMS, and NONE — suppress
 * the notification. The follow-up channel is IMPORTANCE_HIGH and may pierce priority DND
 * on some device configurations, but we apply the same gate uniformly for simplicity;
 * the user can configure DND exceptions in system Settings if needed.
 *
 * ### Why "blocking" semantics rather than "allowing"
 * Returning `true` when DND *blocks* the call gives callers a natural guard:
 * ```kotlin
 * if (!areNotificationsEnabled() || isDndBlocking()) return Result.success()
 * ```
 * This mirrors the `notificationsEnabled` flag check shape (D-10 ordering) and avoids
 * double-negation at the call site.
 */
fun Context.isDndBlocking(): Boolean {
    val nm = getSystemService<NotificationManager>() ?: return false
    return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
}
