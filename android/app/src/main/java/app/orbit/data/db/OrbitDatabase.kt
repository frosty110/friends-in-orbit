package app.orbit.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.orbit.data.dao.CallEventDao
import app.orbit.data.dao.ContactDao
import app.orbit.data.dao.ListDao
import app.orbit.data.dao.ListMembershipDao
import app.orbit.data.dao.NoteDao
import app.orbit.data.dao.RuleTemplateDao
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ContactPhoneEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.NoteEntity
import app.orbit.data.entity.RuleKind
import app.orbit.data.entity.RuleTemplateEntity
import app.orbit.notify.NudgeSchedule

/**
 * Room database for Orbit. Schema v=12 (nudge schedule).
 *
 * Schema JSON exports to `android/app/schemas/app.orbit.data.db.OrbitDatabase/{1..11}.json`.
 * v1→v2: ruleParamsOverrideJson column.
 * v2→v3: ignoredAt + preIgnoreListMembershipsJson + isArchived columns.
 * v3→v4: normalizedPhone column with unique index, dedup of duplicate
 *        contacts via FK re-pointing of list_memberships / call_events / notes.
 * v4→v5: LocalTime stored as seconds-of-day INTEGER (was HH:mm TEXT)
 *        on the lists table.
 * v5→v6: drop the orphaned `index_lists_sortOrder_active` partial unique index that
 *        an earlier MIGRATION_4_5 created. Room's strict TableInfo validation rejects
 *        any index on a table that the entity doesn't declare, and `@Index` cannot
 *        express partial-unique. The unique-active invariant is enforced in
 *        ListRepositoryImpl.reorder via range-only renumber inside withTransaction.
 * v6→v7: backfill `rule_templates.paramsJson` for any row still carrying the legacy
 *        `"{}"` placeholder. The sealed `RuleParams` requires a `"type"` discriminator;
 *        decoding `"{}"` throws and bubbled up as the Card View "Something's off here"
 *        error every time the rule engine resolved params for a seeded list.
 * v7→v8: additive non-unique indices on call_events.occurredAt
 *        (eliminates filesort on Call Log) and contacts(isIgnored, ignoredAt)
 *        (eliminates full-table scan on Settings → Ignored Contacts). No data change.
 * v8→v9: denormalize `lists.dueCount` for ADR 0006 Rule 2.
 *        Additive INTEGER NOT NULL DEFAULT 0 column; backfilled in-migration
 *        from a one-shot COUNT against `list_memberships` with the JVM-side
 *        `now` passed as a bound param so SQLCipher's CURRENT_TIMESTAMP
 *        behavior is irrelevant. Kept fresh by the seven mutator use cases;
 *        staleness window for time-based transitions is recomputed on app
 *        foreground.
 * v9→v10: new `contact_phones` table (one row per device phone
 *        number, unique on normalizedPhone, FK CASCADE to contacts) so calls
 *        to a contact's second number reconcile. Backfilled from each
 *        contact's existing primary `phoneNumber` / `normalizedPhone`.
 * v10→v11: additive `contacts.isStarred` INTEGER NOT NULL
 *        DEFAULT 0 mirroring ContactsContract STARRED (Android favorites).
 *        No backfill needed: the next ingest delta-sync refreshes the flag
 *        from the device alongside displayName / photoUri.
 * v11→v12: additive `lists.nudgeScheduleJson` TEXT DEFAULT
 *        NULL (D-01/NOTIF-10/11). All existing rows are backfilled with the
 *        default nudge schedule (all 7 days at 10:00) per D-03 (default-ON).
 *        The migration literal is sourced from [app.orbit.notify.NudgeSchedule.DEFAULT_JSON]
 *        to enforce byte-exact parity with the serializer output.
 *
 * The `MigrationTestHelper` baseline test asserts the runtime schema matches
 * the v=1 JSON; [Migration1To2Test] asserts the v=1 → v=2 transition;
 * [Migration2To3Test] asserts the v=2 → v=3 transition.
 *
 * Construction concerns (SQLCipher `loadLibs`, passphrase handoff, PRAGMAs, seed callback,
 * Application-context assertion) live in `DatabaseFactory.kt`, not here. This class is
 * pure schema: `@Database(entities = [...])` + `@TypeConverters` + abstract DAO getters
 * + the migration objects.
 *
 * Lifecycle owner: Hilt's DatabaseModule (`@Singleton`-scoped provider). No `@Singleton`
 * on the class itself — the singleton guarantee comes from the Hilt module provider plus
 * `DatabaseFactory.create()` asserting an `Application` context.
 */
@Database(
    entities = [
        ContactEntity::class,
        ContactPhoneEntity::class,
        ListEntity::class,
        ListMembershipEntity::class,
        CallEventEntity::class,
        NoteEntity::class,
        RuleTemplateEntity::class,
    ],
    version = 12,
    exportSchema = true,
)
@TypeConverters(OrbitTypeConverters::class)
abstract class OrbitDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun listDao(): ListDao
    abstract fun listMembershipDao(): ListMembershipDao
    abstract fun callEventDao(): CallEventDao
    abstract fun noteDao(): NoteDao
    abstract fun ruleTemplateDao(): RuleTemplateDao
}

/**
 * Migration: add the nullable `ruleParamsOverrideJson` column to `lists` so
 * List Configuration can persist per-list rule-param overrides (LIST-04 end-to-end).
 *
 * Additive nullable column with no default backfill — every existing row carries
 * `ruleParamsOverrideJson = NULL`, which is `OverrideResolver`'s "fall through to
 * template params" branch. Strict-migration policy preserved (no
 * `fallbackToDestructiveMigration` in [DatabaseFactory]).
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE lists ADD COLUMN ruleParamsOverrideJson TEXT DEFAULT NULL")
    }
}

/**
 * Migration: add the IGNORE-06 sort-order column (`ignoredAt`), the
 * un-ignore drift-restore snapshot column (`preIgnoreListMembershipsJson`), and
 * the CONTACT-06 archive flag (`isArchived`) to `contacts`. All three nullable
 * or default-valued — every existing row backfills to NULL/0, which is the
 * "never ignored / never archived" branch in the ignore/archive use cases.
 *
 * Archive is a SEPARATE hide mechanism from ignore. ArchiveContactUseCase
 * writes ONLY `isArchived = 1`; SurfaceNextUseCase filters `AND NOT isArchived`;
 * SettingsIgnoredScreen filters `WHERE isArchived = 0 AND isIgnored = 1`.
 *
 * Strict-migration policy preserved (no `fallbackToDestructiveMigration` in
 * [DatabaseFactory]). Schema JSON export 3.json must be tracked in git on first
 * compile (KSP emits it automatically when version bumps).
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contacts ADD COLUMN ignoredAt INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE contacts ADD COLUMN preIgnoreListMembershipsJson TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE contacts ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Hardening migration: adds a `normalizedPhone` column to `contacts`,
 * dedupes any existing duplicate humans (re-pointing FK rows on the survivor),
 * then adds the unique index that makes the dedup invariant. A non-unique index
 * on `phoneNumber` is added in the same step for display-side queries.
 *
 * The backfill normalizer is intentionally inlined (digit-only with leading `+`,
 * matching `ContactsReader.normalizeForMatch`). We do NOT call the libphonenumber
 * `PhoneNumberNormalizer` from a migration — migrations should be deterministic
 * and self-contained. The next ingest pass will recompute via libphonenumber and
 * the new transactional upsert path will overwrite when the values diverge.
 *
 * Dedup ordering matters: re-point FKs (list_memberships, call_events, notes) to
 * the survivor BEFORE deleting losers, so CASCADE doesn't silently drop history.
 * `INSERT OR IGNORE` on list_memberships handles the case where the survivor
 * already has a membership for that listId — the loser's row is then CASCADE-
 * deleted when we delete its contact.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Add the column with an empty-string default so existing rows are valid.
        db.execSQL("ALTER TABLE contacts ADD COLUMN normalizedPhone TEXT NOT NULL DEFAULT ''")

        // 2. Backfill normalizedPhone via Kotlin-side normalization (loop in-process).
        db.query("SELECT id, phoneNumber FROM contacts").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val raw = cursor.getString(1) ?: ""
                val norm = normalizeRaw(raw)
                db.execSQL(
                    "UPDATE contacts SET normalizedPhone = ? WHERE id = ?",
                    arrayOf<Any>(norm, id),
                )
            }
        }

        // 3. Dedup: for each non-empty normalizedPhone with > 1 row, the smallest
        // id wins. Re-point list_memberships / call_events / notes to the survivor,
        // then delete losers (CASCADE cleans the loser's old membership rows).
        val pendingDeletes = mutableListOf<Long>()
        db.query(
            "SELECT normalizedPhone, GROUP_CONCAT(id) FROM contacts " +
                "WHERE normalizedPhone != '' GROUP BY normalizedPhone HAVING COUNT(*) > 1",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val ids = cursor.getString(1).split(",").map(String::toLong).sorted()
                val survivor = ids.first()
                val losers = ids.drop(1)
                for (loser in losers) {
                    // INSERT OR IGNORE preserves existing (survivor,listId) pairs;
                    // the loser's now-orphaned membership rows are wiped just below.
                    db.execSQL(
                        "INSERT OR IGNORE INTO list_memberships(contactId, listId, addedAt, nextDueAt, skipCount) " +
                            "SELECT ?, listId, addedAt, nextDueAt, skipCount FROM list_memberships WHERE contactId = ?",
                        arrayOf<Any>(survivor, loser),
                    )
                    db.execSQL(
                        "DELETE FROM list_memberships WHERE contactId = ?",
                        arrayOf<Any>(loser),
                    )
                    db.execSQL(
                        "UPDATE call_events SET contactId = ? WHERE contactId = ?",
                        arrayOf<Any>(survivor, loser),
                    )
                    db.execSQL(
                        "UPDATE notes SET contactId = ? WHERE contactId = ?",
                        arrayOf<Any>(survivor, loser),
                    )
                    pendingDeletes += loser
                }
            }
        }
        for (id in pendingDeletes) {
            db.execSQL("DELETE FROM contacts WHERE id = ?", arrayOf<Any>(id))
        }

        // 4. Now that duplicates are gone, enforce uniqueness.
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_contacts_normalizedPhone " +
                "ON contacts(normalizedPhone)",
        )
        // 5. Display-side phoneNumber lookups stay indexed (non-unique).
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_contacts_phoneNumber " +
                "ON contacts(phoneNumber)",
        )
    }

    /**
     * Migration-local normalizer. Mirrors `ContactsReader.normalizeForMatch`
     * (digits only, plus a leading `+`). Inlined so the migration has no
     * dependency on app code that may itself evolve.
     */
    private fun normalizeRaw(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for ((i, ch) in raw.withIndex()) {
            if (ch.isDigit()) sb.append(ch)
            else if (ch == '+' && i == 0) sb.append(ch)
        }
        return sb.toString()
    }
}

/**
 * M9 (LocalTime round-trip): `activeHoursStart` and `activeHoursEnd` change
 * affinity from TEXT (`HH:mm` formatted) to INTEGER (seconds-of-day) so
 * sub-minute precision survives the round trip. SQLite cannot ALTER COLUMN
 * TYPE in place — we rebuild the table with the new column types and
 * convert existing string values via SQL `substr`/`CAST` (`HH:mm` →
 * HH * 3600 + MM * 60).
 *
 * Defensive sortOrder dedup also runs here: any pre-existing collision among
 * non-archived rows is renumbered 0..N-1 in `(sortOrder ASC, name ASC)` order
 * so the in-app `reorder` logic starts from a clean monotonic sequence.
 *
 * Earlier revisions of this migration also created a partial unique index
 * `(sortOrder) WHERE isArchived = 0`. Room's strict TableInfo validation
 * rejects any index on a table the entity doesn't declare (and `@Index`
 * cannot express partial-unique), so the index now lives only as a
 * documented invariant enforced by [ListRepositoryImpl.reorder]. See
 * [MIGRATION_5_6] for the cleanup of any v5 DB that already has it.
 *
 * Strict-migration policy preserved (no `fallbackToDestructiveMigration` in
 * [DatabaseFactory]).
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Rebuild `lists` with INTEGER active-hours columns. The entity's
        // FK to rule_templates(id) ON DELETE SET NULL is preserved verbatim.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `lists_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`isArchived` INTEGER NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`smartRuleJson` TEXT, " +
                "`ruleTemplateId` INTEGER, " +
                "`activeHoursStart` INTEGER, " +
                "`activeHoursEnd` INTEGER, " +
                "`notificationsEnabled` INTEGER NOT NULL, " +
                "`ruleParamsOverrideJson` TEXT, " +
                "FOREIGN KEY(`ruleTemplateId`) REFERENCES `rule_templates`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL )",
        )

        // 2. Copy rows, converting "HH:mm" → seconds-of-day. NULL stays NULL.
        // CAST(substr(...) AS INTEGER) silently coerces malformed strings to 0,
        // which is acceptable because the surface-side code treats 0 as
        // "midnight" — strictly better than a parse-throw on legacy data.
        db.execSQL(
            "INSERT INTO `lists_new` (" +
                "`id`, `name`, `sortOrder`, `isArchived`, `type`, `smartRuleJson`, " +
                "`ruleTemplateId`, `activeHoursStart`, `activeHoursEnd`, " +
                "`notificationsEnabled`, `ruleParamsOverrideJson`) " +
                "SELECT " +
                "`id`, `name`, `sortOrder`, `isArchived`, `type`, `smartRuleJson`, " +
                "`ruleTemplateId`, " +
                "CASE WHEN `activeHoursStart` IS NULL THEN NULL " +
                "ELSE (CAST(substr(`activeHoursStart`, 1, 2) AS INTEGER) * 3600 + " +
                "CAST(substr(`activeHoursStart`, 4, 2) AS INTEGER) * 60) END, " +
                "CASE WHEN `activeHoursEnd` IS NULL THEN NULL " +
                "ELSE (CAST(substr(`activeHoursEnd`, 1, 2) AS INTEGER) * 3600 + " +
                "CAST(substr(`activeHoursEnd`, 4, 2) AS INTEGER) * 60) END, " +
                "`notificationsEnabled`, `ruleParamsOverrideJson` " +
                "FROM `lists`",
        )

        // 3. Replace the table.
        db.execSQL("DROP TABLE `lists`")
        db.execSQL("ALTER TABLE `lists_new` RENAME TO `lists`")

        // 4. Recreate indices that the entity declares. Room's KSP-generated
        // schema always emits `index_<table>_<column>` named indices; we mirror
        // the names so a future automigration sees the same shape.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_lists_ruleTemplateId` " +
                "ON `lists` (`ruleTemplateId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_lists_sortOrder` " +
                "ON `lists` (`sortOrder`)",
        )

        // 5. Defensive sortOrder dedup among non-archived rows so the
        // in-app reorder logic starts from a clean monotonic 0..N-1 sequence.
        db.query(
            "SELECT `id` FROM `lists` WHERE `isArchived` = 0 " +
                "ORDER BY `sortOrder` ASC, `name` ASC",
        ).use { cursor ->
            var index = 0
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                db.execSQL(
                    "UPDATE `lists` SET `sortOrder` = ? WHERE `id` = ?",
                    arrayOf<Any>(index, id),
                )
                index += 1
            }
        }
    }
}

/**
 * Drop the orphaned `index_lists_sortOrder_active` partial unique index that
 * an earlier revision of [MIGRATION_4_5] created. Room reads every index on
 * the `lists` table during schema validation; finding an extra one that the
 * entity doesn't declare aborts startup with `IllegalStateException:
 * Migration didn't properly handle: lists`. The unique-active invariant is
 * enforced in [ListRepositoryImpl.reorder] via range-only renumber inside
 * `db.withTransaction`, so dropping the index is safe.
 *
 * `IF EXISTS` makes this a no-op for any v5 DB that never had the index
 * (e.g. fresh installs that bypass migrations entirely).
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS `index_lists_sortOrder_active`")
    }
}

/**
 * Backfill `rule_templates.paramsJson` for rows still carrying the legacy
 * `"{}"` placeholder that the original [SeedCallback] wrote. The sealed
 * `RuleParams` requires the `"type"` discriminator (see
 * [JsonProvider]); decoding `"{}"` throws a `SerializationException`,
 * which `SurfaceNextUseCase` lets bubble through `combine { … }` so
 * `CardViewViewModel.uiState` falls into the `.catch` branch and renders
 * the "Something's off here" error shell instead of a card.
 *
 * Each `UPDATE` is gated on `paramsJson = '{}' AND kind = ?` so rows that
 * were already written with proper JSON (e.g. by tests or future
 * editor flows) are not clobbered.
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE `rule_templates` SET `paramsJson` = ? WHERE `paramsJson` = '{}' AND `kind` = ?",
            arrayOf<Any>(paramsJsonFor(RuleKind.KEEP_IN_TOUCH), RuleKind.KEEP_IN_TOUCH.name),
        )
        db.execSQL(
            "UPDATE `rule_templates` SET `paramsJson` = ? WHERE `paramsJson` = '{}' AND `kind` = ?",
            arrayOf<Any>(paramsJsonFor(RuleKind.LATE_NIGHT), RuleKind.LATE_NIGHT.name),
        )
        db.execSQL(
            "UPDATE `rule_templates` SET `paramsJson` = ? WHERE `paramsJson` = '{}' AND `kind` = ?",
            arrayOf<Any>(paramsJsonFor(RuleKind.ENERGIZE), RuleKind.ENERGIZE.name),
        )
    }
}

/**
 * Additive index migration. No data change.
 *
 * Two indices land in v=8:
 * - `index_call_events_occurredAt` on `call_events(occurredAt)` — eliminates the
 *   filesort that hit on every Call Log open (`CallEventDao.observeForLog` runs
 *   `ORDER BY occurredAt DESC LIMIT 200`).
 * - `index_contacts_isIgnored_ignoredAt` on `contacts(isIgnored, ignoredAt)` —
 *   eliminates the full-table scan on Settings → Ignored Contacts
 *   (`WHERE isIgnored = 1 ORDER BY ignoredAt DESC`).
 *
 * Index names match Room's auto-generated `index_<table>_<cols>` form so
 * TableInfo validation accepts them after the version bump (v=8 schema JSON
 * declares the same names — see 8.json under app/schemas/).
 *
 * `IF NOT EXISTS` makes the migration idempotent and tolerant of any
 * partial-replay scenario (matches the pattern in [MIGRATION_3_4] and
 * [MIGRATION_5_6]).
 *
 * Strict-migration policy preserved (no `fallbackToDestructiveMigration` in
 * [DatabaseFactory]). SQLCipher encrypts the new index pages alongside data
 * pages — no plaintext leak surface introduced.
 */
val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_call_events_occurredAt` " +
                "ON `call_events` (`occurredAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_contacts_isIgnored_ignoredAt` " +
                "ON `contacts` (`isIgnored`, `ignoredAt`)",
        )
    }
}

/**
 * Additive denormalization migration for ADR 0006 Rule 2.
 *
 * Adds `dueCount: INTEGER NOT NULL DEFAULT 0` to `lists`. The default-0
 * value satisfies SQLite's no-rewrite ALTER constraint (every existing
 * row gets 0 instantly), then a single UPDATE backfills the correct
 * count from `list_memberships`. The `now` predicate is passed as a
 * bound JVM Long so SQLCipher's CURRENT_TIMESTAMP semantics are not
 * load-bearing (mirrors [MIGRATION_3_4]'s bound-param style).
 *
 * The seven mutator use cases keep this column fresh on every membership /
 * nextDueAt write. For time-based "now crossed nextDueAt" transitions, the
 * column is recomputed on app foreground via HomeFeed.refreshDueCountsIfStale
 * (5-min TTL).
 *
 * Strict-migration policy preserved (no `fallbackToDestructiveMigration`
 * in [DatabaseFactory]). Schema export 9.json must be tracked in git on
 * first compile (KSP emits it automatically when the version bumps).
 */
val MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `lists` ADD COLUMN `dueCount` INTEGER NOT NULL DEFAULT 0",
        )
        val nowMs = System.currentTimeMillis()
        db.execSQL(
            "UPDATE `lists` SET `dueCount` = (" +
                "SELECT COUNT(*) FROM `list_memberships` " +
                "WHERE `list_memberships`.`listId` = `lists`.`id` " +
                "AND (`list_memberships`.`nextDueAt` IS NULL " +
                "OR `list_memberships`.`nextDueAt` <= ?))",
            arrayOf<Any>(nowMs),
        )
    }
}

/**
 * Creates the `contact_phones` table (one row per device phone number per
 * contact) and backfills it from every existing contact's primary
 * `phoneNumber` / `normalizedPhone` with `isPrimary = 1`.
 *
 * Table/index SQL mirrors Room's KSP-generated shape exactly (column order,
 * FK clause, `index_<table>_<cols>` names) so TableInfo validation accepts it
 * after the version bump to v=10.
 *
 * Backfill notes:
 * - `INSERT OR IGNORE` + the unique `normalizedPhone` index makes the
 *   backfill idempotent and first-wins on any pre-existing duplicate (cannot
 *   occur in practice — `contacts.normalizedPhone` is itself unique since
 *   MIGRATION_3_4 — but partial-replay tolerance matches the house pattern).
 * - Rows with an empty `normalizedPhone` are skipped: an empty match key can
 *   never match a call-log row and would collide with every other empty key
 *   under the unique index.
 * - Secondary numbers cannot be recovered in-migration (the device address
 *   book is not readable here); the next [app.orbit.domain.usecase.IngestPhoneContactsUseCase]
 *   pass populates them.
 *
 * Strict-migration policy preserved (no `fallbackToDestructiveMigration` in
 * [DatabaseFactory]). Schema export 10.json must be tracked in git on first
 * compile (KSP emits it automatically when the version bumps).
 */
val MIGRATION_9_10: Migration = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `contact_phones` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`contactId` INTEGER NOT NULL, " +
                "`phoneNumber` TEXT NOT NULL, " +
                "`normalizedPhone` TEXT NOT NULL, " +
                "`isPrimary` INTEGER NOT NULL, " +
                "FOREIGN KEY(`contactId`) REFERENCES `contacts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_contact_phones_normalizedPhone` " +
                "ON `contact_phones` (`normalizedPhone`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_contact_phones_contactId` " +
                "ON `contact_phones` (`contactId`)",
        )
        db.execSQL(
            "INSERT OR IGNORE INTO `contact_phones` " +
                "(`contactId`, `phoneNumber`, `normalizedPhone`, `isPrimary`) " +
                "SELECT `id`, `phoneNumber`, `normalizedPhone`, 1 FROM `contacts` " +
                "WHERE `normalizedPhone` != ''",
        )
    }
}

/**
 * Additive `contacts.isStarred` column mirroring Android's hand-curated
 * favorite flag (ContactsContract STARRED). `INTEGER NOT NULL
 * DEFAULT 0` satisfies SQLite's no-rewrite ALTER constraint: every existing
 * row reads "not starred" until the next [app.orbit.domain.usecase.IngestPhoneContactsUseCase]
 * delta-sync pass refreshes the flag from the device (the same path that
 * keeps displayName / photoUri fresh) — no in-migration backfill is possible
 * because the device address book is not readable here (same constraint as
 * [MIGRATION_9_10]'s secondary numbers).
 *
 * Strict-migration policy preserved (no `fallbackToDestructiveMigration` in
 * [DatabaseFactory]). Schema export 11.json must be tracked in git on first
 * compile (KSP emits it automatically when the version bumps).
 */
val MIGRATION_10_11: Migration = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `contacts` ADD COLUMN `isStarred` INTEGER NOT NULL DEFAULT 0",
        )
    }
}

/**
 * Additive `lists.nudgeScheduleJson` column with default-ON backfill
 * (D-03/NOTIF-10/11).
 *
 * Step 1: ALTER TABLE — TEXT column with DEFAULT NULL satisfies SQLite's
 * no-rewrite ALTER constraint (TEXT columns cannot carry a non-constant DEFAULT).
 *
 * Step 2: UPDATE — backfills every existing list row with the default nudge
 * schedule. The literal is sourced from [NudgeSchedule.DEFAULT_JSON] (an imported
 * symbol, NOT a re-typed string) so the byte-exact contract between the migration
 * SQL and the serializer output is compiler-enforced. Equality is also asserted
 * by [app.orbit.notify.NudgeScheduleTest.default_serializedJsonMatchesConstant].
 *
 * Security (T-10-03): the DEFAULT_JSON value is bound via parameterized
 * `execSQL(?, arrayOf(...))` — no string interpolation.
 *
 * Strict-migration policy preserved (no `fallbackToDestructiveMigration` in
 * [DatabaseFactory]). Schema export 12.json must be tracked in git on first
 * compile (KSP emits it automatically when the version bumps).
 */
val MIGRATION_11_12: Migration = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Step 1: add column — TEXT cannot carry a non-constant DEFAULT in SQLite.
        db.execSQL("ALTER TABLE `lists` ADD COLUMN `nudgeScheduleJson` TEXT DEFAULT NULL")
        // Step 2: backfill ALL rows with the default schedule per D-03.
        // NudgeSchedule.DEFAULT_JSON is the imported constant — NOT a re-typed literal.
        db.execSQL(
            "UPDATE `lists` SET `nudgeScheduleJson` = ?",
            arrayOf<Any>(NudgeSchedule.DEFAULT_JSON),
        )
    }
}
