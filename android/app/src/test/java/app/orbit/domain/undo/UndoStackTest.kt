package app.orbit.domain.undo

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [UndoStack] — depth-1 undo holder used by the batch use cases
 * (MOVE-07, BulkIgnore, BulkPause). Verifies the load-bearing invariants:
 *   1. depth-1 replacement (newer put replaces older entry)
 *   2. take() returns AND clears (no leakage to a second take)
 *   3. peek() does not clear
 *   4. clear() unconditionally empties
 *   5. take() on empty returns null
 *   6. inverse closure round-trips through put → take → invoke()
 */
class UndoStackTest {

    @Test
    fun put_then_take_returns_entry() {
        val stack = UndoStack()
        val entry = UndoStack.PendingUndo(inverse = { /* no-op */ }, label = "Moved 3")
        stack.put(entry)
        val got = stack.take()
        assertNotNull(got)
        assertEquals("Moved 3", got.label)
    }

    @Test
    fun take_clears_pending() {
        val stack = UndoStack()
        stack.put(UndoStack.PendingUndo(inverse = {}, label = "A"))
        stack.take()
        assertNull(stack.peek())
        assertNull(stack.take())
    }

    @Test
    fun put_replaces_previous_depth1_invariant() {
        val stack = UndoStack()
        stack.put(UndoStack.PendingUndo(inverse = {}, label = "first"))
        stack.put(UndoStack.PendingUndo(inverse = {}, label = "second"))
        val got = stack.take()
        assertNotNull(got)
        assertEquals("second", got.label, "newer put MUST replace older entry")
        assertNull(stack.take(), "only one entry was ever held")
    }

    @Test
    fun peek_does_not_clear() {
        val stack = UndoStack()
        stack.put(UndoStack.PendingUndo(inverse = {}, label = "X"))
        stack.peek()
        stack.peek()
        val got = stack.take()
        assertNotNull(got)
        assertEquals("X", got.label)
    }

    @Test
    fun clear_unconditionally_empties_stack() {
        val stack = UndoStack()
        stack.put(UndoStack.PendingUndo(inverse = {}, label = "X"))
        stack.clear()
        assertNull(stack.peek())
        assertNull(stack.take())
    }

    @Test
    fun inverse_lambda_is_invocable_via_take() = runBlocking {
        val stack = UndoStack()
        var inverseRan = false
        stack.put(UndoStack.PendingUndo(inverse = { inverseRan = true }, label = "X"))
        stack.take()?.inverse?.invoke()
        assertEquals(true, inverseRan, "take() returns the inverse closure unmodified")
    }
}
