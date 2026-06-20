package app.orbit.domain

/**
 * Domain-layer seam for triggering an immediate, incremental call-log resync
 * from the call loop (CORE-04).
 *
 * The card view fires this on return from the dialer so a just-completed call
 * advances the deck within ~1-2s, instead of waiting on the 10s-debounced
 * content observer or the TTL-gated resume sync (the latter is suppressed in
 * exactly this flow — foreground -> dial -> return all happen inside its 60s
 * window).
 *
 * Declared as a `fun interface` so plain-JVM ViewModel test fixtures can supply
 * a no-op / counting SAM lambda without any WorkManager or Android context
 * dependency (mirrors [WidgetRefreshTrigger]).
 *
 * Production binding: [app.orbit.calllog.ContentObserverController] — its
 * `enqueueImmediateSync` enqueues the expedited, non-debounced CallLogSyncWorker.
 * Registered in [app.orbit.di.CallLogModule].
 *
 * This interface MUST NOT import any `androidx.work` / `android.content.Context`
 * types — keeping the seam Android-free is the point (test fixtures stay plain
 * JVM, no Robolectric needed).
 */
fun interface CallLogResyncTrigger {
    fun enqueueImmediateSync(fullResync: Boolean)
}
