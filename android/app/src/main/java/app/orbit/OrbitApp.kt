package app.orbit

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import app.orbit.calllog.ContentObserverController
import app.orbit.data.AppPrefs
import app.orbit.data.feed.HomeFeed
import app.orbit.data.keystore.DatabaseKeyProvider
import app.orbit.logging.OrbitDebugTree
import app.orbit.notify.NudgeScheduler
import app.orbit.notify.OrbitNotifications
import app.orbit.widget.WidgetUpdateScheduler
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Application entry point.
 *
 * Implements [Configuration.Provider] so WorkManager uses Hilt's
 * [HiltWorkerFactory] to instantiate @HiltWorker classes — required for
 * [app.orbit.calllog.CallLogSyncWorker], [app.orbit.notify.ListPromptWorker],
 * and [app.orbit.notify.IncomingFollowUpWorker]. The AndroidManifest
 * `tools:node="remove"` block removes the default initializer so this
 * Configuration takes effect.
 *
 * ContentObserver lifecycle: [ContentObserverController.start] is idempotent
 * and called on every cold start. Safe without READ_CALL_LOG granted — the
 * observer is passive; only the worker touches the content provider and
 * checks permission there. Settings also calls start() on permission grant
 * (defense-in-depth so the observer never gets "stuck revoked").
 */
@HiltAndroidApp
class OrbitApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var hiltWorkerFactory: HiltWorkerFactory
    @Inject lateinit var appPrefs: AppPrefs
    @Inject lateinit var contentObserverController: ContentObserverController
    @Inject lateinit var keyProvider: DatabaseKeyProvider
    @Inject lateinit var homeFeed: HomeFeed
    @Inject lateinit var nudgeScheduler: NudgeScheduler

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(hiltWorkerFactory)
            .setMinimumLoggingLevel(
                if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.ERROR,
            )
            .build()

    /**
     * Serve a single application-scoped Coil [ImageLoader] (decoder registry +
     * OkHttp client + dispatchers) instead of letting each PhIcon call site
     * allocate its own. Coil resolves this through `Coil.imageLoader(context)`
     * whenever an [AsyncImage] composable does not pass an explicit
     * `imageLoader` parameter.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(SvgDecoder.Factory()) }
            .build()

    override fun onCreate() {
        super.onCreate()

        // Plant the PII-scrubbing tree BEFORE any code path that could log
        // call-log data. Release builds stay silent — no Tree planted,
        // Timber.d is a no-op. CALL-07 mandatory gate.
        if (BuildConfig.DEBUG) {
            Timber.plant(OrbitDebugTree())
        }

        // Notification channels must be registered before the first notify()
        // call; safe every launch (creating an existing channel is a no-op).
        OrbitNotifications.ensureChannels(applicationContext)

        // Start the CallLog observer. Safe without permission — only the
        // worker touches the content provider and checks READ_CALL_LOG.
        contentObserverController.start()

        // Cancel the legacy periodic work by literal name so any installed-device
        // WorkManager record is cleaned up. The string "orbit.daily_digest"
        // is byte-identical to the old worker's UNIQUE_NAME so the stale record is
        // actually cancelled on existing installs (NOTIF-08). Two workers remain:
        // ListPromptWorker and IncomingFollowUpWorker.
        WorkManager.getInstance(applicationContext).cancelUniqueWork("orbit.daily_digest")

        // Re-anchor all per-list nudge chains on every cold start so
        // WorkManager persistence aligns with the current schedule (accounts for
        // time-zone changes, schedule edits while backgrounded, etc.). REPLACE policy
        // inside reAnchorAll makes this idempotent. Must run on a coroutine — it reads
        // the DB and is declared suspend.
        appScope.launch { nudgeScheduler.reAnchorAll() }

        // WIDGET-06: register the 1h periodic widget sweep on every cold
        // start. ExistingPeriodicWorkPolicy.KEEP makes re-registration a no-op so
        // WorkManager does not reschedule an already-enqueued periodic chain. This
        // catches active-hours boundary transitions (~60min staleness upper bound)
        // without requiring any user interaction.
        WidgetUpdateScheduler.schedulePeriodic(applicationContext)

        // Pre-warm the Keystore-wrapped passphrase off the
        // Main thread so the synchronous `runBlocking { ... }` gate inside
        // `DatabaseFactory.create` returns immediately on first DB access
        // (the cached value is handed back from `mutex.withLock` rather than
        // re-decrypting). `runCatching` swallows pre-warm failures: the
        // synchronous gate retains the existing failure surface (and the
        // sealed `KeystoreUnavailableException` recovery dispatch).
        appScope.launch(Dispatchers.IO) {
            runCatching { keyProvider.getOrCreatePassphrase() }
        }

        // ADR 0006 Rule 3 — pay SQLCipher first-open behind
        // the launch image. HomeFeed.prime() forces
        // ListRepository.observeActive().first(), which opens the encrypted
        // DB on appScope (Dispatchers.Default) before MainActivity composes.
        // The launch image absorbs ~250ms KDF + ~30ms native lib load (ADR
        // 0005) so the user's first MainActivity composition reads from a
        // warm DB and Home renders instantly from the cached `tiles`
        // StateFlow. Idempotent — safe on every cold start including
        // post-process-death. Independent of the keystore pre-warm above
        // (DatabaseFactory's mutex coordinates the passphrase handoff).
        appScope.launch {
            homeFeed.prime()
        }
    }
}
