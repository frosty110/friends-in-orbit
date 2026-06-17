package app.orbit.logging

/**
 * Tree-layer PII scrubber for Timber.
 *
 * Contract: pure, deterministic, idempotent (scrub(scrub(x)) == scrub(x)). No I/O,
 * no allocations beyond the returned String. Safe to call on every log message.
 *
 * The phone regex is deliberately aggressive — it MAY redact digit strings that
 * are timestamps or durations (e.g. `occurredAt=1729776000000`). That's acceptable
 * by design — the rule is "never leak PII," not "never redact
 * non-PII content." Log call sites that need to emit raw numbers (counts, durations,
 * rowIds) must use non-digit formatting (e.g. "scanned=3400" — digits adjacent to
 * `=` without `-`, space, or parens do NOT match the phone regex; the 6+ digit
 * threshold spares 3–5 digit counts).
 */
internal object PiiSanitizer {

    /**
     * Matches phone-number-shaped substrings: optional leading `+`, then 7+ digits
     * possibly separated by `-`, space, or parens. Crafted to match the common
     * shapes of CallLog.Calls.NUMBER (+14155551234, 415-555-1234, (415) 555-1234,
     * +44 20 7946 0018) without matching single-token digit runs shorter than 7
     * digits (sparing most counts and durations).
     */
    private val PHONE_REGEX = Regex("""\+?\d[\d\-() ]{6,}""")

    /**
     * Known Kotlin-template placeholder patterns that leak contact PII. Catches the
     * careless `Timber.d("Synced ${contact.name}")` regression even when the rendered
     * name contains no digits.
     */
    // NOTE: Android's ICU regex engine treats a bare `}` as a metacharacter
    // (it can close a repetition `{n,m}` block). The JVM regex engine on
    // desktop is more permissive and tolerates a bare `}`, which is why
    // these patterns compiled on test runs but exploded as
    // `PatternSyntaxException` at app cold-launch on-device. Every literal
    // closing brace below is therefore escaped as `\}` so the patterns
    // compile uniformly across both engines.
    private val TEMPLATE_REPLACEMENTS = listOf(
        Regex("""\$\{[^}]*\.name[^}]*\}""") to "[contact]",
        Regex("""\$\{[^}]*\.displayName[^}]*\}""") to "[contact]",
        Regex("""\$\{[^}]*\.phoneNumber[^}]*\}""") to "[phone]",
        Regex("""\$\{[^}]*\.phone[^}]*\}""") to "[phone]",
        Regex("""\$\{[^}]*\.rawCallNumber[^}]*\}""") to "[call#]",
        Regex("""\$\{contactName\}""") to "[contact]",
        Regex("""\{phoneNumber\}""") to "[phone]",
        Regex("""\{displayName\}""") to "[contact]",
        Regex("""\{rawCallNumber\}""") to "[call#]",
        Regex("""\{contactName\}""") to "[contact]",
    )

    fun scrub(message: String): String {
        if (message.isEmpty()) return message
        var out = message
        for ((pattern, replacement) in TEMPLATE_REPLACEMENTS) {
            out = out.replace(pattern, replacement)
        }
        out = out.replace(PHONE_REGEX, "[phone]")
        return out
    }
}
