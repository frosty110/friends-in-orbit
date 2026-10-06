package app.orbit

import android.app.UiModeManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import app.orbit.calllog.ContentObserverController
import app.orbit.data.AppPrefs
import app.orbit.data.feed.HomeFeed
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.ResetOutcome
import app.orbit.domain.usecase.WidgetSurfaceUseCase
import app.orbit.nav.AppLinks
import app.orbit.nav.OrbitNavHost
import app.orbit.nav.Routes
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.screens.picker.PickerCommitBus
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.theme.OrbitDarkMode
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.OrbitThemes
import app.orbit.ui.theme.ThemeSettings
import app.orbit.ui.util.TimeStyle
import app.orbit.ui.util.UiText
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

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
     * LAUNCH-01: who "Call next" opens: the same cross-list head the widgets
     * show first, read once at the moment of the tap.
     */
    @Inject lateinit var nextPeople: WidgetSurfaceUseCase

    /**
     * SET-06: where a failed reset is told to the user. The app-level host
     * mounted in [OrbitNavHost] collects this bus and renders on whatever
     * screen is up, which is the point: the reset runs on the application
     * scope and its failure may land after Settings has been popped.
     */
    @Inject lateinit var commitBus: PickerCommitBus

    /**
     * D-17: the NAVIGATE_TO route string from a notification, widget or
     * launcher-shortcut tap. Seeded from the launch Intent in [onCreate] and
     * updated on warm re-entry via [onNewIntent]. Consumed once by
     * OrbitNavHost's LaunchedEffect, then cleared to null so a config
     * change (rotation, theme switch) does not re-navigate.
     *
     * It starts null and is filled in [onCreate], not from `intent` here: a
     * property initialiser runs in the constructor, before Android attaches
     * the launch Intent, so `intent` was always null at this point and a tap
     * that cold-started the app (the usual case for a nudge) opened Home
     * instead of the list.
     *
     * De-duplication approach: [OrbitNavHost] receives the current value;
     * its `LaunchedEffect(navigateTo)` calls [nav.navigate] when non-null,
     * then the Activity clears [navigateTo] back to null by calling
     * [onNavigateToConsumed]. Clearing happens inside the LaunchedEffect
     * body so the NavController is in scope and the navigation has already
     * been dispatched before the value is erased.
     */
    private var navigateTo: String? by mutableStateOf<String?>(null)

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
        routeFrom(intent)
    }

    /**
     * Turns a launch Intent into a route for [navigateTo]. A notification or a
     * widget names its route in [AppLinks.EXTRA_NAVIGATE_TO]; a launcher
     * shortcut names an action, resolved here (LAUNCH-01). The decision itself
     * is [AppLinks.landingFor], a pure function with its own tests; this
     * method only pays for the reads a landing needs.
     *
     * Shortcuts exist from install, so their routes wait for onboarding to be
     * done. The onboarding flag is a suspending DataStore read, so the landing
     * is decided twice: once assuming onboarding is done, which settles a
     * route or an unrelated launch at once (a cold nudge tap never waits on
     * DataStore), and, for a shortcut only, again with the real flag.
     */
    private fun routeFrom(intent: Intent?) {
        when (val landing = AppLinks.landingFor(intent, onboardingComplete = true)) {
            is AppLinks.Landing.Open -> navigateTo = landing.route
            AppLinks.Landing.Nothing -> Unit
            AppLinks.Landing.CallNext, AppLinks.Landing.Search -> lifecycleScope.launch {
                navigateTo = when (AppLinks.landingFor(intent, appPrefs.isOnboardingComplete.first())) {
                    AppLinks.Landing.CallNext -> AppLinks.callNextRoute(nextPeople())
                    AppLinks.Landing.Search -> Routes.GlobalSearch
                    // Before onboarding the app simply opens.
                    else -> return@launch
                }
            }
        }
    }

    /**
     * SET-06: once ResetService has finished (works cancelled, observers
     * stopped, Room + DataStore wiped) the user must land somewhere honest.
     * Restarting the task is the simplest reliable mechanism: the relaunched
     * MainActivity re-resolves its start destination from the now-cleared
     * onboarding flag and lands on the welcome screen. An in-place
     * nav.navigate would leave stale back-stack entries and ViewModels
     * holding pre-reset state; Activity.recreate() keeps the nav back stack.
     * The outcome is cleared before this runs: the process survives the
     * restart, and the new activity's collector must not restart it again.
     */
    private fun restartTaskIntoOnboarding() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(launchIntent)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The Splash API keeps the themed launcher screen visible until the
        // boot ViewModel resolves a start destination. No runBlocking, no
        // flash of the wrong route.
        val splash = installSplashScreen()
        // Hold the splash until BOTH the start route and the theme are resolved,
        // so the first painted frame is already in the user's chosen theme.
        splash.setKeepOnScreenCondition {
            appViewModel.startDestination.value == null || appViewModel.themeSettings.value == null
        }

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // D-17: a fresh launch carries its route; a recreation (rotation,
        // process restore) does not re-open it, because the first one was
        // already consumed.
        if (savedInstanceState == null) routeFrom(intent)

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
                        // The phone's 12/24-hour setting may have changed while
                        // the app was in the background.
                        TimeStyle.refresh(this@MainActivity)
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

        // SET-06: act on the reset's outcome here, not in Settings. The reset
        // runs on the application scope (rules.md Code 6) and the outcome is
        // sticky state on ResetService, so it is still there if the user
        // backed out of Settings or backgrounded the app during the wipe; a
        // SharedFlow collected by the Settings screen dropped both the
        // completion and the failure in that window, leaving a live app over
        // an empty database with the onboarding flag cleared.
        //
        // RESUMED, and the failure published through a dispatch: the snackbar
        // host in OrbitNavHost restarts its own collector on ON_START, but that
        // launch goes through the composition's dispatcher and has not run when
        // ON_START is delivered here, so a failure already waiting when the app
        // comes back would be published into a bus with no subscriber and
        // dropped (PickerCommitBus keeps no replay). Publishing from ON_RESUME,
        // and after a dispatch of our own, lands behind the host's launch. The
        // outcome is cleared only after the message is on the bus, so a resume
        // cut short still re-publishes it next time; nothing fires while the
        // activity is stopped, so a restart waits for the user to return.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                appViewModel.resetOutcome.collect { outcome ->
                    when (outcome) {
                        null -> Unit
                        ResetOutcome.Completed -> {
                            appViewModel.onResetOutcomeHandled()
                            restartTaskIntoOnboarding()
                        }
                        ResetOutcome.Failed -> {
                            withContext(Dispatchers.Main) {
                                commitBus.publish(
                                    SnackbarEvent(UiText.res(R.string.settings_reset_failed)),
                                )
                            }
                            appViewModel.onResetOutcomeHandled()
                        }
                    }
                }
            }
        }

        setContent {
            val start by appViewModel.startDestination.collectAsStateWithLifecycle()
            val themeSettings by appViewModel.themeSettings.collectAsStateWithLifecycle()
            val curtain by appViewModel.privacyCurtainActive.collectAsStateWithLifecycle()

            val settings = themeSettings ?: ThemeSettings.DEFAULT
            val dark = OrbitThemes.effectiveDark(settings, isSystemInDarkTheme())
            // System bar icons follow the in-app Light / Dark choice, not just
            // the system's: with "Dark" chosen on a light-mode phone the icons
            // were dark on charcoal. Transparent bars, edge to edge.
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            // Tell the system too, so the splash screen and system dialogs on
            // the next launch match the choice (API 31+, the app's minimum).
            LaunchedEffect(settings.darkMode) {
                getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
                    when (settings.darkMode) {
                        OrbitDarkMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                        OrbitDarkMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                        OrbitDarkMode.DARK -> UiModeManager.MODE_NIGHT_YES
                    },
                )
            }

            CompositionLocalProvider(LocalPrivacyCurtain provides curtain) {
                OrbitTheme(settings = settings, darkTheme = dark) {
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
