package app.orbit.notify

import app.orbit.domain.contactFixture
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * NOTIF-14 / NOTIF-15: who a nudge names. The list's next person, once; never
 * the same person in two nudges for a list in a row.
 */
class NudgeSubjectTest {

    private val kai = contactFixture(id = 7L, displayName = "Kai Nakamura")

    @Test
    fun namesTheHead_whenNobodyWasNamedBefore() {
        assertEquals(kai, nudgeSubject(head = kai, lastNamedContactId = null))
    }

    @Test
    fun namesTheHead_whenSomeoneElseWasNamedLast() {
        assertEquals(kai, nudgeSubject(head = kai, lastNamedContactId = 8L))
    }

    @Test
    fun holdsTheNameBack_whenTheHeadWasNamedLast() {
        assertNull(nudgeSubject(head = kai, lastNamedContactId = 7L))
    }

    @Test
    fun namesNobody_whenTheListSurfacesNobody() {
        assertNull(nudgeSubject(head = null, lastNamedContactId = null))
    }

    @Test
    fun namesNobody_whenTheHeadHasNoName() {
        assertNull(nudgeSubject(head = contactFixture(id = 9L, displayName = "  "), lastNamedContactId = null))
    }
}
