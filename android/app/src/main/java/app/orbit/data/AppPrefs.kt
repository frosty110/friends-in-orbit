package app.orbit.data

import android.Manifest
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// Tiny Preferences wrapper for the persisted flags. v0.2 carries onboarding
// state, call-log import + sync state, picker thresholds, and — re-introduced
// for WIDGET-04 widget masking only — a minimal-mode flag.
// The original minimal-mode + biometric-lock keys were removed 2026-04-28
// (whole-app review: biometric cut, minimal-mode user-toggle cut). The
// minimal-mode flag here is scoped entirely to widget rendering: it masks
// contact names as "Contact" on the home-screen widget surface. There is NO
// in-app minimal-mode toggle and NO biometric coupling (biometric lock cut on
// 2026-04-28, ADR 0003 superseded).
private val Context.dataStore by preferencesDataStore(name = "orbit_prefs")

open class AppPrefs @Inject constructor(@ApplicationContext private val context: Context) {

    val isOnboardingComplete: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_ONBOARDED] ?: false }

    /**
     * F-2 fix (2026-04-30 hot-fix-260430-hs4) — per-permission "we have asked
     * the OS for this permission at least once" flag. Disambiguates the two
     * indistinguishable cases under Android's
     * `shouldShowRequestPermissionRationale = false`: never-asked vs.
     * permanently-denied. Without this flag, fresh-install onboarding shows
     * the "Open settings" deep-link CTA before the system permission dialog
     * has ever been presented (UAT 2026-04-30).
     *
     * Each flag flips to `true` exactly once — the first time the
     * `rememberLauncherForActivityResult` callback fires for that permission.
     * Reset path: [resetAll] clears all DataStore keys (existing behavior).
     */
    val hasAskedContacts: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_HAS_ASKED_CONTACTS] ?: false }

    val hasAskedCallLog: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_HAS_ASKED_CALL_LOG] ?: false }

    val hasAskedNotifications: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_HAS_ASKED_NOTIFICATIONS] ?: false }

    /**
     * Convenience accessor — maps an Android permission constant to the
     * corresponding `hasAsked*` Flow. Unknown permissions return a constant
     * `false` Flow so callers don't need to handle the absent case.
     */
    fun hasAskedFor(permission: String): Flow<Boolean> = when (permission) {
        Manifest.permission.READ_CONTACTS -> hasAskedContacts
        Manifest.permission.READ_CALL_LOG -> hasAskedCallLog
        Manifest.permission.POST_NOTIFICATIONS -> hasAskedNotifications
        else -> flowOf(false)
    }

    /**
     * F-3 fix (2026-04-30 hot-fix-260430-hs4) — last completed onboarding
     * step name for crash-resume hydration. Written by [app.orbit.nav.OrbitNavHost]
     * on each step's entry via a `LaunchedEffect`; read by
     * [app.orbit.AppViewModel] on cold start when `isOnboardingComplete=false`.
     * Stored as the enum's `name` (String) for forward/backward compatibility
     * — adding a new step does not invalidate old data; an unknown name maps
     * back to OnboardWelcome. Cleared by [OnboardingDoneViewModel] on
     * completion alongside [setOnboardingComplete].
     */
    val lastOnboardingStep: Flow<String?> =
        context.dataStore.data.map { it[KEY_LAST_ONBOARDING_STEP] }

    // Call-log-ingestion prefs (CALL-02 / CALL-06).
    //
    // `callLogImportDays`: how far back the worker reaches on each reconcile pass
    // (90 default matches PRD §Call Detection). Coerced into [1, 3650] to rule out
    // nonsensical values from a future Settings UI.
    //
    // `lastCallLogSyncAt`: epoch millis of the last successful reconcile write,
    // read by `CallLogReconciler` as its `sinceMs` filter for incremental passes.
    // Clamped to ≥0 so a zero means "never synced — import the full window".
    //
    // `isCallLogSyncEnabled`: master toggle surfaced in Settings. When
    // false, the ContentObserver and periodic worker are no-ops. Default false so
    // onboarding remains opt-in (PRD §Privacy).
    val callLogImportDays: Flow<Int> =
        context.dataStore.data.map { it[KEY_CALL_LOG_IMPORT_DAYS] ?: 90 }

    val lastCallLogSyncAt: Flow<Long> =
        context.dataStore.data.map { it[KEY_LAST_CALL_LOG_SYNC_AT] ?: 0L }

    val isCallLogSyncEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_CALL_LOG_SYNC_ENABLED] ?: false }

    /**
     * WIDGET-04 — widget-only contact-name masking flag.
     *
     * When `true`, the 2×2 and 4×2 home-screen widgets replace the contact's
     * display name with the generic string "Contact" so shoulder-surfers cannot
     * read the name off the widget. Avatar initials and photos are also masked
     * to a silhouette. This flag is NOT coupled to any in-app minimal-mode
     * toggle (the user-facing toggle was cut 2026-04-28, ADR 0003 superseded)
     * and carries ZERO biometric coupling. It can only be set via
     * [setMinimalModeEnabled] (currently wired in the Settings surface).
     *
     * Default `false` — real names shown unless the user opts into masking.
     */
    val minimalModeEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_MINIMAL_MODE] ?: false }

    /**
     * 5-minute TTL gate for [app.orbit.data.feed.HomeFeed.refreshDueCountsIfStale].
     *
     * Stores the epoch-millis of the last successful `dueCount` recompute pass
     * across all active lists. Default `0L` (never recomputed → first foreground
     * after install triggers a refresh). The gate exists because `lists.dueCount`
     * is column-backed; a contact whose `nextDueAt` crosses `clock.now()` without
     * any write is invisible to the column until something else writes to that
     * list. The 5-minute foreground gate eliminates user-perceptible staleness
     * without adding a tick worker.
     *
     * `open` so test fixtures can subclass [AppPrefs] and stub this Flow + setter
     * without standing up a real DataStore.
     */
    open val lastDueCountRecomputeAt: Flow<Long> =
        context.dataStore.data.map { it[KEY_LAST_DUE_COUNT_RECOMPUTE_AT] ?: 0L }

    /**
     * 24h TTL gate for [app.orbit.calllog.ContactsIngestWorker].
     *
     * Stored as epoch millis; surfaced as a nullable [Instant] so the worker
     * can treat "never ingested" as a distinct case from "ingested recently".
     * The worker writes via [setLastContactsIngestedAt] on success and reads
     * via this flow inside [doWork] to decide whether to skip a re-run.
     */
    val lastContactsIngestedAt: Flow<Instant?> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_LAST_CONTACTS_INGESTED_AT]?.let { Instant.ofEpochMilli(it) }
        }

    // Picker thresholds (PICK-07).
    //
    // Four user-tunable knobs that drive the shared ContactPicker's filter chips:
    //   * `commonlyCalledTopPct`  — top N% of call counts qualify as "commonly called"
    //   * `rarelyCalledBottomPct` — bottom N% (excluding zero) qualify as "rarely called"
    //   * `recentlyAddedDays`     — N-day window for "recently added"
    //   * `longGapDays`           — N-day silence threshold for "long gap"
    //
    // Defaults (20, 50, 30, 90) and coercion bounds (5..50, 10..90, 1..3650,
    // 1..3650) match the ThresholdStepperRow spec — out-of-range writes clamp
    // rather than throw, so a future bug in the stepper UI cannot poison the
    // DataStore.
    val commonlyCalledTopPct: Flow<Int> =
        context.dataStore.data.map { it[KEY_COMMONLY_CALLED_TOP_PCT] ?: 20 }

    val rarelyCalledBottomPct: Flow<Int> =
        context.dataStore.data.map { it[KEY_RARELY_CALLED_BOTTOM_PCT] ?: 50 }

    val recentlyAddedDays: Flow<Int> =
        context.dataStore.data.map { it[KEY_RECENTLY_ADDED_DAYS] ?: 30 }

    val longGapDays: Flow<Int> =
        context.dataStore.data.map { it[KEY_LONG_GAP_DAYS] ?: 90 }

    // Convenience aggregate — `ContactPickerViewModel` consumes this inside its
    // `combine()` block so a single threshold change re-fans every filter without
    // having to plumb four separate flows.
    val pickerThresholds: Flow<PickerThresholds> = combine(
        commonlyCalledTopPct,
        rarelyCalledBottomPct,
        recentlyAddedDays,
        longGapDays,
    ) { top, bottom, recent, gap -> PickerThresholds(top, bottom, recent, gap) }

    // Appearance (THEMING 2026-06-22). Stored as raw primitives so the data
    // layer carries no dependency on the UI theme package; the UI layer maps
    // these into ThemeSettings via OrbitThemeId/OrbitDarkMode.fromKey(). Defaults
    // mirror those enums' DEFAULT (warm + system). accentHue is -1 for "use the
    // theme's own accent"; 0..359 is a dial override.
    val colorTheme: Flow<String> =
        context.dataStore.data.map { it[KEY_COLOR_THEME] ?: "warm" }

    val darkMode: Flow<String> =
        context.dataStore.data.map { it[KEY_DARK_MODE] ?: "system" }

    val accentHue: Flow<Int> =
        context.dataStore.data.map { it[KEY_ACCENT_HUE] ?: -1 }

    suspend fun setOnboardingComplete(value: Boolean) {
        context.dataStore.edit { it[KEY_ONBOARDED] = value }
    }

    /**
     * F-2 fix (2026-04-30 hot-fix-260430-hs4) — flip the per-permission
     * "we have asked once" flag. Called from the
     * `rememberLauncherForActivityResult` callback in
     * [app.orbit.ui.screens.onboarding.OnboardingPermScreen] so
     * `isPermanentlyDenied` can disambiguate first-launch (never asked) from
     * "don't ask again" (asked, declined permanently). Future permissions:
     * no-op until explicitly mapped.
     */
    suspend fun setHasAsked(permission: String) {
        context.dataStore.edit { prefs ->
            when (permission) {
                Manifest.permission.READ_CONTACTS -> prefs[KEY_HAS_ASKED_CONTACTS] = true
                Manifest.permission.READ_CALL_LOG -> prefs[KEY_HAS_ASKED_CALL_LOG] = true
                Manifest.permission.POST_NOTIFICATIONS -> prefs[KEY_HAS_ASKED_NOTIFICATIONS] = true
                else -> Unit
            }
        }
    }

    /**
     * F-3 fix (2026-04-30 hot-fix-260430-hs4) — write the persisted
     * onboarding-step name. Pass `null` to clear (called from
     * [OnboardingDoneViewModel] on completion).
     */
    suspend fun setLastOnboardingStep(stepName: String?) {
        context.dataStore.edit { prefs ->
            if (stepName == null) prefs.remove(KEY_LAST_ONBOARDING_STEP)
            else prefs[KEY_LAST_ONBOARDING_STEP] = stepName
        }
    }

    suspend fun setCallLogImportDays(value: Int) {
        context.dataStore.edit { it[KEY_CALL_LOG_IMPORT_DAYS] = value.coerceIn(1, 3650) }
    }

    suspend fun setLastCallLogSyncAt(value: Long) {
        context.dataStore.edit { it[KEY_LAST_CALL_LOG_SYNC_AT] = value.coerceAtLeast(0L) }
    }

    suspend fun setCallLogSyncEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_CALL_LOG_SYNC_ENABLED] = value }
    }

    /** WIDGET-04 — setter companion to [minimalModeEnabled]. */
    suspend fun setMinimalModeEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_MINIMAL_MODE] = value }
    }

    /** Setter companion to [lastDueCountRecomputeAt]; written by the staleness gate on success. */
    open suspend fun setLastDueCountRecomputeAt(epochMs: Long) {
        context.dataStore.edit { it[KEY_LAST_DUE_COUNT_RECOMPUTE_AT] = epochMs.coerceAtLeast(0L) }
    }

    /** Setter companion to [lastContactsIngestedAt]; written by the worker on success. */
    suspend fun setLastContactsIngestedAt(at: Instant) {
        context.dataStore.edit { it[KEY_LAST_CONTACTS_INGESTED_AT] = at.toEpochMilli() }
    }

    suspend fun setCommonlyCalledTopPct(value: Int) {
        context.dataStore.edit { it[KEY_COMMONLY_CALLED_TOP_PCT] = value.coerceIn(5, 50) }
    }

    suspend fun setRarelyCalledBottomPct(value: Int) {
        context.dataStore.edit { it[KEY_RARELY_CALLED_BOTTOM_PCT] = value.coerceIn(10, 90) }
    }

    suspend fun setRecentlyAddedDays(value: Int) {
        context.dataStore.edit { it[KEY_RECENTLY_ADDED_DAYS] = value.coerceIn(1, 3650) }
    }

    suspend fun setLongGapDays(value: Int) {
        context.dataStore.edit { it[KEY_LONG_GAP_DAYS] = value.coerceIn(1, 3650) }
    }

    /** THEMING — persist the chosen theme id key (OrbitThemeId.key). */
    suspend fun setColorTheme(key: String) {
        context.dataStore.edit { it[KEY_COLOR_THEME] = key }
    }

    /** THEMING — persist the dark-mode choice (OrbitDarkMode.key). */
    suspend fun setDarkMode(key: String) {
        context.dataStore.edit { it[KEY_DARK_MODE] = key }
    }

    /** THEMING — persist the accent-dial hue; null clears the override (-1). */
    suspend fun setAccentHue(hue: Int?) {
        context.dataStore.edit {
            it[KEY_ACCENT_HUE] = hue?.let { h -> ((h % 360) + 360) % 360 } ?: -1
        }
    }

    /**
     * SET-06 — destructive wipe of every key in the DataStore. Used by
     * [app.orbit.data.repository.ResetService] in the user-confirmed Reset path.
     *
     * After this returns, every flow that reads `prefs[KEY_*] ?: default` falls
     * back to its default — including `KEY_ONBOARDED`, so the next cold start
     * re-enters the welcome screen (F1: reinstall = re-onboard semantics).
     */
    suspend fun resetAll() {
        context.dataStore.edit { it.clear() }
    }

    companion object {
        private val KEY_ONBOARDED = booleanPreferencesKey("onboarding_complete")
        private val KEY_CALL_LOG_IMPORT_DAYS = intPreferencesKey("call_log_import_days")
        private val KEY_LAST_CALL_LOG_SYNC_AT = longPreferencesKey("last_call_log_sync_at_ms")
        private val KEY_CALL_LOG_SYNC_ENABLED = booleanPreferencesKey("call_log_sync_enabled")
        // WIDGET-04 — widget-only contact-name masking. Re-introduced
        // scoped to widgets after the in-app toggle was cut (ADR 0003 superseded).
        private val KEY_MINIMAL_MODE = booleanPreferencesKey("minimal_mode_enabled")
        private val KEY_LAST_DUE_COUNT_RECOMPUTE_AT = longPreferencesKey("last_due_count_recompute_at")
        private val KEY_LAST_CONTACTS_INGESTED_AT = longPreferencesKey("last_contacts_ingested_at")
        private val KEY_COMMONLY_CALLED_TOP_PCT = intPreferencesKey("commonly_called_top_pct")
        private val KEY_RARELY_CALLED_BOTTOM_PCT = intPreferencesKey("rarely_called_bottom_pct")
        private val KEY_RECENTLY_ADDED_DAYS = intPreferencesKey("recently_added_days")
        private val KEY_LONG_GAP_DAYS = intPreferencesKey("long_gap_days")
        // THEMING 2026-06-22 — user-selectable appearance.
        private val KEY_COLOR_THEME = stringPreferencesKey("color_theme")
        private val KEY_DARK_MODE = stringPreferencesKey("dark_mode")
        private val KEY_ACCENT_HUE = intPreferencesKey("accent_hue")
        // F-2 fix (2026-04-30 hot-fix-260430-hs4) — per-permission "asked
        // at least once" flags. See [hasAskedContacts] / [setHasAsked].
        private val KEY_HAS_ASKED_CONTACTS = booleanPreferencesKey("has_asked_contacts")
        private val KEY_HAS_ASKED_CALL_LOG = booleanPreferencesKey("has_asked_call_log")
        private val KEY_HAS_ASKED_NOTIFICATIONS = booleanPreferencesKey("has_asked_notifications")
        // F-3 fix (2026-04-30 hot-fix-260430-hs4) — last completed onboarding
        // step name for crash-resume hydration. See [lastOnboardingStep] /
        // [setLastOnboardingStep].
        private val KEY_LAST_ONBOARDING_STEP = stringPreferencesKey("last_onboarding_step")
    }
}

/**
 * Aggregate snapshot of the four picker thresholds (PICK-07).
 *
 * Surfaced as `AppPrefs.pickerThresholds: Flow<PickerThresholds>` so a
 * `ContactPickerViewModel.combine()` can fan every filter chip from a
 * single source. `DEFAULT` mirrors the per-flow defaults (20 / 50 / 30 / 90)
 * for use as `initialValue` in `stateIn`.
 */
data class PickerThresholds(
    val commonlyTopPct: Int,
    val rarelyBottomPct: Int,
    val recentlyAddedDays: Int,
    val longGapDays: Int,
) {
    companion object {
        val DEFAULT = PickerThresholds(20, 50, 30, 90)
    }
}
