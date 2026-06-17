package app.orbit.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CALL-07 gate — unit tests for the pure-function scrubber that sits inside
 * [OrbitDebugTree]. Every Timber log message flows through [PiiSanitizer.scrub]
 * before reaching Logcat; these tests prove the redaction contract in isolation
 * so the [OrbitDebugTreeTest] end-to-end test can trust it.
 */
class PiiSanitizerTest {

    @Test
    fun scrub_us_e164_phone_is_redacted() {
        val out = PiiSanitizer.scrub("phone is +14155551234")
        assertEquals("phone is [phone]", out)
    }

    @Test
    fun scrub_us_dashed_phone_is_redacted() {
        val out = PiiSanitizer.scrub("call from 415-555-1234")
        assertEquals("call from [phone]", out)
    }

    @Test
    fun scrub_uk_international_phone_is_redacted() {
        val out = PiiSanitizer.scrub("+44 20 7946 0018 called")
        assertTrue(out.contains("[phone]"), "expected [phone] in: $out")
        assertFalse(out.contains("7946"), "digits leaked in: $out")
    }

    @Test
    fun scrub_kotlin_template_name_placeholder_is_redacted() {
        val out = PiiSanitizer.scrub("Synced \${contact.name}")
        assertEquals("Synced [contact]", out)
    }

    @Test
    fun scrub_kotlin_template_phone_placeholder_is_redacted() {
        val out = PiiSanitizer.scrub("raw: \${contact.phoneNumber}")
        assertEquals("raw: [phone]", out)
    }

    @Test
    fun scrub_curly_brace_phone_placeholder_is_redacted() {
        val out = PiiSanitizer.scrub("raw: {phoneNumber}")
        assertEquals("raw: [phone]", out)
    }

    @Test
    fun scrub_non_pii_passthrough() {
        val input = "sync_complete scanned=3400 inserted=12 direction=OUTGOING"
        assertEquals(input, PiiSanitizer.scrub(input))
    }

    @Test
    fun scrub_empty_string_is_noop() {
        assertEquals("", PiiSanitizer.scrub(""))
    }

    @Test
    fun scrub_multiline_redacts_independently() {
        val input = "line1 +14155551234\nline2 no-pii\nline3 +14155551235"
        val out = PiiSanitizer.scrub(input)
        assertFalse(out.contains("+14155551234"), "line 1 leaked: $out")
        assertFalse(out.contains("+14155551235"), "line 3 leaked: $out")
        assertTrue(out.contains("no-pii"), "non-PII line was clobbered: $out")
    }

    @Test
    fun scrub_is_idempotent() {
        val input = "phone: +14155551234 name: \${contact.name}"
        val once = PiiSanitizer.scrub(input)
        val twice = PiiSanitizer.scrub(once)
        assertEquals(once, twice)
    }

    @Test
    fun scrub_large_digit_run_looking_like_timestamp_is_redacted() {
        // 13-digit epoch ms — acceptable to redact (false positive, not false negative).
        val input = "occurredAt=1729776000000"
        val out = PiiSanitizer.scrub(input)
        assertFalse(out.contains("1729776000000"), "large digit run leaked: $out")
    }

    @Test
    fun scrub_short_digit_run_is_not_redacted() {
        // 3-digit counts must survive the scrubber — the 6+ threshold protects them.
        val input = "count=123"
        assertEquals(input, PiiSanitizer.scrub(input))
    }
}
