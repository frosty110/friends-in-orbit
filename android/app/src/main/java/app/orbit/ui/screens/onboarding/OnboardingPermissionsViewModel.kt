package app.orbit.ui.screens.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.orbit.calllog.ContactsIngestWorker
import app.orbit.data.AppPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * OnboardingPermissions ViewModel (ARCH-02 + ARCH-04).
 *
 * NO permission-source indirection interface, NO Android-impl binding, NO
 * DomainModule `@Binds`. Uses [ContextCompat.checkSelfPermission] directly on
 * the `@ApplicationContext`-injected [Context]. A testability indirection seam
 * is intentionally not added here.
 *
 * State is held in a `MutableStateFlow` exposed via `asStateFlow()`. The
 * ViewModel itself survives configuration changes (ARCH-02), so wrapping the
 * already-stateful flow in a cold-to-hot pipeline would double-buffer without
 * benefit — this VM has no cold upstream to keep warm. `onRefresh()` is how
 * the Screen requests a re-read when the user returns from Settings -> Apps
 * after flipping a permission.
 *
 * On-device validation covers permission-flag correctness. JVM shape-only
 * tests verify only that the StateFlow emits a [OnboardingPermissionsUiState.Ready]
 * instance under `onRefresh()` — the boolean values depend on the test host's
 * grants and are not asserted.
 *
 * When [computeState] observes a `READ_CONTACTS` flip from
 * denied → granted, enqueue [ContactsIngestWorker] (the cold-start ingestion
 * path that used to live in `AppViewModel.init` is now event-triggered).
 * `ExistingWorkPolicy.KEEP` is intentional — concurrent grant + observer
 * fires must not stack; the worker's own 24h TTL gates re-runs.
 *
 * F-2 fix (2026-04-30 hot-fix-260430-hs4): combine [AppPrefs.hasAskedFor]
 * flows with the synchronously-computed permission state so
 * [OnboardingPermScreen.isPermanentlyDenied] can disambiguate first-launch
 * from "don't ask again". [onLauncherFired] flips the per-permission flag
 * the first time the system dialog completes (granted or denied).
 */
@HiltViewModel
class OnboardingPermissionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPrefs: AppPrefs,
) : ViewModel() {

    @Volatile private var lastSeenHasContacts: Boolean =
        checkPerm(Manifest.permission.READ_CONTACTS)

    private val _permState = MutableStateFlow(computePermSnapshot())

    val uiState: StateFlow<OnboardingPermissionsUiState> = combine(
        _permState,
        appPrefs.hasAskedContacts,
        appPrefs.hasAskedCallLog,
        appPrefs.hasAskedNotifications,
    ) { perms, askedContacts, askedCallLog, askedNotifs ->
        OnboardingPermissionsUiState.Ready(
            hasCallLog = perms.hasCallLog,
            hasContacts = perms.hasContacts,
            hasNotifications = perms.hasNotifications,
            hasAskedContacts = askedContacts,
            hasAskedCallLog = askedCallLog,
            hasAskedNotifications = askedNotifs,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = OnboardingPermissionsUiState.Ready(
            hasCallLog = _permState.value.hasCallLog,
            hasContacts = _permState.value.hasContacts,
            hasNotifications = _permState.value.hasNotifications,
        ),
    )

    /**
     * Re-reads the three permission flags and emits a fresh Ready. Called from
     * the Screen on entry + every ON_RESUME — the user may flip a permission
     * in Settings -> Apps and return to the flow, so we refresh without
     * requiring a re-launch.
     */
    fun onRefresh() {
        _permState.value = computePermSnapshot()
    }

    /**
     * F-2 fix — flip [AppPrefs.setHasAsked] for the given permission. Called
     * from the `rememberLauncherForActivityResult` callback inside
     * [OnboardingPermScreen] BEFORE [onRefresh] so the `isPermanentlyDenied`
     * recompute on the next emission sees `hasBeenAsked = true`.
     */
    fun onLauncherFired(permission: String) {
        viewModelScope.launch { appPrefs.setHasAsked(permission) }
    }

    private fun computePermSnapshot(): PermSnapshot {
        val hasCallLog = checkPerm(Manifest.permission.READ_CALL_LOG)
        val hasContacts = checkPerm(Manifest.permission.READ_CONTACTS)
        val hasNotifications = checkPerm(Manifest.permission.POST_NOTIFICATIONS)
        if (!lastSeenHasContacts && hasContacts) {
            enqueueContactsIngest()
        }
        lastSeenHasContacts = hasContacts
        return PermSnapshot(hasCallLog, hasContacts, hasNotifications)
    }

    private fun enqueueContactsIngest() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ContactsIngestWorker.UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ContactsIngestWorker>().build(),
        )
    }

    private fun checkPerm(name: String): Boolean =
        ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED

    private data class PermSnapshot(
        val hasCallLog: Boolean,
        val hasContacts: Boolean,
        val hasNotifications: Boolean,
    )
}
