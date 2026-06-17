package app.orbit.data.db

/**
 * Thin abstraction over [androidx.room.RoomDatabase.withTransaction].
 *
 * This seam lets the bulk use cases
 * ([app.orbit.domain.usecase.BulkRemoveFromListUseCase],
 * [app.orbit.domain.usecase.BulkIgnoreUseCase],
 * [app.orbit.domain.usecase.BulkPauseUseCase]) be JVM-unit-tested
 * without instantiating Room. Production wiring binds [RoomTransactionRunner],
 * which delegates to `db.withTransaction(block)`.
 *
 * Tests provide a passthrough impl that runs the block directly:
 * ```kotlin
 * private val passThruTx = object : TransactionRunner {
 *     override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
 * }
 * ```
 *
 * Note: the [block] passed here MUST NOT switch dispatchers
 * (`withContext(Dispatchers.X)`) — Room confines suspending DAO calls to its
 * internal transaction executor.
 */
interface TransactionRunner {
    suspend fun <T> withTransaction(block: suspend () -> T): T
}
