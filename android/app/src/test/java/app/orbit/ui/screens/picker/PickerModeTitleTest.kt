package app.orbit.ui.screens.picker

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * App-bar title pluralization for [pickerModeTitle].
 * "Move 1 contact" / "Copy 1 contact", never "1 contacts". Add mode ignores
 * the count entirely (locked copy "Add contacts").
 */
class PickerModeTitleTest {

    @Test
    fun add_mode_ignores_selection_count() {
        assertEquals("Add contacts", pickerModeTitle(PickerMode.Add, 0))
        assertEquals("Add contacts", pickerModeTitle(PickerMode.Add, 1))
        assertEquals("Add contacts", pickerModeTitle(PickerMode.Add, 7))
    }

    @Test
    fun move_mode_singularizes_at_one() {
        assertEquals("Move 1 contact", pickerModeTitle(PickerMode.Move, 1))
        assertEquals("Move 2 contacts", pickerModeTitle(PickerMode.Move, 2))
        assertEquals("Move 0 contacts", pickerModeTitle(PickerMode.Move, 0))
    }

    @Test
    fun copy_mode_singularizes_at_one() {
        assertEquals("Copy 1 contact", pickerModeTitle(PickerMode.Copy, 1))
        assertEquals("Copy 12 contacts", pickerModeTitle(PickerMode.Copy, 12))
    }
}
