package app.orbit.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Opens the system dialer pre-filled with [phone] via `ACTION_DIAL`. CORE-02
 * contract — no `CALL_PHONE` permission required (we never place the call
 * ourselves; the user taps the call button in the dialer).
 *
 * Guard: [Intent.resolveActivity] checks for a dialer app. On tablets and other
 * dialer-less devices it returns null and we show a factual toast. The
 * `<queries>` element in `AndroidManifest.xml` is mandatory
 * on Android 11+ (minSdk 31) — without it, `resolveActivity` returns null even
 * when a dialer IS installed.
 *
 * Toast copy "No dialer app installed" is voice-audited: factual, sentence
 * case, no exclamation, no apology.
 */
fun Context.dialPhoneNumber(phone: String) {
    val intent = Intent(Intent.ACTION_DIAL).apply {
        data = Uri.fromParts("tel", phone, null)
    }
    if (intent.resolveActivity(packageManager) != null) {
        startActivity(intent)
    } else {
        Toast.makeText(this, "No dialer app installed", Toast.LENGTH_SHORT).show()
    }
}
