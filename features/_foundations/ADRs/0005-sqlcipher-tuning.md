# ADR 0005 — SQLCipher production tuning (PRAGMAs, loadLibs call site, passphrase hygiene)

**Status:** accepted
**Date:** 2026-04-23
**Amended:** 2026-04-23 (package-name correction after implementation; see §Addendum)
**Deciders:** the maintainer
**Supersedes:** none
**Refines:** ADR 0002 (SQLCipher-for-Room — decided *that* we encrypt; this ADR decides *how* it runs in production)

## Context

ADR 0002 accepted SQLCipher as the encryption layer for Room. That decision named the library and the passphrase-via-Keystore flow but left the production wiring implicit:

- When is the SQLCipher native library loaded? (The native library must be loaded once before the first DB open, on every process start. See Addendum for the correct load call on `net.zetetic:sqlcipher-android:4.14.1`.)
- What PRAGMAs are set, and with what values? (Defaults work, but "works" and "is the right choice for encrypted user data on mobile" are not the same.)
- How is the passphrase `ByteArray` handled on handoff from `DatabaseKeyProvider` to `SupportFactory`? Left in memory, it's a soft secrets leak — minor, but detectable by any static-analysis scanner and trivially cheap to fix.
- How many `OrbitDatabase` instances are allowed per process? Room's own singleton guard depends on callers passing the same `Context.applicationContext`. If callers pass an Activity context, Room happily builds a second instance bound to a different lifecycle. Silent corruption is the worst-case outcome.

These are the kind of decisions that are invisible when written correctly and catastrophic when written wrong. Pin them explicitly so the next person touching the data layer cannot accidentally drift.

## Options considered

### loadLibs() call site
1. **`Application.onCreate`** — canonical pattern in SQLCipher's own docs. Loads native libs on every cold start whether the DB is touched or not. ~15–30ms cost on cold launch.
2. **`DatabaseFactory.create()`, guarded by `AtomicBoolean`** — lazy. Only paid when the DB is actually opened. First-call cost is identical; subsequent calls are a volatile read.
3. **Static initializer on `OrbitDatabase`** — clever but fragile; runs on classload which can happen in surprising orders with Hilt code generation.

### Passphrase ByteArray lifecycle
1. **Pass and forget** — `SupportFactory(passphrase, null, true)`. The `true` flag tells `SupportFactory` to clear the array after use. Trust the library.
2. **Pass, then explicitly zero in `finally`** — defensive. Runs regardless of `SupportFactory`'s internal behavior (which has changed between SQLCipher versions in the past).
3. **Pass as `CharArray`** — not supported by `SupportFactory`'s byte-array constructor. Non-option.

### PRAGMAs
1. **SQLCipher defaults only** — journal_mode=DELETE, synchronous=FULL, cipher_page_size=4096, kdf_iter=256000 (SQLCipher 4). Works correctly, but `journal_mode=DELETE` with encrypted page rewrites is measurably slower than WAL on writes and conflicts with Room's assumption that concurrent reads don't block writes.
2. **WAL + synchronous=NORMAL + explicit cipher defaults** — industry-standard mobile SQLite tuning, additionally safe under SQLCipher because WAL journals are also encrypted. `synchronous=NORMAL` trades a tiny durability window (last-commit loss on power cut) for a large write-throughput gain. For a local-only app with user-exportable data, this is the correct tradeoff.
3. **`synchronous=OFF`** — too aggressive. Rejected.

### Singleton enforcement
1. **Trust Room's internal singleton** — works *if* callers pass `applicationContext`. Silently breaks if they don't.
2. **Require `Application` context in `DatabaseFactory.create()`** — factory unwraps `context.applicationContext` and asserts the result `is Application`. Impossible to misuse.

## Decision

### loadLibs() call site — Option 2 (factory-lazy)
`DatabaseFactory.create()` calls `SQLiteDatabase.loadLibs(context)` once per process, guarded by a module-level `AtomicBoolean`. Not `Application.onCreate` — the app can launch many times without touching the DB (e.g., widget refresh that hits only SharedPreferences), and paying the native-load cost on every cold launch is wasteful. The lazy path pays identical cost on first DB access and zero on subsequent accesses.

### Passphrase lifecycle — Option 2 (defensive zeroing)
```kotlin
val passphrase: ByteArray = keyProvider.getPassphrase()
try {
    val factory = SupportFactory(passphrase, null, true)
    Room.databaseBuilder(...).openHelperFactory(factory).build()
} finally {
    passphrase.fill(0)
}
```
The `true` flag in `SupportFactory` already instructs it to zero the array after use; the explicit `fill(0)` in `finally` is belt-and-braces in case of exception before `SupportFactory` reads the array. Zero cost, one line, removes a class of audit findings.

### PRAGMAs — Option 2 (explicit WAL + tuned cipher)
Set via a `SupportOpenHelper.Callback.onConfigure` override (or the `SupportFactory` hook — whichever Room version we're on):

| PRAGMA | Value | Rationale |
|---|---|---|
| `journal_mode` | `WAL` | Concurrent reads don't block writes; journals are encrypted under SQLCipher 4. |
| `synchronous` | `NORMAL` | ~2–5× write throughput vs. `FULL`; durability window is last-commit-on-power-cut, acceptable for local-only relational data with an export path. |
| `cipher_page_size` | `4096` | SQLCipher 4 default, pinned explicitly so a library upgrade changing the default can never silently re-encrypt. |
| `kdf_iter` | `256000` | SQLCipher 4 default, pinned explicitly for the same reason. |
| `cipher_memory_security` | `OFF` | SQLCipher 4 default. `ON` zeros encryption buffers on free; measurable perf cost with zero realistic threat model benefit on Android (memory is per-process-isolated). |

All five are set explicitly — even the ones that match the library default — because a silently-changed default is the same class of bug as a silently-added fallback migration.

### Singleton enforcement — Option 2 (Application-typed)
```kotlin
fun create(context: Context): OrbitDatabase {
    val app = context.applicationContext
    check(app is Application) { "DatabaseFactory requires an Application context; got ${app::class.java.name}" }
    // ...
}
```

## Consequences

- `DatabaseFactory.kt` owns every piece of this ADR. Nothing in it leaks up to `OrbitApp.onCreate`, `AppContainer`, or DAOs.
- The `SupportOpenHelper.Callback` override is the only place PRAGMAs are set. A grep for `PRAGMA` outside `DatabaseFactory.kt` is a review smell.
- First-DB-open has a one-time cost of: native lib load (~15–30ms) + KDF (~250ms at 256000 iterations on a mid-range device). This is expected and correct; the cost only happens on cold process start with cold DB. Subsequent opens are free.
- `kdf_iter=256000` will become `kdf_iter=N` if we ever move to a user-chosen passphrase (currently out of scope per ADR 0002). That would be a new ADR.
- `DatabaseFactory.kt` must contain `loadLibs`, `PRAGMA journal_mode`, `PRAGMA synchronous`, and `fillZero`/`fill(0)`. Missing any of them is a regression.

## Non-consequences

- Does not change the passphrase generation flow in ADR 0002 — that's 1.3's responsibility.
- Does not specify Room migration mechanics — strict-migration policy lives in the data-layer roadmap entry, not here.
- Does not introduce WAL-checkpoint tuning (`wal_autocheckpoint`) — defaults are correct for our write volume.
- Does not make any claims about threat model beyond what ADR 0002 already states (at-rest encryption, Keystore-bound key).

## Related

- ADR 0002 — SQLCipher-for-Room (this ADR refines the production wiring)
- ADR 0003 — Biometric (shares the Keystore key lifecycle)
- The data-layer success criteria (which enforce this ADR)
- SQLCipher upstream docs — [SQLCipher Android: Usage](https://www.zetetic.net/sqlcipher/sqlcipher-for-android/)

---

## Addendum — 2026-04-23 (package surface correction)

The ADR body above was written before `DatabaseFactory.kt` was implemented. Every decision it pins still holds. The symbol names used in the code examples were wrong — they described the **legacy** `net.sqlcipher:android-database-sqlcipher` artifact rather than the catalog-pinned `net.zetetic:sqlcipher-android:4.14.1`. Zetetic renamed the package when they split SQLCipher for Android into the modernized `net.zetetic.database.sqlcipher.*` namespace (the old `net.sqlcipher.database.*` coordinates are abandoned). This addendum corrects the surface names; every semantic in §Decision carries forward unchanged.

**Real API surface on `net.zetetic:sqlcipher-android:4.14.1` (+ `androidx.sqlite:sqlite:2.6.2`):**

| ADR §Decision concept | Legacy API (what was quoted) | Actual API (what shipped) |
|---|---|---|
| Native library load | `net.sqlcipher.database.SQLiteDatabase.loadLibs(context)` | `System.loadLibrary("sqlcipher")` |
| OpenHelper factory | `net.sqlcipher.database.SupportFactory(passphrase, null, true)` | `net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase, hook, clearPassphrase = true)` |
| PRAGMA hook | `SQLiteDatabaseHook { preKey / postKey }` with `SQLiteDatabase` args | `SQLiteDatabaseHook { preKey / postKey }` with `net.zetetic.database.sqlcipher.SQLiteConnection` args |
| Raw PRAGMA execution | `db.rawExecSQL("PRAGMA ...")` on `SQLiteDatabase` | `connection.execute("PRAGMA ...", emptyArray(), null)` on `SQLiteConnection` |

The `DatabaseFactory.kt` that landed in commit `8450ff2` uses the real API surface. The five PRAGMAs, the `AtomicBoolean`-guarded load, the `finally { passphrase.fill(0) }`, the `check(app is Application)`, and the strict-migration contract are all preserved verbatim. The `SupportOpenHelperFactory` constructor's third arg (`clearPassphrase = true`) is the equivalent of the legacy `SupportFactory`'s third-arg behavior — the library itself zeroes the array after use; our explicit `fill(0)` in `finally` is the belt-and-braces on top.

**Why this matters:** the next plan that touches SQLCipher should grep for `net.zetetic.database.sqlcipher` to find the real import surface. The `net.sqlcipher.database.*` package does not exist in our dependency tree and any code referencing it won't compile.
