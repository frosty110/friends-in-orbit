package app.orbit.data.db

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.orbit.data.entity.RuleKind
import app.orbit.data.keystore.DatabaseKeyProvider
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Builds the encrypted Room database per ADR-0005 (SQLCipher production tuning):
 *  - SQLCipher native libs loaded exactly once per process, guarded by AtomicBoolean
 *  - Application-context assertion prevents silent multi-instance via non-App contexts
 *  - Passphrase ByteArray zeroed in `finally` (belt-and-braces vs SupportFactory's own clear)
 *  - Five explicit PRAGMAs via SQLiteDatabaseHook.postKey — pinned to the SQLCipher 4 defaults
 *    so a library-default drift becomes visible in diffs
 *  - Strict migrations only — NO fallbackToDestructiveMigration (policy enforced here)
 *  - Seed callback inserts three RuleTemplate rows (KEEP_IN_TOUCH, LATE_NIGHT, ENERGIZE)
 *    inside a transaction on first DB creation
 *
 * This is the only file in the codebase that references `System.loadLibrary("sqlcipher")`,
 * `PRAGMA`, or `net.zetetic.database.sqlcipher.*`. The invariant is grep-enforced.
 *
 * Implementation note: sqlcipher-android 4.14.1 (the catalog-pinned artifact) exposes its
 * API under `net.zetetic.database.sqlcipher.*` with class name `SupportOpenHelperFactory`
 * (not the legacy `net.sqlcipher.database.SupportFactory`). Native lib loading uses
 * `System.loadLibrary("sqlcipher")` rather than the legacy `SQLiteDatabase.loadLibs(ctx)`.
 * `SQLiteDatabaseHook.{preKey,postKey}` receives a `SQLiteConnection`, and PRAGMAs run via
 * `connection.executeRaw(sql, emptyArray(), null)`. Semantics match ADR-0005 exactly; only
 * the symbol names differ from the legacy artifact.
 *
 * See: features/_foundations/ADRs/0005-sqlcipher-tuning.md
 * See: features/_foundations/ADRs/0002-sqlcipher-for-room.md
 */

private const val DB_NAME = "orbit.db"

private val libsLoaded = AtomicBoolean(false)

/**
 * Build the encrypted Room database. Called exactly once at process start by
 * Hilt's DatabaseModule on a background dispatcher — NOT from Application.onCreate
 * main thread. The runBlocking on the Keystore unwrap costs ~1–3 ms on TEE and is
 * bounded by a single call site.
 */
fun create(context: Context, keyProvider: DatabaseKeyProvider): OrbitDatabase {
    val app = context.applicationContext
    check(app is Application) {
        "DatabaseFactory requires an Application context; got ${app::class.java.name}"
    }
    if (libsLoaded.compareAndSet(false, true)) {
        System.loadLibrary("sqlcipher")
    }

    val passphrase: ByteArray = runBlocking { keyProvider.getOrCreatePassphrase() }
    try {
        val factory = SupportOpenHelperFactory(passphrase, PragmaHook, /* clearPassphrase = */ true)
        return Room.databaseBuilder(app, OrbitDatabase::class.java, DB_NAME)
            .openHelperFactory(factory)
            .addCallback(SeedCallback)
            // Strict migrations only — no fallbackToDestructiveMigration, ever.
            // v=1 → v=2 — ruleParamsOverrideJson.
            // v=2 → v=3 — ignoredAt, preIgnoreListMembershipsJson, isArchived.
            // v=3 → v=4 — normalizedPhone column + dedup + unique index.
            // v=4 → v=5 — M9 (LocalTime → seconds-of-day INTEGER).
            // v=5 → v=6 — drop orphaned partial unique index `index_lists_sortOrder_active`
            //              created by an earlier MIGRATION_4_5 revision; Room rejected it.
            // v=6 → v=7 — backfill `rule_templates.paramsJson` from "{}" to a proper
            //              discriminator-bearing default; without this Card View errors
            //              with "Something's off here" because the engine factory throws
            //              when decoding the placeholder.
            // v=7 → v=8 — additive non-unique indices on
            //              call_events.occurredAt and contacts(isIgnored, ignoredAt).
            //              Eliminates filesort on Call Log + full-table scan on
            //              Settings → Ignored Contacts. No data change.
            // v=8 → v=9 — denormalize lists.dueCount for ADR 0006
            //              Rule 2 (single-query Home path). Additive INTEGER NOT
            //              NULL DEFAULT 0 column with one-shot Kotlin-supplied
            //              `now` backfill from list_memberships.
            // v=9 → v=10 — new contact_phones table (all numbers
            //              per contact, unique normalizedPhone, FK CASCADE)
            //              backfilled from contacts' primary numbers so
            //              second-number calls reconcile.
            // v=10 → v=11 — additive contacts.isStarred (INTEGER
            //              NOT NULL DEFAULT 0) mirroring ContactsContract
            //              STARRED; refreshed by the ingest delta-sync.
            // v=11 → v=12 — additive lists.nudgeScheduleJson
            //              TEXT DEFAULT NULL; backfilled with DEFAULT_JSON for
            //              every existing list row (D-03/NOTIF-10/11).
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
            )
            .build()
    } finally {
        passphrase.fill(0)
    }
}

/**
 * PRAGMAs per ADR-0005. Set after SQLCipher has the key (`postKey`) and before Room
 * opens the DB, so every Room-owned connection inherits these values.
 *
 * All five are set explicitly — including the three that match SQLCipher 4 defaults —
 * so a silent library-default change shows up in a diff rather than as a perf/behavior
 * regression.
 */
private object PragmaHook : SQLiteDatabaseHook {
    override fun preKey(connection: SQLiteConnection) {
        // No-op. Per SQLCipher docs, preKey is for PRAGMAs that must precede the KEY step
        // (e.g., cipher suite selection). Our config uses SQLCipher 4 defaults for cipher
        // selection, so preKey is intentionally empty.
    }

    override fun postKey(connection: SQLiteConnection) {
        connection.executeRaw("PRAGMA cipher_page_size = 4096;", emptyArray(), null)
        connection.executeRaw("PRAGMA kdf_iter = 256000;", emptyArray(), null)
        // OFF per ADR-0005 §Decision: zeroing encryption buffers on free has measurable
        // perf cost with zero realistic threat-model benefit on Android (memory is
        // per-process-isolated; mlock is unreliable on Android 11+). Matches SQLCipher 4
        // default — pinned here so a library-default flip is visible in diff review.
        connection.executeRaw("PRAGMA cipher_memory_security = OFF;", emptyArray(), null)
        connection.executeRaw("PRAGMA journal_mode = WAL;", emptyArray(), null)
        connection.executeRaw("PRAGMA synchronous = NORMAL;", emptyArray(), null)
    }
}

/**
 * Seed callback — inserts three RuleTemplate rows on first DB creation. Uses the raw
 * SupportSQLiteDatabase handle because Room has not finished initializing DAOs at
 * callback time; invoking a DAO here would re-enter Room.
 *
 * `paramsJson` carries the kotlinx-serialization-encoded default for each
 * [RuleParams] subtype. The discriminator is the `"type"` field
 * ([JsonProvider.json] sets `classDiscriminator = "type"`), so decode in
 * [engineFor] / [resolveParamsFor] resolves the correct engine. With
 * `encodeDefaults = false` the JSON collapses to just `{"type":"<kind>"}`
 * — every numeric field falls through to the data-class default in
 * [RuleParams.kt]. An empty `"{}"` would deserialize-throw because the
 * sealed class needs the discriminator; that was the cause of the
 * Card View "Something's off here" error before the fix.
 */
private object SeedCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            insertTemplate(db, name = RuleKind.KEEP_IN_TOUCH.name, kind = RuleKind.KEEP_IN_TOUCH)
            insertTemplate(db, name = RuleKind.LATE_NIGHT.name, kind = RuleKind.LATE_NIGHT)
            insertTemplate(db, name = RuleKind.ENERGIZE.name, kind = RuleKind.ENERGIZE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertTemplate(db: SupportSQLiteDatabase, name: String, kind: RuleKind) {
        db.execSQL(
            "INSERT INTO rule_templates (name, kind, paramsJson) VALUES (?, ?, ?)",
            arrayOf(name, kind.name, paramsJsonFor(kind)),
        )
    }
}

/**
 * Default `paramsJson` for a [RuleKind] — matches the discriminator the
 * `RuleParams` sealed class expects. Kept on the data layer (next to
 * [SeedCallback] and [MIGRATION_6_7]) rather than on the domain layer to
 * avoid a circular import; the literal JSON shape is short, stable, and
 * verified against `JsonProvider.json.encodeToString(...)` in unit tests.
 */
internal fun paramsJsonFor(kind: RuleKind): String = when (kind) {
    RuleKind.KEEP_IN_TOUCH -> "{\"type\":\"keepInTouch\"}"
    RuleKind.LATE_NIGHT -> "{\"type\":\"lateNight\"}"
    RuleKind.ENERGIZE -> "{\"type\":\"energize\"}"
}
