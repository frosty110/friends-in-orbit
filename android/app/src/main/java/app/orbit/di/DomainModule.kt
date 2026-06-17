package app.orbit.di

import app.orbit.domain.clock.Clock
import app.orbit.domain.clock.SystemClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.ZoneId
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-lifetime CoroutineScope qualifier. Used by
 * [app.orbit.data.repository.RuleTemplateRepositoryImpl] to host the
 * `stateIn(...)` cache that elides the N+1 in
 * [app.orbit.domain.usecase.MarkCalledUseCase]. Distinct from the private
 * `appScope` in [app.orbit.OrbitApp] (which serves the keystore pre-warm +
 * digest scheduling); repositories that need an app-lifetime scope inject
 * through this qualifier instead of reaching into OrbitApp directly.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Hilt module. @InstallIn(SingletonComponent::class) — app-lifetime scope.
 *
 * Binds the `Clock` interface to `SystemClock`. `SystemClock` has a zero-arg
 * constructor on HEAD, so `@Inject constructor()` resolves trivially.
 *
 * Provides the default [ZoneId]. As of ADR 0008 (2026-06-12) the consumer is
 * [app.orbit.domain.usecase.WidgetSurfaceUseCase], which uses it to compute the
 * cross-list time-of-day penalty (`timeOfDayPenalty`). SurfaceNext/SurfaceQueue
 * no longer take a ZoneId — active hours stopped being a surfacing gate.
 * If a per-list ZoneId is ever introduced (e.g. a "Late night" list pinned to a
 * specific tz), this becomes qualifier-scoped.
 *
 * Use cases (SurfaceNext, MarkCalled, SkipContact, PauseContact) are
 * constructor-resolved via `@Inject constructor` — no UseCaseModule is authored.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {

    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemClock): Clock

    companion object {
        // Future: if a per-list ZoneId is introduced (e.g. a "Late night"
        // list pinned to a specific tz), convert this into a `@DefaultZoneId`
        // qualifier so the per-list binding doesn't shadow the default one.
        @Provides
        fun provideZoneId(): ZoneId = ZoneId.systemDefault()

        /**
         * Application-lifetime CoroutineScope. Default dispatcher
         * (the cache is a 3-row in-memory map that never blocks; using Default
         * keeps it off Main without grabbing an IO thread). SupervisorJob
         * isolates a single failing subscription from cancelling the graph.
         */
        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
