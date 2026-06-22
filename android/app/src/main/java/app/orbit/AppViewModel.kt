package app.orbit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.data.AppPrefs
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import app.orbit.domain.clock.Clock
import app.orbit.nav.Routes
import app.orbit.ui.screens.onboarding.OnboardingStep
import app.orbit.ui.theme.OrbitDarkMode
import app.orbit.ui.theme.OrbitThemeId
import app.orbit.ui.theme.ThemeSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Boot-time + global UI state. Kept small on purpose — per-screen state lives
 * in per-screen Hilt view-models. This VM owns:
 *   - start destination (onboarding vs home) — resolved once from [AppPrefs].
 *   - privacy curtain (auto-only — flips on focus loss, restores on focus regain).
 *
 * Hilt constructs this VM via `@AndroidEntryPoint` on MainActivity +
 * `by viewModels<AppViewModel>()`; there is no manual factory companion. The
 * VM does not own a repository for permission checks — per-screen VMs check
 * their own permissions, and call-log ingestion lives in `CallLogSyncWorker`.
 * Both exposed StateFlows use `WhileSubscribed(5_000L)` per ARCH-02.
 *
 * 2026-04-28: removed biometric-lock flag and the user-toggled minimal-mode
 * half of the privacy curtain combine. Quick-hide on focus loss survives —
 * it's the only privacy substrate now.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val appPrefs: AppPrefs,
    private val callEventRepo: CallEventRepository,
    private val contactRepo: ContactRepository,
    private val clock: Clock,
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
     * NOTE-02 — post-call banner state.
     *
     * Re-derived imperatively on every app resume (HomeScreen calls
     * [checkPostCallPrompt] inside `LifecycleResumeEffect` — do NOT use
     * `LaunchedEffect(Unit)`; that fires once per composition and misses the
     * dialer→app return because Home stays composed).
     *
     * `dismissedCallEventIds` is intentionally process-scoped (in-memory): a
     * fresh process should re-prompt — feature, not bug. The set is keyed by
     * callEventId so a retroactive note about an older call can't suppress a
     * banner for a newer one.
     */
    data class PostCallPromptState(
        val callEventId: Long,
        val contactId: Long,
        val contactName: String,
    )

    private val dismissedCallEventIds = mutableSetOf<Long>()

    private val _postCallPrompt = MutableStateFlow<PostCallPromptState?>(null)
    val postCallPrompt: StateFlow<PostCallPromptState?> = _postCallPrompt.asStateFlow()

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
                appPrefs.accentHue,
            ) { themeKey, darkKey, hue ->
                ThemeSettings(
                    themeId = OrbitThemeId.fromKey(themeKey),
                    darkMode = OrbitDarkMode.fromKey(darkKey),
                    accentHue = if (hue < 0) null else hue,
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
     * arg which is NOT recoverable from prefs alone — if the persisted step
     * is `FirstList`, fall back to [Routes.OnboardSync] so the user re-enters
     * at the sync gate (the closest re-runnable step) and the Preview screen
     * will re-create a list before re-routing forward.
     */
    private suspend fun resolveOnboardingResume(): String {
        val name = runCatching { appPrefs.lastOnboardingStep.first() }.getOrNull().orEmpty()
        val step = OnboardingStep.entries.firstOrNull { it.name == name }
        return when (step) {
            OnboardingStep.PermContacts      -> Routes.OnboardPermContacts
            OnboardingStep.PermCallLog       -> Routes.OnboardPermCallLog
            OnboardingStep.PermNotifications -> Routes.OnboardPermNotifs
            OnboardingStep.Sync              -> Routes.OnboardSync
            OnboardingStep.FirstList         -> Routes.OnboardSync   // listId not recoverable
            null                             -> Routes.OnboardWelcome
        }
    }

    fun onForegroundChanged(foreground: Boolean) { _isForeground.value = foreground }

    /**
     * NOTE-02 — re-derive [postCallPrompt] from disk. Called from
     * [HomeScreen]'s `LifecycleResumeEffect` (NOT `LaunchedEffect(Unit)` —
     * a single-fire effect misses the dialer-return path because Home stays
     * composed across the call).
     *
     * Window: 10 minutes. Suppression: in-memory dismissed-set, NOT a Room
     * column — the user dismissing a banner is process-scoped intent, not a
     * persistent fact about the call. Process death wipes the set; the next
     * cold launch re-prompts — feature, not bug.
     */
    fun checkPostCallPrompt() {
        viewModelScope.launch {
            val since = clock.now().minus(Duration.ofMinutes(10))
            val event = callEventRepo.latestUnnotedOutgoing(since)
            if (event == null || event.id in dismissedCallEventIds) {
                _postCallPrompt.value = null
                return@launch
            }
            // CallEventEntity.contactId is non-nullable in the current schema.
            // Defensive lookup still: if the contact row is gone (orphaned /
            // deleted in another flow) we suppress rather than render a banner
            // with a blank name.
            val contact = contactRepo.getById(event.contactId) ?: run {
                _postCallPrompt.value = null
                return@launch
            }
            _postCallPrompt.value = PostCallPromptState(
                callEventId = event.id,
                contactId = event.contactId,
                contactName = contact.displayName,
            )
        }
    }

    /** NOTE-02 — record dismissal in the process-scoped set and clear the
     *  visible banner state. */
    fun dismissPostCallPrompt(callEventId: Long) {
        dismissedCallEventIds += callEventId
        _postCallPrompt.value = null
    }
}
