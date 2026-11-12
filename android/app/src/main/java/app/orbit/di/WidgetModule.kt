package app.orbit.di

import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.widget.GlanceOneWidgetRefresher
import app.orbit.widget.OneWidgetRefresher
import app.orbit.widget.WorkManagerWidgetRefreshTrigger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module binding [WidgetRefreshTrigger] to its production implementation
 * [WorkManagerWidgetRefreshTrigger].
 *
 * @Singleton scope matches the scope of the injected use cases (all are
 * constructor-injected as transitive app-lifetime singletons via
 * [SingletonComponent]).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetModule {

    @Binds
    @Singleton
    abstract fun bindWidgetRefreshTrigger(
        impl: WorkManagerWidgetRefreshTrigger,
    ): WidgetRefreshTrigger

    /** WIDGET-12: Widget settings redraws its own widget at once. */
    @Binds
    abstract fun bindOneWidgetRefresher(
        impl: GlanceOneWidgetRefresher,
    ): OneWidgetRefresher
}
