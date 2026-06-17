package app.orbit.domain.undo

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-depth undo holder. Batch use cases (Move/Copy/Remove/Ignore/Pause)
 * stash an inverse `suspend () -> Unit` here so the snackbar's "Undo" tap can
 * replay it.
 *
 * Depth-1 by deliberate decision — newer batches replace older undos
 * (transactional undo, MOVE-03 / MOVE-07). [@Singleton] +
 * [@Volatile] keeps the pending entry alive across ViewModel rotation
 * and ensures happens-before across the
 * VM-write / snackbar-read pair.
 *
 * Survives rotation (singleton) but NOT process death (deliberate — undo
 * windows are sub-5-second; a process death is treated as a user-initiated
 * exit).
 */
@Singleton
class UndoStack @Inject constructor() {

    /**
     * One pending undo: the suspending [inverse] lambda the snackbar will run on
     * "Undo" tap, plus the [label] used for snackbar copy ("Moved 3 to Inner orbit").
     */
    data class PendingUndo(val inverse: suspend () -> Unit, val label: String)

    @Volatile private var pending: PendingUndo? = null

    /** Replace any prior pending entry with [entry] (depth-1 invariant). */
    fun put(entry: PendingUndo) {
        pending = entry
    }

    /** Return the current pending entry AND clear it. Returns `null` if empty. */
    fun take(): PendingUndo? {
        val current = pending
        pending = null
        return current
    }

    /** Return the current pending entry without clearing it. Returns `null` if empty. */
    fun peek(): PendingUndo? = pending

    /** Unconditionally drop the pending entry. */
    fun clear() {
        pending = null
    }
}
