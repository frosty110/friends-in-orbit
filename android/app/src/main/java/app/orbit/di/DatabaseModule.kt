package app.orbit.di

import android.content.Context
import app.orbit.data.AppPrefs
import app.orbit.data.dao.CallEventDao
import app.orbit.data.dao.ContactDao
import app.orbit.data.dao.ListDao
import app.orbit.data.dao.ListMembershipDao
import app.orbit.data.dao.NoteDao
import app.orbit.data.dao.RuleTemplateDao
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.db.RoomTransactionRunner
import app.orbit.data.db.TransactionRunner
import app.orbit.data.db.create as createDatabase
import app.orbit.data.keystore.DatabaseKeyProvider
import app.orbit.domain.JsonProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * Database Hilt module. @InstallIn(SingletonComponent::class) — app-lifetime scope.
 *
 * Provides the three Context-bound singletons (`AppPrefs`, `DatabaseKeyProvider`,
 * `OrbitDatabase`), the six DAO accessors derived from the DB, and the shared
 * kotlinx-serialization `Json` instance consumed by the rule-engine use cases.
 *
 * B4 invariant: `provideOrbitDatabase` passes `ctx` UNCAST to `createDatabase`.
 * `DatabaseFactory.create()` internally asserts the applicationContext check
 * (see that file). Casting `ctx` here would risk ClassCastException when Hilt
 * returns a ContextWrapper instead of the Application subclass directly.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideAppPrefs(@ApplicationContext ctx: Context): AppPrefs = AppPrefs(ctx)

    @Provides @Singleton
    fun provideDatabaseKeyProvider(@ApplicationContext ctx: Context): DatabaseKeyProvider =
        DatabaseKeyProvider(ctx)

    @Provides @Singleton
    fun provideOrbitDatabase(
        @ApplicationContext ctx: Context,
        keyProvider: DatabaseKeyProvider,
    ): OrbitDatabase = createDatabase(ctx, keyProvider)

    /**
     * Bind [RoomTransactionRunner] to the [TransactionRunner]
     * abstraction so bulk use cases can be JVM-unit-tested with a passthrough
     * fake. `@Provides` (not `@Binds`) because [DatabaseModule] is an `object`
     * — Hilt's `@Binds` requires an `abstract class` module.
     */
    @Provides @Singleton
    fun provideTransactionRunner(impl: RoomTransactionRunner): TransactionRunner = impl

    @Provides fun provideContactDao(db: OrbitDatabase): ContactDao = db.contactDao()
    @Provides fun provideListDao(db: OrbitDatabase): ListDao = db.listDao()
    @Provides fun provideListMembershipDao(db: OrbitDatabase): ListMembershipDao = db.listMembershipDao()
    @Provides fun provideCallEventDao(db: OrbitDatabase): CallEventDao = db.callEventDao()
    @Provides fun provideNoteDao(db: OrbitDatabase): NoteDao = db.noteDao()
    @Provides fun provideRuleTemplateDao(db: OrbitDatabase): RuleTemplateDao = db.ruleTemplateDao()

    @Provides @Singleton
    fun provideJson(): Json = JsonProvider.json
}
