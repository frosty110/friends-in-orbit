package app.orbit.logging

import timber.log.Timber

/**
 * Debug-only Timber Tree that scrubs PII at the Tree layer (04-RESEARCH §Pattern 2).
 *
 * Every log line emitted via `Timber.d/i/w/e/v/wtf` flows through [log]; we
 * intercept, scrub via [PiiSanitizer], and delegate to `super.log()` which
 * writes to Logcat.
 *
 * Defense-in-depth: this Tree is the ONLY mechanism that guarantees CALL-07.
 * Call-site discipline alone is not sufficient — see 04-RESEARCH §Pitfall 2.
 *
 * Lifecycle: planted in [app.orbit.OrbitApp.onCreate] guarded by
 * [app.orbit.BuildConfig.DEBUG]. Release builds do NOT plant this tree; since
 * no Tree is registered, `Timber.d(...)` is a no-op per Timber 5.0.1 source
 * (`Timber.TREE_OF_SOULS.log()` iterates a zero-element array).
 */
internal class OrbitDebugTree : Timber.DebugTree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        super.log(priority, tag, PiiSanitizer.scrub(message), t)
    }
}
