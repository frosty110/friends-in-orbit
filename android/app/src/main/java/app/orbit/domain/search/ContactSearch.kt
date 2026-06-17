package app.orbit.domain.search

import java.text.Normalizer

/**
 * Shared contact-search matcher (quality review 2026-06-09 #16) — one
 * matching + ranking rule for every search surface (Browse filter, Global
 * Search, contact picker), replacing the per-screen
 * `displayName.contains(q, ignoreCase = true)` checks that missed
 * diacritics ("Jose" never found "José") and phone digits, and ranked a
 * word-start hit no higher than a mid-word one.
 *
 * Matching rules, in rank order (lower rank = better):
 *   1. [Rank.WORD_START] — a folded query that begins the name or any
 *      word inside it ("ma" → "Maya", "jo" → "Sarah Jones").
 *   2. [Rank.SUBSTRING]  — the folded query appears mid-word
 *      ("ari" → "Maria").
 *   3. [Rank.PHONE]      — the query's digits (3+ of them, so "1" doesn't
 *      light up the whole book) appear in the contact's normalized number.
 *
 * Folding = Unicode NFD decomposition with combining marks stripped +
 * lowercase, so "José", "JOSE", and "jose" are the same key. Pure
 * functions, no Android dependencies — call from any layer.
 */
object ContactSearch {

    enum class Rank { WORD_START, SUBSTRING, PHONE }

    /** Minimum digits before a query is also tried as a phone fragment. */
    private const val MIN_PHONE_QUERY_DIGITS: Int = 3

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NON_DIGITS = Regex("\\D")

    /** Diacritic-folded lowercase form ("José" → "jose"). */
    fun fold(raw: String): String =
        COMBINING_MARKS.replace(Normalizer.normalize(raw, Normalizer.Form.NFD), "")
            .lowercase()

    /** Digits-only form of a query ("(404) 555" → "404555"). */
    fun digitsOf(raw: String): String = NON_DIGITS.replace(raw, "")

    /**
     * Match [query] against a contact. Returns the best [Rank] or null for
     * no match. A blank query matches nothing — callers decide what an
     * empty search shows.
     *
     * [normalizedPhone] is the store's already-normalized number (digits
     * with optional leading `+`); pass null/empty when unknown.
     */
    fun match(query: String, displayName: String, normalizedPhone: String?): Rank? {
        val foldedQuery = fold(query.trim())
        if (foldedQuery.isEmpty()) return null

        val foldedName = fold(displayName)
        val nameHit = when {
            foldedName.startsWith(foldedQuery) -> Rank.WORD_START
            // Word starts: any segment boundary (space, hyphen, dot…).
            foldedName.split(' ', '-', '.', '(', ')', '\'')
                .any { it.isNotEmpty() && it.startsWith(foldedQuery) } -> Rank.WORD_START
            foldedName.contains(foldedQuery) -> Rank.SUBSTRING
            else -> null
        }
        if (nameHit != null) return nameHit

        val queryDigits = digitsOf(query)
        if (queryDigits.length >= MIN_PHONE_QUERY_DIGITS &&
            normalizedPhone?.contains(queryDigits) == true
        ) {
            return Rank.PHONE
        }
        return null
    }

    /**
     * Convenience for list filtering: keep matches, best rank first, ties
     * broken by the existing order of [items] (callers pre-sort by their
     * own rule — alphabetical, recency — and that order survives within a
     * rank band).
     */
    fun <T> filterRanked(
        items: List<T>,
        query: String,
        name: (T) -> String,
        phone: (T) -> String?,
    ): List<T> {
        if (query.isBlank()) return items
        return items.mapNotNull { item ->
            match(query, name(item), phone(item))?.let { rank -> rank to item }
        }
            .sortedBy { (rank, _) -> rank.ordinal }
            .map { (_, item) -> item }
    }
}
