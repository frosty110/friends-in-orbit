package app.orbit.calllog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioral coverage for [PhoneNumberNormalizer].
 *
 * The normalizer wraps libphonenumber's [com.google.i18n.phonenumbers.PhoneNumberUtil]
 * with a strict contract:
 *  - never returns null
 *  - parseable + valid → E.164 (`+<cc><subscriber>`)
 *  - parseable but not valid (short codes) → digit-only fallback (preserves leading `+`)
 *  - unparseable → digit-only fallback
 *  - empty / blank → ""
 *  - idempotent: `normalize(normalize(x)) == normalize(x)`
 *
 * Inputs use unambiguous explicit country codes (`+1...`, `+44...`) so tests pass
 * regardless of the JVM default region. Synthetic phone numbers (`+155...`,
 * `+44207946...`) — no real PII.
 */
class PhoneNumberNormalizerTest {

    private val normalizer = PhoneNumberNormalizer()

    // ------------------------------------------------------------------
    // Empty / null
    // ------------------------------------------------------------------

    @Test
    fun null_returns_empty() {
        assertEquals("", normalizer.normalize(null))
    }

    @Test
    fun empty_returns_empty() {
        assertEquals("", normalizer.normalize(""))
        assertEquals("", normalizer.normalize("   "))
    }

    // ------------------------------------------------------------------
    // E.164 round-trip (already-formatted input)
    // ------------------------------------------------------------------

    @Test
    fun us_e164_is_unchanged() {
        assertEquals("+14155551234", normalizer.normalize("+14155551234"))
    }

    @Test
    fun uk_international_e164_is_unchanged() {
        assertEquals("+442079460018", normalizer.normalize("+442079460018"))
    }

    @Test
    fun br_international_e164_is_unchanged() {
        assertEquals("+5511999998888", normalizer.normalize("+5511999998888"))
    }

    // ------------------------------------------------------------------
    // Parseable inputs that need formatting
    // ------------------------------------------------------------------

    @Test
    fun dashed_us_with_plus_one_is_valid_e164() {
        // "+1-415-555-1234" — unambiguous regardless of default region.
        assertEquals("+14155551234", normalizer.normalize("+1-415-555-1234"))
    }

    @Test
    fun parentheses_us_with_plus_one_is_valid_e164() {
        assertEquals("+14155551234", normalizer.normalize("+1 (415) 555-1234"))
    }

    // ------------------------------------------------------------------
    // Non-valid / unparseable fallbacks
    // ------------------------------------------------------------------

    @Test
    fun short_code_falls_back_to_digits() {
        val out = normalizer.normalize("911")
        // libphonenumber treats short codes as not-valid; we expect digits-only.
        assertEquals("911", out)
    }

    @Test
    fun garbage_input_with_no_digits_is_empty() {
        // "no-digits" — no digits to extract; fallback yields "".
        assertEquals("", normalizer.normalize("no-digits-here"))
    }

    @Test
    fun parseable_but_invalid_preserves_plus_prefix() {
        // Short code with a leading `+` — unusual but within the fallback contract.
        val out = normalizer.normalize("+911")
        assertTrue(out.startsWith("+"), "lost plus prefix on: +911 -> $out")
    }

    // ------------------------------------------------------------------
    // Idempotency invariant — load-bearing for the reconciler's
    // re-normalization step in CallLogReconciler.reconcile.
    // ------------------------------------------------------------------

    @Test
    fun normalization_is_idempotent() {
        val inputs = listOf(
            "+14155551234",
            "+1-415-555-1234",
            "+44 20 7946 0018",
            "911",
            "+5511999998888",
        )
        for (input in inputs) {
            val once = normalizer.normalize(input)
            val twice = normalizer.normalize(once)
            assertEquals(once, twice, "normalize not idempotent for: $input")
        }
    }
}
