package app.orbit.ui.screens.picker

import app.cash.turbine.test
import app.orbit.domain.undo.UndoStack
import app.orbit.testutil.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Picker-commit lifecycle — behavior of the app-level snackbar host's
 * ViewModel: Undo replays (and clears) the depth-1 inverse on the app scope,
 * a failed inverse surfaces "Couldn't undo that" on the bus instead of
 * crashing, and an empty stack is a quiet no-op.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PickerCommitSnackbarHostViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun fixture(): Triple<PickerCommitSnackbarHostViewModel, UndoStack, PickerCommitBus> {
        val undoStack = UndoStack()
        val commitBus = PickerCommitBus()
        val vm = PickerCommitSnackbarHostViewModel(
            commitBus = commitBus,
            undoStack = undoStack,
            // Unconfined test dispatcher — undo runs synchronously in-test.
            appScope = CoroutineScope(SupervisorJob() + mainDispatcherRule.testDispatcher),
        )
        return Triple(vm, undoStack, commitBus)
    }

    @Test
    fun `onUndo runs the pending inverse and clears the stack`() = runTest {
        val (vm, undoStack, _) = fixture()
        var ran = false
        undoStack.put(
            UndoStack.PendingUndo(inverse = { ran = true }, label = "Added 1 to In touch"),
        )

        vm.onUndo()

        assertTrue("inverse must run", ran)
        assertNull("depth-1 entry must be consumed", undoStack.peek())
    }

    @Test
    fun `failed inverse publishes couldn't undo that instead of throwing`() = runTest {
        val (vm, undoStack, commitBus) = fixture()
        undoStack.put(
            UndoStack.PendingUndo(
                inverse = { throw IllegalStateException("simulated cipher failure") },
                label = "Added 1 to In touch",
            ),
        )

        commitBus.events.test {
            vm.onUndo()
            assertEquals("Couldn't undo that", awaitItem().message)
        }
    }

    @Test
    fun `onUndo with nothing pending is a quiet no-op`() = runTest {
        val (vm, _, commitBus) = fixture()

        commitBus.events.test {
            vm.onUndo()
            expectNoEvents()
        }
    }
}
