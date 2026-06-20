package app.orbit

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import app.orbit.calllog.ContentObserverController
import app.orbit.data.AppPrefs
import app.orbit.data.feed.HomeFeed
import app.orbit.data.repository.ListRepository
import app.orbit.nav.OrbitNavHost
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.theme.OrbitTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels<AppViewModel>()

    /**
     * Process-scoped feed singleton injected here so the
     * [Lifecycle.Event.ON_START] observer below can dispatch
     * `refreshDueCountsIfStale()` (5-minute TTL gate). Hilt resolves the
     * same singleton instance OrbitApp primed at cold-start, so this read
     * is free.
     */
    @Inject lateinit var homeFeed: HomeFeed

    /**
     * Call-detection backbone. Injected here so the [Lifecycle.Event.ON_START]
     * observer can trigger an incremental call-log re-sync on every foreground
     * ([ContentObserverController.enqueueResumeSyncIfStale]). This closes the
     * process-death gap: the content observer only fires while a live process
     * holds the registration, so a call that completes while Orbit's process is
     * dead (common during a long call with the app backgrounded) is otherwise
     * never picked up until the next call. Hilt resolves the same app-scoped
     * singleton OrbitApp registered at cold start.
     */
    @Inject lateinit var contentObserverController: ContentObserverController

    /**
     * ONB-19 / ONB-09 — threaded into [OrbitNavHost] so the onboarding nav
     * graph can call [ListRepository.create] + [ListRepository.addMember]
     * inline at navigate-time when the user taps "Make this my first list"
     * / "Start blank" / "Add another list". Hilt resolves the same singleton
     * instance the onboarding screens consume via `@HiltViewModel`.
     */
    @Inject lateinit var listRepo: ListRepository

    /**
     * Threaded into [OrbitNavHost] so each counted onboarding composable
     * persists its [OnboardingStep] on entry. Cold-start resume reads the
     * persisted step in [AppViewModel.resolveOnboardingResume].
     */
    @Inject lateinit var appPrefs: AppPrefs

    /**
     * D-17 — the NAVIGATE_TO route string from a notification
     * PendingIntent tap. Seeded from the launch Intent on cold start and
     * updated on warm re-entry via [onNewIntent]. Consumed once by
     * OrbitNavHost's LaunchedEffect, then cleared to null so a config
     * change (rotation, theme switch) does not re-navigate.
     *
     * De-duplication approach: [OrbitNavHost] receives the current value;
     * its `LaunchedEffect(navigateTo)` calls [nav.navigate] when non-null,
     * then the Activity clears [navigateTo] back to null by calling
     * [onNavigateToConsumed]. Clearing happens inside the LaunchedEffect
     * body so the NavController is in scope and the navigation has already
     * been dispatched before the value is erased.
     */
    private var navigateTo: String? by mutableStateOf(
        intent?.getStringExtra("app.orbit.extra.NAVIGATE_TO"),
    )

    /**
     * Called by [OrbitNavHost] after it has consumed the [navigateTo] value.
     * Clears the field so the same destination is not re-navigated on
     * recomposition or config change.
     */
    fun onNavigateToConsumed() {
        navigateTo = null
    }

    /**
     * D-17 — warm tap handling. When a notification arrives while
     * the app is foregrounded [FLAG_ACTIVITY_SINGLE_TOP] delivers the tap here
     * rather than via a fresh [onCreate]. Update [intent] (required for
     * [getIntent] callers) and extract the NAVIGATE_TO extra into [navigateTo]
     * so the LaunchedEffect in [OrbitNavHost] re-fires.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigateTo = intent.getStringExtra("app.orbit.extra.NAVIGATE_TO")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The Splash API keeps the themed launcher screen visible until the
        // boot ViewModel resolves a start destination. No runBlocking, no
        // flash of the wrong route.
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { appViewModel.startDestination.value == null }

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // PRIV-04: redact release screenshots/screen-recordings; debug variant unchanged for the screenshot-review workflow.
        if (!BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }

        // PRIV-03: Activity-scoped lifecycle observer (NOT migrated to LifecycleStartEffect).
        // LifecycleStartEffect is composable-scoped — its observer's lifetime would be
        // tied to composition, not the Activity. Keeping the observer here means
        // backgrounding while a Composable is recomposing (config change, theme switch)
        // still flips the curtain. Consumer composables read the combined signal via
        // LocalPrivacyCurtain.current. A per-screen LifecycleStartEffect may be
        // introduced later for per-screen lifecycle reads — none today.
        //
        // Quick-hide (PRD §Privacy): drive the privacy curtain on/off as focus
        // changes so the app-switcher snapshot and lock-screen preview don't
        // leak list names. Always-on — no user toggle (the user-facing minimal
        // mode setting was removed 2026-04-28).
        // This extends the privacy-curtain observer rather than adding a second
        // one. ON_START fires on every foreground (including rotations and theme
        // switches). The 5-minute TTL gate inside refreshDueCountsIfStale
        // prevents recompute spam (T-16-15 mitigation in the threat register).
        // `runCatching` swallows any DataStore /
        // Room hiccup so a refresh failure cannot crash MainActivity, mirroring
        // the AppViewModel pattern of guarding DataStore reads.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP  -> appViewModel.onForegroundChanged(false)
                    Lifecycle.Event.ON_START -> {
                        appViewModel.onForegroundChanged(true)
                        // Re-sync the call log on every foreground (TTL-gated
                        // inside the controller so rotation/theme churn is a
                        // no-op). Catches calls that completed while the process
                        // was dead and the content observer was unregistered.
                        runCatching { contentObserverController.enqueueResumeSyncIfStale() }
                        lifecycleScope.launch {
                            runCatching { homeFeed.refreshDueCountsIfStale() }
                        }
                    }
                    else -> Unit
                }
            }
        )

        setContent {
            val start by appViewModel.startDestination.collectAsStateWithLifecycle()
            val curtain by appViewModel.privacyCurtainActive.collectAsStateWithLifecycle()

            CompositionLocalProvider(LocalPrivacyCurtain provides curtain) {
                OrbitTheme {
                    val resolvedStart = start ?: return@OrbitTheme   // splash still up
                    val nav = rememberNavController()
                    OrbitNavHost(
                        nav = nav,
                        listRepo = listRepo,
                        appPrefs = appPrefs,
                        startDestination = resolvedStart,
                        navigateTo = navigateTo,
                        onNavigateToConsumed = ::onNavigateToConsumed,
                    )
                }
            }
        }
    }
}
