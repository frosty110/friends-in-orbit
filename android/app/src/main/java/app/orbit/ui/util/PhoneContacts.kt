package app.orbit.ui.util

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast

/**
 * Opens the device's own contact card for [phoneContactId] via `ACTION_VIEW`.
 *
 * Why this exists: an address book accumulates numbers whose owner the user no
 * longer recognises, and the picker deliberately shows very little about a
 * person (name, call line, list membership). The honest place to answer "who
 * IS this?" is the system contacts app, which already holds the call and
 * message history for that number. Orbit holds no message data at all — it
 * ships with `READ_CALL_LOG` / `READ_CONTACTS` and no `READ_SMS` — so handing
 * the row off is the only truthful answer, not a shortcut.
 *
 * [phoneContactId] is `ContactsContract.Contacts._ID` as captured in
 * `ContactEntity.phoneContactId`, so the URI is the canonical
 * `content://com.android.contacts/contacts/{id}`. Rows with no
 * `phoneContactId` (call-log-only contacts) must not reach this function —
 * callers gate on null.
 *
 * Guard mirrors [dialPhoneNumber]: [Intent.resolveActivity] can return null on
 * a device with no contacts app, and the `<queries>` element in
 * `AndroidManifest.xml` is mandatory on Android 11+ or the resolve returns null
 * even when a contacts app IS installed.
 *
 * Toast copy is voice-audited: factual, sentence case, no apology.
 */
fun Context.openPhoneContact(phoneContactId: Long) {
    val uri = ContentUris.withAppendedId(
        ContactsContract.Contacts.CONTENT_URI,
        phoneContactId,
    )
    val intent = Intent(Intent.ACTION_VIEW, uri)
    if (intent.resolveActivity(packageManager) != null) {
        startActivity(intent)
    } else {
        Toast.makeText(this, "No contacts app installed", Toast.LENGTH_SHORT).show()
    }
}
