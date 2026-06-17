package app.orbit.di

import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.CallEventRepositoryImpl
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ContactRepositoryImpl
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.ListRepositoryImpl
import app.orbit.data.repository.NoteRepository
import app.orbit.data.repository.NoteRepositoryImpl
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.data.repository.RuleTemplateRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Repository Hilt module. @InstallIn(SingletonComponent::class) — app-lifetime scope.
 *
 * Binds each repository interface to its Room-backed `*Impl`. `@Singleton` on
 * each binding guarantees one instance per app lifetime. Hilt resolves the
 * `*Impl` via `@Inject constructor`.
 *
 * Visibility: each `bind*` method is `internal` so it can take an
 * `internal`-visibility `*Impl` parameter without exposing it through a public
 * function signature (DATA-06 invariant). Dagger/KSP honors `internal` on
 * these abstract binding methods and generates the module factory with
 * matching visibility.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton internal abstract fun bindContactRepository(impl: ContactRepositoryImpl): ContactRepository
    @Binds @Singleton internal abstract fun bindListRepository(impl: ListRepositoryImpl): ListRepository
    @Binds @Singleton internal abstract fun bindCallEventRepository(impl: CallEventRepositoryImpl): CallEventRepository
    @Binds @Singleton internal abstract fun bindNoteRepository(impl: NoteRepositoryImpl): NoteRepository
    @Binds @Singleton internal abstract fun bindRuleTemplateRepository(impl: RuleTemplateRepositoryImpl): RuleTemplateRepository
}
