package app.orbit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.data.AppPrefs
import app.orbit.data.dao.WaitingCallRow
import app.orbit.data.repository.ResetOutcome
import app.orbit.data.repository.ResetService
import app.orbit.data.repository.WaitingCalls
import app.orbit.domain.clock.Clock
import app.orbit.nav.Routes
import app.orbit.ui.components.NoteWaiting
import app.orbit.ui.screens.onboarding.OnboardingStep
import app.orbit.ui.theme.OrbitDarkMode
import app.orbit.ui.theme.OrbitThemeId
import app.orbit.ui.theme.ThemeSettings
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatDuration
import app.orbit.ui.util.formatRelativeFine
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Boot-time + global UI state. Kept small on purpose — per-screen state lives
 * in per-screen Hilt view-models. This VM owns:
 *   - start destination (onboarding vs home) — resolved once from [AppPrefs].
 *   - privacy curtain (auto-only — flips on focus loss, restores on focus regain).
 *   - the reset's outcome ([resetOutcome], SET-06), read from the app-scoped
 *     [ResetService] so MainActivity can act on it wherever the user is.
 *   - the calls waiting for a note on Home ([notesWaiting], HOME-14 over
 *     NOTE-05's [WaitingCalls]) and their dismissals.
 *
 * Hilt constructs this VM via `@AndroidEntryPoint` on MainActivity +
 * `by viewModels<AppViewModel>()`; there is no manual factory companion. The
 * VM does not own a repository for permission checks — per-screen VMs check
 * their own permissions, and call-log ingestion lives in `CallLogSyncWorker`.
 * The derived StateFlows use `WhileSubscribed(5_000L)` per ARCH-02.
 *
 * 2026-04-28: removed biometric-lock flag and the user-toggled minimal-mode
 * half of the privacy curtain combine. Quick-hide on focus loss survives —
 * it's the only privacy substrate now.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val appPrefs: AppPrefs,
    private val waitingCalls: WaitingCalls,
    private val clock: Clock,
    private val resetService: ResetService,
) : ViewModel() {

    private val _startDestination = MutableStateFlow<String?>(null)
    val startDestination: StateFlow<String?> = _startDestination.asStateFlow()

    /**
     * THEMING 2026-06-22 — the user's chosen appearance, mapped from the raw
     * AppPrefs primitives into a [ThemeSettings]. Null until the first DataStore
     * emission; MainActivity holds the splash until it loads so the first frame
     * is already in the chosen theme (no flash). Continuously re-emits on every
     * change, so picking a theme/accent in Settings retints the whole app live.
     */
    private val _themeSettings = MutableStateFlow<ThemeSettings?>(null)
    val themeSettings: StateFlow<ThemeSettings?> = _themeSettings.asStateFlow()

    private val _isForeground = MutableStateFlow(true)

    /**
     * SET-06: how the last Reset Orbit ended, or null. Sticky on the service,
     * so it is still there when Settings has been popped or the app comes
     * back from the background; MainActivity collects it while resumed,
     * restarts the task on Completed, shows the failure on Failed, and calls
     * [onResetOutcomeHandled] first so the restarted process (the same one)
     * does not act on it again.
     */
    val resetOutcome: StateFlow<ResetOutcome?> = resetService.outcome

    fun onResetOutcomeHandled() = resetService.clearOutcome()

    /**
     * HOME-14 / NOTE-05: the calls waiting for a note, for Home's stack.
     *
     * [WaitingCalls] owns the rules and re-emits live (a note saved on
     * another screen, a dismissal, a new call). What Home controls is the
     * start of the 24 hour window: [onHomeResumed] moves it on every resume,
     * so a call slides out once a day has passed even while Home stays
     * composed (Home is the root destination and survives the dialer
     * round-trip). Until the first resume nothing is read.
     *
     * The time since each call is worded at each emission, against the
     * clock, so a call synced while Home is open says "just now" rather than
     * being measured from the last resume.
     *
     * A failed read shows no stack rather than crashing the screen: Home's
     * own state reads the same database and says "Orbit couldn't load your
     * lists" when it cannot (HOME-10), and this list is an extra on top of it.
     * The catch is inside the resume's flow, so the next resume reads again.
     */
    private val homeResumedAt = MutableStateFlow<Instant?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val notesWaiting: StateFlow<List<NoteWaiting>> =
        homeResumedAt
            .filterNotNull()
            .flatMapLatest { resumedAt ->
                waitingCalls.observe(resumedAt)
                    .map { rows ->
                        val now = clock.now()
                        rows.map { it.toNoteWaiting(now) }
                    }
                    .catch { emit(emptyList()) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * HOME-14: what a dismissal says ("Dismissed 3 calls" with Undo), or that
     * it could not be saved. The ids ride the event, so its Undo needs nothing
     * the screen might have lost (the Home README's snackbar note).
     */
    data class NotesWaitingEvent(val message: UiText, val undoCallEventIds: List<Long> = emptyList())

    private val _notesWaitingEvents = MutableSharedFlow<NotesWaitingEvent>(extraBufferCapacity = 4)
    val notesWaitingEvents: SharedFlow<NotesWaitingEvent> = _notesWaitingEvents.asSharedFlow()

    /** True if list / contact names should render as generic "Contact" because
     *  the app is currently backgrounded — protects the app-switcher snapshot
     *  and lock-screen preview from leaking relationships at a glance. PRD §Privacy.
     *  No user-facing toggle — quick-hide is always on. */
    val privacyCurtainActive: StateFlow<Boolean> =
        _isForeground
            .map { foreground -> !foreground }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    init {
        viewModelScope.launch {
            // Default to onboarding on any DataStore read failure so a corrupted
            // prefs file or IO error surfaces as the onboarding flow rather than
            // a splash screen hung waiting on a value that will never arrive.
            val done = runCatching { appPrefs.isOnboardingComplete.first() }.getOrDefault(false)
            _startDestination.value = when {
                done -> Routes.Home
                else -> resolveOnboardingResume()
            }
        }
        // THEMING — map the three raw appearance prefs into ThemeSettings and
        // keep _themeSettings current. Long-lived collector (live theme switch).
        viewModelScope.launch {
            combine(
                appPrefs.colorTheme,
                appPrefs.darkMode,
                appPrefs.accentHue
            ) { themeKey, darkKey, hue ->
                ThemeSettings(
                    themeId = OrbitThemeId.fromKey(themeKey),
                    darkMode = OrbitDarkMode.fromKey(darkKey),
                    accentHue = if (hue < 0) null else hue
                )
            }.collect { _themeSettings.value = it }
        }
        // Phone-contact ingestion no longer fires on every cold launch. The
        // Main-thread cost (content-provider scan + DB transaction) is deferred
        // to ContactsIngestWorker, triggered from permission grant
        // (OnboardingPermissionsViewModel) and from a ContactsContract
        // ContentObserver (ContentObserverController). The worker gates re-runs
        // with a 24h DataStore TTL so cold-start does no avoidable work.
    }

    /**
     * F-3 fix (2026-04-30 hot-fix-260430-hs4) — resolve the start destination
     * for an in-progress onboarding flow. Reads [AppPrefs.lastOnboardingStep];
     * maps the persisted enum name back to the matching `Routes.Onboard*`
     * constant. Unknown / null / blank values fall back to
     * [Routes.OnboardWelcome] (defensive — a corrupted DataStore should not
     * strand the user). The OnboardFirstList route requires a `{listId}` path
     * arg, which a start destination cannot carry. If the persisted step
     * is `FirstList`, fall back to [Routes.OnboardSync] so the user re-enters
     * at the sync gate (the closest re-runnable step). Sync then continues
     * straight into the list already being built
     * ([app.orbit.ui.screens.onboarding.OnboardingListStarter.pendingListId]),
     * so the resume never creates a second one.
     */
    private suspend fun resolveOnboardingResume(): String {
        val name = runCatching { appPrefs.lastOnboardingStep.first() }.getOrNull().orEmpty()
        val step = OnboardingStep.entries.firstOrNull { it.name == name }
        return when (step) {
            OnboardingStep.PermContacts -> Routes.OnboardPermContacts
            OnboardingStep.PermCallLog -> Routes.OnboardPermCallLog
            OnboardingStep.PermNotifications -> Routes.OnboardPermNotifs
            OnboardingStep.Sync -> Routes.OnboardSync
            OnboardingStep.FirstList -> Routes.OnboardSync // Sync continues into the saved list
            null -> Routes.OnboardWelcome
        }
    }

    fun onForegroundChanged(foreground: Boolean) {
        _isForeground.value = foreground
    }

    /**
     * HOME-14: Home resumed (and on first composition). Moves the 24 hour
     * window to now. Called from HomeScreen's `LifecycleResumeEffect`, not a
     * `LaunchedEffect(Unit)`, which fires once per composition and misses the
     * return from the dialer because Home stays composed across the call.
     */
    fun onHomeResumed() {
        homeResumedAt.value = clock.now()
    }

    /**
     * HOME-14: "Dismiss" on one call, or "Dismiss all". Persisted (NOTE-05),
     * so the calls stay closed after a restart, with an Undo; a write that
     * fails says "Couldn't save your change" and offers nothing to undo
     * (rules.md Code 3). The post-call notification for these people goes
     * away by itself once the store changes (NOTIF-16, `PostCallNotifier`).
     */
    fun dismissNotesWaiting(callEventIds: List<Long>) {
        if (callEventIds.isEmpty()) return
        viewModelScope.launch {
            val saved = writeOrSayFailed { waitingCalls.dismiss(callEventIds) }
            if (saved) {
                _notesWaitingEvents.tryEmit(
                    NotesWaitingEvent(
                        message = callEventIds.size.let { n ->
                            UiText.plural(R.plurals.home_snackbar_calls_dismissed, n, n)
                        },
                        undoCallEventIds = callEventIds,
                    ),
                )
            }
        }
    }

    /** HOME-14: the dismissal's Undo. The calls wait again. */
    fun undoDismissNotesWaiting(callEventIds: List<Long>) {
        viewModelScope.launch { writeOrSayFailed { waitingCalls.undoDismiss(callEventIds) } }
    }

    private suspend fun writeOrSayFailed(write: suspend () -> Unit): Boolean =
        try {
            write()
            true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t // rules.md Code 5
            _notesWaitingEvents.tryEmit(NotesWaitingEvent(UiText.res(R.string.components_snackbar_save_failed)))
            false
        }

    /** "14 min · 2 hours ago": the app's duration and time-since words. */
    private fun WaitingCallRow.toNoteWaiting(now: Instant): NoteWaiting = NoteWaiting(
        callEventId = callEventId,
        contactId = contactId,
        name = displayName,
        photoUri = photoUri,
        direction = direction,
        meta = UiText.res(
            R.string.components_notes_waiting_meta,
            formatDuration(durationSeconds),
            formatRelativeFine(occurredAt, now),
        ),
    )
}
