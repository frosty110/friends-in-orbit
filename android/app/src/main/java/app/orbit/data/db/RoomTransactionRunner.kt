package app.orbit.data.db

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [TransactionRunner] backed by Room's `db.withTransaction(block)`
 * extension. Singleton because [OrbitDatabase] itself is provided as a
 * Singleton — re-instantiating the runner per call would be a misuse but
 * harmless; the @Singleton scope just matches the database lifecycle.
 */
@Singleton
class RoomTransactionRunner @Inject constructor(
    private val db: OrbitDatabase,
) : TransactionRunner {
    override suspend fun <T> withTransaction(block: suspend () -> T): T =
        db.withTransaction(block)
}
