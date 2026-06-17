package app.orbit.calllog

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * E.164 normalization wrapper around libphonenumber.
 *
 * Contract:
 * - `normalize(null)` or `normalize("")` returns `""`. Never throws, never returns null.
 * - Parseable + valid numbers return E.164 format (leading `+`, country code, subscriber).
 * - Parseable but not-valid (short codes, emergency numbers) fall back to digit-only with
 *   optional leading `+`.
 * - Unparseable input falls back to digit-only with optional leading `+`.
 * - Idempotent: `normalize(normalize(x)) == normalize(x)`. The reconciler relies on this
 *   to safely re-normalize `CallRow.normalizedPhone` (which was produced by the legacy
 *   `ContactsReader.normalizeForMatch`) without drift.
 *
 * Region handling: uses `Locale.getDefault().country` with `"US"` fallback when the
 * locale has no country (e.g. `en` without a region). Settings may later persist a
 * user-chosen `homeCountryIso`; until then, the region is captured at construction
 * time so `Locale.getDefault()` mutations
 * mid-session don't flap the normalizer — `@Singleton` keeps a single instance for
 * the process lifetime.
 */
@Singleton
class PhoneNumberNormalizer @Inject constructor() {

    private val util: PhoneNumberUtil = PhoneNumberUtil.getInstance()
    private val defaultRegion: String = Locale.getDefault().country.ifBlank { "US" }

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            val parsed = util.parse(raw, defaultRegion)
            if (util.isValidNumber(parsed)) {
                util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
            } else {
                // Short codes, emergency numbers: parseable but `isValidNumber == false`.
                // Falling back to digit-only-with-plus preserves the input's identity
                // for our matching purposes (we still need a stable string to compare
                // against `ContactEntity.phoneNumber` after re-normalization).
                digitsOnlyWithPlus(raw)
            }
        } catch (_: NumberParseException) {
            digitsOnlyWithPlus(raw)
        }
    }

    private fun digitsOnlyWithPlus(raw: String): String = buildString(raw.length) {
        if (raw.startsWith("+")) append('+')
        raw.forEach { if (it.isDigit()) append(it) }
    }
}
