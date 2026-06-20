package app.orbit.di

import app.orbit.calllog.ContentObserverController
import app.orbit.domain.CallLogResyncTrigger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module binding [CallLogResyncTrigger] to its production implementation
 * [ContentObserverController] (CORE-04 — card view return-from-dial resync).
 *
 * @Singleton matches the controller's own scope, so the interface resolves to
 * the same app-lifetime instance OrbitApp primed at cold start. Mirrors
 * [WidgetModule].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CallLogModule {

    @Binds
    @Singleton
    abstract fun bindCallLogResyncTrigger(
        impl: ContentObserverController,
    ): CallLogResyncTrigger
}
