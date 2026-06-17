package app.orbit.domain.search

import app.orbit.domain.search.ContactSearch.Rank
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class ContactSearchTest {

    // ── Diacritic folding ────────────────────────────────────────────────

    @Test
    fun `jose finds José`() {
        assertEquals(Rank.WORD_START, ContactSearch.match("jose", "José Álvarez", null))
    }

    @Test
    fun `accented query finds plain name`() {
        assertEquals(Rank.WORD_START, ContactSearch.match("josé", "Jose Smith", null))
    }

    // ── Ranking ──────────────────────────────────────────────────────────

    @Test
    fun `word start outranks mid-word`() {
        assertEquals(Rank.WORD_START, ContactSearch.match("jo", "Sarah Jones", null))
        assertEquals(Rank.SUBSTRING, ContactSearch.match("ari", "Maria", null))
    }

    @Test
    fun `hyphenated and parenthesised segments count as word starts`() {
        assertEquals(Rank.WORD_START, ContactSearch.match("lee", "Anna-Lee Park", null))
        assertEquals(Rank.WORD_START, ContactSearch.match("young", "(Young) Brandon", null))
    }

    // ── Phone digits ─────────────────────────────────────────────────────

    @Test
    fun `digit query matches normalized phone`() {
        assertEquals(Rank.PHONE, ContactSearch.match("404555", "Maya", "+14045550199"))
        assertEquals(Rank.PHONE, ContactSearch.match("(404) 555", "Maya", "+14045550199"))
    }

    @Test
    fun `short digit query never matches phone`() {
        assertNull(ContactSearch.match("40", "Maya", "+14045550199"))
    }

    @Test
    fun `name hit wins over phone hit`() {
        assertEquals(Rank.WORD_START, ContactSearch.match("4 the win", "4 The Win Pizza", "+440000"))
    }

    // ── Non-matches and blanks ───────────────────────────────────────────

    @Test
    fun `blank query matches nothing`() {
        assertNull(ContactSearch.match("  ", "Maya", "+14045550199"))
    }

    @Test
    fun `unrelated query is null`() {
        assertNull(ContactSearch.match("zz", "Maya", null))
    }

    // ── filterRanked ─────────────────────────────────────────────────────

    private data class Row(val name: String, val phone: String?)

    @Test
    fun `filterRanked orders by rank then preserves input order`() {
        val rows = listOf(
            Row("Maria", null), // SUBSTRING for "ari"
            Row("Ariana", null), // WORD_START
            Row("Zed", "+1305550123"), // no match
            Row("Ari Gold", null), // WORD_START — after Ariana in input
        )
        val out = ContactSearch.filterRanked(rows, "ari", { it.name }, { it.phone })
        assertEquals(listOf("Ariana", "Ari Gold", "Maria"), out.map { it.name })
    }

    @Test
    fun `filterRanked with blank query returns input unchanged`() {
        val rows = listOf(Row("B", null), Row("A", null))
        assertEquals(rows, ContactSearch.filterRanked(rows, "", { it.name }, { it.phone }))
    }
}
