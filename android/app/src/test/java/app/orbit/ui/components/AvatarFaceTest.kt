package app.orbit.ui.components

import kotlin.test.assertEquals
import org.junit.Test

/**
 * [avatarInitials] is the one monogram rule the app's avatar, the widgets and
 * the nudge's large icon share (UX rubric decision 5). The widget used to show
 * a single initial while the app showed two.
 */
class AvatarFaceTest {

    @Test
    fun twoWords_giveTwoLetters() {
        assertEquals("KN", avatarInitials("Kai Nakamura"))
    }

    @Test
    fun moreThanTwoWords_useTheFirstTwo() {
        assertEquals("SO", avatarInitials("Sarah Okafor Lindqvist"))
    }

    @Test
    fun oneWord_givesOneLetter() {
        assertEquals("M", avatarInitials("Mom"))
    }

    @Test
    fun extraSpaces_areIgnored_andLettersUpperCased() {
        assertEquals("AL", avatarInitials("  ana   lópez "))
    }

    @Test
    fun blankName_givesNoLetters() {
        assertEquals("", avatarInitials("   "))
    }

    @Test
    fun `initials take whole code points, so emoji names are not split`() {
        // U+1F33B SUNFLOWER is a surrogate pair; the old first() kept half.
        assertEquals("\uD83C\uDF3BS", avatarInitials("\uD83C\uDF3B Sam"))
    }
}
