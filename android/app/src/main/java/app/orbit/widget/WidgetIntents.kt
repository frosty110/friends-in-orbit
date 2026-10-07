// android/app/src/main/java/app/orbit/widget/WidgetIntents.kt
//
// The intents a widget fires, built outside the composables so they are
// plain, unit-tested functions (WidgetPeopleTest, and the nudge notification
// reuses dialIntent). Moved out of WidgetContent.kt on 2026-10-07, which is
// now Glance UI only.
package app.orbit.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.orbit.MainActivity
import app.orbit.data.entity.ContactEntity

/**
 * Builds an ACTION_DIAL intent for the given contact. Uses [ContactEntity.phoneNumber]
 * (the display-number field) as the dialer data URI.
 *
 * [Uri.fromParts] (not `Uri.parse`) so numbers containing `#` or wait/pause
 * characters (`555-1234,,123#`) are opaque-encoded instead of being truncated
 * at the URI fragment delimiter (review WR-04).
 *
 * Never uses CALL_PHONE or ACTION_CALL (PRIV-05). Glance wraps this in a
 * FLAG_IMMUTABLE PendingIntent via actionStartActivity.
 */
fun dialIntent(contact: ContactEntity): Intent =
    Intent(Intent.ACTION_DIAL).apply {
        data = Uri.fromParts("tel", contact.phoneNumber, null)
    }

/**
 * Opens Orbit's Home screen (the default start destination), for the empty
 * state's tap target. No NAVIGATE_TO extra; MainActivity starts normally and
 * lands on Home per NavGraph default.
 */
fun openHomeIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
