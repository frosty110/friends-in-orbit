package app.orbit.data.android

import android.content.Context
import android.provider.ContactsContract
import androidx.annotation.WorkerThread
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One phone number on a device contact — raw display form + match key. */
data class PhoneNumberRow(
    val number: String,
    val normalized: String,
)

data class PhoneContact(
    val contactId: Long,       // ContactsContract.Contacts._ID — stable across app installs
    val displayName: String,
    val phone: String,         // primary/display number (first row per contact, NUMBER column to keep formatting)
    val normalizedPhone: String,
    // PHOTO_THUMBNAIL_URI from the joined contact row; null when the
    // contact has no photo.
    val photoUri: String? = null,
    // EVERY number on the contact (primary first, deduped on the
    // normalized form). Defaults to empty for legacy constructors; consumers
    // should fall back to the primary pair when empty.
    val phones: List<PhoneNumberRow> = emptyList(),
    // ContactsContract STARRED from the joined contact row.
    // Android favorites are hand-curated closest people; mirrored into
    // ContactEntity.isStarred and refreshed by the delta-sync.
    val isStarred: Boolean = false,
)

// Reads the device address book. Returns contacts that have at least one
// phone number; the app is phone-first (PRD §Contact Data Model).
//
// @Singleton + @Inject so ContactPickerViewModel can take it via constructor
// injection without a separate @Provides binding. The existing
// direct-instantiation call sites (`ContactsReader(context).readAll()`) keep
// working — Hilt's @Inject constructor permits direct ctor calls too.
//
// `open` (class + readAll) so JVM unit tests can substitute a canned-rows
// subclass without mocking ContentResolver (same seam pattern as
// CallLogReconciler / ContentObserverController).
@Singleton
open class ContactsReader @Inject constructor(@ApplicationContext private val context: Context) {

    @WorkerThread
    open suspend fun readAll(): List<PhoneContact> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
            // Thumbnail URI joined from the contact row; feeds
            // ContactEntity.photoUri so picker/detail AsyncImage branches light up.
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            // STARRED joined from the contact row; feeds
            // ContactEntity.isStarred so the picker's "Starred" filter chip
            // and Unsorted starred-first ordering light up.
            ContactsContract.CommonDataKinds.Phone.STARRED,
        )
        val sort = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY +
            " COLLATE NOCASE ASC"

        // The Phone table emits one row per number, so a multi-number contact
        // spans several rows. The FIRST row per contact stays the primary
        // (preserves the identity key); every subsequent row accumulates into
        // `phones` so call-log reconciliation can match second-SIM /
        // work-line calls.
        val accumulators = LinkedHashMap<Long, Accumulator>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            sort,
        )?.use { c ->
            val idxId = c.getColumnIndexOrThrow(projection[0])
            val idxName = c.getColumnIndexOrThrow(projection[1])
            val idxNumber = c.getColumnIndexOrThrow(projection[2])
            val idxNorm = c.getColumnIndexOrThrow(projection[3])
            val idxPhoto = c.getColumnIndexOrThrow(projection[4])
            val idxStarred = c.getColumnIndexOrThrow(projection[5])

            while (c.moveToNext()) {
                val id = c.getLong(idxId)
                val name = c.getString(idxName) ?: continue
                val number = c.getString(idxNumber) ?: continue
                val norm = c.getString(idxNorm) ?: normalizeForMatch(number)
                val photo = c.getString(idxPhoto)
                val starred = c.getInt(idxStarred) == 1
                val acc = accumulators.getOrPut(id) { Accumulator(name, photo, starred) }
                // Dedup within the contact on the normalized form — the same
                // number entered twice (home + mobile labels) is one row.
                if (acc.seenNormalized.add(norm)) {
                    acc.phones += PhoneNumberRow(number = number, normalized = norm)
                }
            }
        }
        accumulators.mapNotNull { (id, acc) ->
            val primary = acc.phones.firstOrNull() ?: return@mapNotNull null
            PhoneContact(
                contactId = id,
                displayName = acc.displayName,
                phone = primary.number,
                normalizedPhone = primary.normalized,
                photoUri = acc.photoUri,
                phones = acc.phones.toList(),
                isStarred = acc.isStarred,
            )
        }
    }

    /** Per-contact accumulation state for the multi-row Phone-table cursor walk. */
    private class Accumulator(
        val displayName: String,
        val photoUri: String?,
        val isStarred: Boolean,
    ) {
        val seenNormalized = mutableSetOf<String>()
        val phones = mutableListOf<PhoneNumberRow>()
    }

    companion object {
        // Strip everything except digits and a leading '+'. Good enough to
        // match CallLog rows back to contacts when NORMALIZED_NUMBER is absent
        // (older ROMs, or entries we inserted ourselves).
        fun normalizeForMatch(raw: String): String {
            val sb = StringBuilder(raw.length)
            for ((i, ch) in raw.withIndex()) {
                if (ch.isDigit()) sb.append(ch)
                else if (ch == '+' && i == 0) sb.append(ch)
            }
            return sb.toString()
        }
    }
}
