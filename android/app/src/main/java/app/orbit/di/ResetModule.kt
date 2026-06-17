package app.orbit.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * SET-06 — Hilt module for [app.orbit.data.repository.ResetService].
 *
 * ResetService has @Inject constructor + @Singleton, so no @Binds /
 * @Provides is required — Dagger generates a factory automatically.
 * This file exists for symmetry with the other modules under `di/` and as
 * a structural anchor for future reset-adjacent providers (e.g. an
 * Undo-reset flow if v1.1 wants one).
 */
@Module
@InstallIn(SingletonComponent::class)
object ResetModule
