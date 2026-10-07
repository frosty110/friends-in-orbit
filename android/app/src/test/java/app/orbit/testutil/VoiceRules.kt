package app.orbit.testutil

/**
 * The parts of voice.md a test can hold copy to: the never-say list, no
 * exclamation marks, no dashes or arrows, no emoji. Shared by `CopyAuditTest`
 * (what the nudge formatters produce) and `VoiceAuditTest` (every translatable
 * string resource), so there is one list to extend when a word is decided.
 */
object VoiceRules {

    /**
     * Phrases that frame a call as a debt or a game (voice.md "Never say").
     * Matched as whole words, case-insensitively, with an optional plural, so
     * "streaks" and "levels" count and "residue" does not. "due" is the
     * deadline word retired from every screen on 2026-10-05.
     */
    val forbiddenPhrases: List<String> = listOf(
        "haven't called",
        "days since",
        "overdue",
        "it's been",
        "streak",
        "level",
        "achievement",
        "you missed",
        "due",
        "caught up",
        "great job",
        "awesome",
        "keep it going",
        "stay on track",
        "crush your goals",
        "beat your record",
        "the user",
    )

    /** Glyphs copy never uses: dashes join clauses, the arrow stands in for words. */
    val forbiddenGlyphs: Map<Char, String> = mapOf(
        '\u2013' to "en dash",
        '\u2014' to "em dash",
        '\u2192' to "arrow",
    )

    private val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]")

    private val phrasePatterns: List<Pair<String, Regex>> = forbiddenPhrases.map { phrase ->
        phrase to Regex("(?<!\\p{L})${Regex.escape(phrase)}s?(?!\\p{L})", RegexOption.IGNORE_CASE)
    }

    /** Every rule [text] breaks, as short reasons; empty when it is clean. */
    fun violations(text: String): List<String> {
        val plain = text.replace('\u2019', '\'')
        val found = mutableListOf<String>()
        if ('!' in plain) found += "exclamation mark"
        forbiddenGlyphs.forEach { (glyph, name) -> if (glyph in plain) found += name }
        if (emoji.containsMatchIn(plain)) found += "emoji"
        phrasePatterns.forEach { (phrase, pattern) -> if (pattern.containsMatchIn(plain)) found += "says \"$phrase\"" }
        return found
    }
}
