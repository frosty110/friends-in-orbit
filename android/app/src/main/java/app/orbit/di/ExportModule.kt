package app.orbit.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * EXPORT-01 — Hilt module anchor for [app.orbit.domain.export.ExportService].
 *
 * ExportService has @Inject constructor + @Singleton, so no @Binds /
 * @Provides is required — Dagger generates the factory automatically.
 * This file exists for symmetry with the other modules under `app.orbit.di`.
 */
@Module
@InstallIn(SingletonComponent::class)
object ExportModule
