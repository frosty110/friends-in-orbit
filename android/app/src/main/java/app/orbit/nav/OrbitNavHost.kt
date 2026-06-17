package app.orbit.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.orbit.data.AppPrefs
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListType
import app.orbit.data.repository.ListRepository
import app.orbit.ui.screens.browse.BrowseListScreen
import app.orbit.ui.screens.browse.BrowseViewModel
import app.orbit.ui.screens.browse.GlobalSearchScreen
import app.orbit.ui.screens.calllog.CallLogScreen
import app.orbit.ui.screens.calllog.CallLogViewModel
import app.orbit.ui.screens.card.CardViewScreen
import app.orbit.ui.screens.card.CardViewViewModel
import app.orbit.ui.screens.contact.ContactDetailScreen
import app.orbit.ui.screens.contact.ContactDetailViewModel
import app.orbit.ui.screens.home.HomeScreen
import app.orbit.ui.screens.home.HomeViewModel
import app.orbit.ui.screens.lists.ListConfigScreen
import app.orbit.ui.screens.lists.ListConfigViewModel
import app.orbit.ui.screens.lists.ListsManagerScreen
import app.orbit.ui.screens.lists.ListsManagerViewModel
import app.orbit.ui.screens.onboarding.OnboardingDoneScreen
import app.orbit.ui.screens.onboarding.OnboardingFirstListScreen
import app.orbit.ui.screens.onboarding.OnboardingPermCallLogScreen
import app.orbit.ui.screens.onboarding.OnboardingPermContactsScreen
import app.orbit.ui.screens.onboarding.OnboardingPermNotificationsScreen
import app.orbit.ui.screens.onboarding.OnboardingPreviewScreen
import app.orbit.ui.screens.onboarding.OnboardingStep
import app.orbit.ui.screens.onboarding.OnboardingSyncScreen
import app.orbit.ui.screens.onboarding.OnboardingWelcomeScreen
import app.orbit.ui.screens.picker.ContactPickerScreen
import app.orbit.ui.screens.picker.ListPickerScreen
import app.orbit.ui.screens.picker.PickerCommitSnackbarHost
import app.orbit.ui.screens.settings.SettingsScreen
import app.orbit.ui.screens.settings.SettingsViewModel
import app.orbit.ui.screens.settings.ignored.SettingsIgnoredScreen
import app.orbit.ui.screens.settings.ignored.SettingsIgnoredViewModel
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * Navigation graph — one [composable] block per route, each scoped to its own
 * Hilt-constructed ViewModel via explicit [hiltViewModel].
 *
 * Onboarding flow — Welcome →
 * Permissions(Contacts → CallLog → Notifications) → Sync (blocking call-log
 * gate) → Preview (auto-skips when <3 candidates) → FirstList (production
 * List Configuration reused) → Done → Home. The single transactional
 * `setOnboardingComplete(true)` write lives in [OnboardingDoneViewModel.init];
 * the NavHost no longer carries a duplicate write.
 *
 * The "Make this my first list" / "Start blank" / "Add another list" CTAs
 * each create a new STATIC list at navigate-time via [createOnboardingFirstList],
 * which calls [ListRepository.create] + [ListRepository.addMember]. The user's
 * typed name is written by [ListConfigViewModel.setName] inside the
 * OnboardingFirstListScreen.
 *
 * @param listRepo ListRepository instance threaded from MainActivity (where
 *   Hilt resolves it via field injection). Used by [createOnboardingFirstList]
 *   for inline list-creation at navigate-time. Chosen over a
 *   `@Singleton OnboardingListBootstrapper` to keep the diff minimal — one
 *   extra parameter on this composable + one `@Inject` on MainActivity.
 */
/**
 * @param navigateTo Optional route string produced by a notification PendingIntent.
 *   When non-null the [LaunchedEffect] inside this composable
 *   calls [nav.navigate] and then invokes [onNavigateToConsumed] to clear the value
 *   in [MainActivity] so a recomposition does not re-navigate. The extra carries a
 *   fully-formed route ("card/{listId}" or "contact/{contactId}") built by
 *   Routes.card/Routes.contact in the notification workers.
 *
 *   Security: nav.navigate only resolves against declared Routes — an unknown or
 *   malformed string is a no-op (T-10-21). The PendingIntents are FLAG_IMMUTABLE so
 *   no external app can inject an arbitrary string (T-10-20).
 * @param onNavigateToConsumed Callback invoked after navigation so the Activity
 *   clears the navigateTo state and prevents re-navigation on recomposition.
 */
@Composable
fun OrbitNavHost(
    nav: NavHostController,
    listRepo: ListRepository,
    appPrefs: AppPrefs,
    startDestination: String = Routes.Home,
    navigateTo: String? = null,
    onNavigateToConsumed: () -> Unit = {},
) {
    // D-17: consume the NAVIGATE_TO extra from a notification tap.
    // Keyed on the value so it re-fires each time a new (non-null) destination
    // arrives (cold start or warm onNewIntent). After navigation, call
    // onNavigateToConsumed so the Activity clears the value — prevents
    // re-navigation on config changes or recompositions.
    LaunchedEffect(navigateTo) {
        if (!navigateTo.isNullOrBlank()) {
            nav.navigate(navigateTo)
            onNavigateToConsumed()
        }
    }

    // Picker-commit lifecycle — the pickers pop on commit, so their
    // result snackbar ("Added N · Undo" / "Couldn't save that") must outlive
    // the picker's own composition. PickerCommitSnackbarHost collects the
    // app-lifetime PickerCommitBus and renders above whatever screen the pop
    // lands on. The graph itself is unchanged, split into [OrbitNavGraph] so
    // the overlay Box doesn't re-indent every route.
    Box(modifier = Modifier.fillMaxSize()) {
        OrbitNavGraph(
            nav = nav,
            listRepo = listRepo,
            appPrefs = appPrefs,
            startDestination = startDestination,
        )
        PickerCommitSnackbarHost(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding(),
        )
    }
}

@Composable
private fun OrbitNavGraph(
    nav: NavHostController,
    listRepo: ListRepository,
    appPrefs: AppPrefs,
    startDestination: String,
) {
    NavHost(navController = nav, startDestination = startDestination) {
        composable(Routes.Home) {
            HomeScreen(
                vm                      = hiltViewModel<HomeViewModel>(),
                onOpenList              = { listId -> nav.navigate(Routes.card(listId)) },
                onOpenSearch            = { nav.navigate(Routes.GlobalSearch) },
                onOpenSettings          = { nav.navigate(Routes.Settings) },
                onOpenLists             = { nav.navigate(Routes.lists()) },
                onCreateList            = { nav.navigate(Routes.lists(openCreate = true)) },
                // Long-press quick-actions — navigation legs (add people, list settings).
                onAddPeopleToList       = { listId -> nav.navigate(Routes.pickContacts(listId)) },
                onOpenListSettings      = { listId -> nav.navigate(Routes.listConfig(listId)) },
                // NOTE-02 — PostCallBanner "Add a note" tap routes to
                // ContactDetail with focusNote=true so the Notes input claims
                // focus once the screen settles.
                onOpenContactWithFocus  = { id, focus ->
                    nav.navigate(Routes.contactWithFocus(id, focus))
                },
            )
        }
        composable(
            Routes.Card,
            arguments = listOf(navArgument("listId") { type = NavType.StringType }),
        ) { entry ->
            CardViewScreen(
                vm             = hiltViewModel<CardViewViewModel>(),
                listId         = entry.arguments?.getString("listId") ?: "inner",
                onBack         = { nav.popBackStack() },
                onCall         = { contactId -> nav.navigate(Routes.contact(contactId)) },
                onBrowse       = { listId -> nav.navigate(Routes.browse(listId)) },
                onEditList     = { listId -> nav.navigate(Routes.listConfig(listId)) },
                onAddContacts  = { listId -> nav.navigate(Routes.pickContacts(listId)) },
                // 2026-06-09 — call-log-denied notice deep-links to Settings,
                // where the permission row hosts the grant flow.
                onOpenSettings = { nav.navigate(Routes.Settings) },
            )
        }
        composable(
            Routes.Browse,
            arguments = listOf(navArgument("listId") { type = NavType.StringType }),
        ) { entry ->
            BrowseListScreen(
                vm            = hiltViewModel<BrowseViewModel>(),
                listId        = entry.arguments?.getString("listId") ?: "inner",
                onBack        = { nav.popBackStack() },
                onOpenContact = { contactId -> nav.navigate(Routes.contact(contactId)) },
                onAddContacts = { lid -> nav.navigate(Routes.pickContacts(lid ?: "inner")) },
            )
        }
        composable(Routes.GlobalSearch) {
            GlobalSearchScreen(
                onBack        = { nav.popBackStack() },
                onOpenContact = { contactId -> nav.navigate(Routes.contact(contactId)) },
                // "Add to list" routes to the existing list picker; the
                // VM-side commit surfaces on the app-level snackbar host,
                // so navigation alone is enough. The picker VM
                // strips the "c-" prefix from the UI contact id.
                onAddToLists  = { contactId -> nav.navigate(Routes.pickLists(contactId)) },
            )
        }
        composable(
            Routes.Contact,
            arguments = listOf(
                navArgument("contactId") { type = NavType.StringType },
                // NOTE-02 / LOG-03 — optional StringType args (default
                // null). The ContactDetailViewModel reads them via
                // SavedStateHandle and translates: "1" → focus the Notes
                // input, parsed Long → scroll to the matching call event row.
                navArgument("focusNote") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("scrollToCallEventId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            ContactDetailScreen(
                vm           = hiltViewModel<ContactDetailViewModel>(),
                contactId    = entry.arguments?.getString("contactId") ?: "c-sarah",
                onBack       = { nav.popBackStack() },
                onAddToLists = { contactId -> nav.navigate(Routes.pickLists(contactId)) },
                // CONTACT-06 — Re-link tap routes to ContactPickerScreen with
                // mode=relink. The picker's relink-mode filter behavior (hide
                // already-tracked contacts) is a cosmetic deferral.
                onRelink     = { cid -> nav.navigate(Routes.pickContacts(cid.toString(), mode = "relink")) },
                // LOG-01 — overflow → CallLogScreen, wired through the dedicated
                // call-log nav destination.
                onViewAllCalls = { nav.navigate(Routes.CallLog) },
            )
        }
        composable(
            Routes.Lists,
            arguments = listOf(
                navArgument("openCreate") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            ListsManagerScreen(
                vm                  = hiltViewModel<ListsManagerViewModel>(),
                onBack              = { nav.popBackStack() },
                onOpenList          = { listId -> nav.navigate(Routes.listConfig(listId)) },
                onAddContacts       = { listId -> nav.navigate(Routes.pickContacts(listId)) },
                openCreateOnLaunch  = entry.arguments?.getBoolean("openCreate") == true,
            )
        }
        composable(
            Routes.ListConfig,
            arguments = listOf(navArgument("listId") { type = NavType.StringType }),
        ) { entry ->
            ListConfigScreen(
                vm     = hiltViewModel<ListConfigViewModel>(),
                listId = entry.arguments?.getString("listId") ?: "inner",
                onBack = { nav.popBackStack() },
                onSave = { nav.popBackStack() },
                onAddContacts = { lid -> nav.navigate(Routes.pickContacts(lid)) },
            )
        }
        composable(Routes.Settings) {
            SettingsScreen(
                vm                = hiltViewModel<SettingsViewModel>(),
                onBack            = { nav.popBackStack() },
                onOpenIgnored     = { nav.navigate(Routes.SettingsIgnored) },
                onOpenCallHistory = { nav.navigate(Routes.CallLog) },
            )
        }
        // IGNORE-06 — Settings → Ignored full nav destination.
        composable(Routes.SettingsIgnored) {
            SettingsIgnoredScreen(
                vm     = hiltViewModel<SettingsIgnoredViewModel>(),
                onBack = { nav.popBackStack() },
            )
        }
        // LOG-01 — chronological in-app call log.
        composable(Routes.CallLog) {
            CallLogScreen(
                vm     = hiltViewModel<CallLogViewModel>(),
                onBack = { nav.popBackStack() },
                onOpenContact = { contactId, callEventId ->
                    // I2 — named-arg `focusNote = true` for clarity; the
                    // helper produces "contact/{id}?focusNote=1&scrollToCallEventId={...}"
                    // which the ContactDetailViewModel parses via SavedStateHandle.
                    nav.navigate(
                        Routes.contactWithFocus(
                            contactId = contactId.toString(),
                            focusNote = true,
                            scrollToCallEventId = callEventId,
                        ),
                    )
                },
            )
        }

        // ─── Onboarding flow ───────────────────────────────────────────────
        // Welcome → Contacts perm → CallLog perm → Notifications perm →
        // Sync (blocking call-log gate) → Preview (auto-skips when <3
        // candidates) → FirstList (production List Configuration reused) →
        // Done → Home. Permission order is fixed: most-impactful permission
        // first so a half-bail still leaves Orbit functional. Sync is
        // non-skippable. First-list creation is required for activation.
        composable(Routes.OnboardWelcome) {
            OnboardingWelcomeScreen(
                onContinue = { nav.navigate(Routes.OnboardPermContacts) },
            )
        }
        composable(Routes.OnboardPermContacts) {
            // Persist the step on entry so a mid-flow crash returns the user
            // here on re-launch (read by AppViewModel.resolveOnboardingResume).
            LaunchedEffect(Unit) { appPrefs.setLastOnboardingStep(OnboardingStep.PermContacts.name) }
            OnboardingPermContactsScreen(
                onBack = { nav.popBackStack() },
                onContinue = { nav.navigate(Routes.OnboardPermCallLog) },
            )
        }
        composable(Routes.OnboardPermCallLog) {
            LaunchedEffect(Unit) { appPrefs.setLastOnboardingStep(OnboardingStep.PermCallLog.name) }
            OnboardingPermCallLogScreen(
                onBack = { nav.popBackStack() },
                onContinue = { nav.navigate(Routes.OnboardPermNotifs) },
            )
        }
        composable(Routes.OnboardPermNotifs) {
            LaunchedEffect(Unit) { appPrefs.setLastOnboardingStep(OnboardingStep.PermNotifications.name) }
            OnboardingPermNotificationsScreen(
                onBack = { nav.popBackStack() },
                onContinue = { nav.navigate(Routes.OnboardSync) },
            )
        }
        composable(Routes.OnboardSync) {
            LaunchedEffect(Unit) { appPrefs.setLastOnboardingStep(OnboardingStep.Sync.name) }
            OnboardingSyncScreen(
                onContinue = { nav.navigate(Routes.OnboardPreview) },
            )
        }
        composable(Routes.OnboardPreview) {
            // The preview auto-skips when <3 candidates match → onSkip routes
            // the user to a freshly-created empty list. The "Make this my
            // first list" path creates a list pre-populated with the candidate
            // contacts; both paths land on OnboardFirstList(listId).
            val scope = rememberCoroutineScope()
            OnboardingPreviewScreen(
                onAccept = { defaultName, contactIds ->
                    scope.launch {
                        val newListId = createOnboardingFirstList(
                            listRepo = listRepo,
                            name = defaultName,
                            memberContactIds = contactIds,
                        )
                        nav.navigate(Routes.firstList(newListId.toString())) {
                            popUpTo(Routes.OnboardSync) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                onSkip = {
                    scope.launch {
                        val newListId = createOnboardingFirstList(
                            listRepo = listRepo,
                            name = "",
                            memberContactIds = emptyList(),
                        )
                        nav.navigate(Routes.firstList(newListId.toString())) {
                            popUpTo(Routes.OnboardSync) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        composable(
            Routes.OnboardFirstList,
            arguments = listOf(navArgument("listId") { type = NavType.StringType }),
        ) { entry ->
            val listId = entry.arguments?.getString("listId") ?: return@composable
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { appPrefs.setLastOnboardingStep(OnboardingStep.FirstList.name) }
            OnboardingFirstListScreen(
                listId = listId,
                onDone = {
                    nav.navigate(Routes.OnboardDone) {
                        popUpTo(Routes.OnboardWelcome) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onAddAnother = {
                    scope.launch {
                        val nextListId = createOnboardingFirstList(
                            listRepo = listRepo,
                            name = "",
                            memberContactIds = emptyList(),
                        )
                        nav.navigate(Routes.firstList(nextListId.toString())) {
                            popUpTo(Routes.OnboardFirstList) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        composable(Routes.OnboardDone) {
            OnboardingDoneScreen(
                onFinish = {
                    // A2 / ONB-23 — onboarding terminates on Home, clearing the
                    // entire back stack so a back press from Home doesn't
                    // re-enter onboarding.
                    nav.navigate(Routes.Home) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        // Picker routes (BULK-05 / BULK-06). The onboarding flow no longer
        // routes the picker through the nav graph (the in-flow first-list step
        // consumes the production picker via ListConfigViewModel inside
        // OnboardingFirstListScreen / ListConfigBody), so these blocks use the
        // standard non-onboarding shape.
        //
        // onCommit pops immediately; the commit write runs on the
        // app scope inside the VM and its outcome surfaces on the app-level
        // PickerCommitSnackbarHost mounted in OrbitNavHost above.
        composable(
            Routes.PickContacts,
            arguments = listOf(
                navArgument("targetListId") { type = NavType.StringType },
                navArgument("mode") {
                    type = NavType.StringType
                    defaultValue = "add"
                },
                // Required for mode=move only; nullable so the
                // add/copy routes keep their existing shape.
                navArgument("sourceListId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            ContactPickerScreen(
                onBack = { nav.popBackStack() },
                onCommit = { nav.popBackStack() },
                onSkip = null,
            )
        }
        composable(
            Routes.PickLists,
            arguments = listOf(navArgument("contactId") { type = NavType.StringType }),
        ) {
            ListPickerScreen(
                onBack = { nav.popBackStack() },
                onCommit = { nav.popBackStack() },
            )
        }
    }
}

/**
 * ONB-19 / ONB-09 — inline list-creation helper for the onboarding
 * nav graph. Creates a new STATIC list, then inserts one membership row
 * per provided contactId via [ListRepository.addMember].
 *
 * Field shape verified against `data/entity/ListEntity.kt` at HEAD:
 *   id, name, sortOrder, isArchived, type, smartRuleJson, ruleTemplateId,
 *   activeHoursStart, activeHoursEnd, notificationsEnabled,
 *   ruleParamsOverrideJson, dueCount.
 *
 * The user's typed name is set inside the OnboardingFirstListScreen via
 * [ListConfigViewModel.setName] — passing `name = ""` here is
 * intentional for the "Start blank" path; the H/β preview path passes
 * `defaultName = "In touch"` which the user can edit before tapping Done.
 *
 * Cadence default = round-robin — but the actual rule
 * template id is left null here so [ListConfigViewModel] applies the
 * template default lazily when the screen renders. The Cadence
 * SettingGroup defaults to KEEP_IN_TOUCH (id=1 per the seed) when the
 * user opens the picker; for Onboarding the default is fine since the
 * user can change it before tapping Done.
 *
 * Every membership insert calls [ListRepository.addMember]
 * directly. addMember uses `OnConflictStrategy.IGNORE`, so re-adds on a
 * brand-new list are idempotent no-ops.
 */
private suspend fun createOnboardingFirstList(
    listRepo: ListRepository,
    name: String,
    memberContactIds: List<Long>,
): Long {
    val list = ListEntity(
        id = 0L,                                    // auto-generated
        name = name,
        sortOrder = 0,                              // ListRepositoryImpl renumbers on create
        isArchived = false,
        type = ListType.STATIC,
        smartRuleJson = null,
        ruleTemplateId = null,                      // ListConfigViewModel applies its default on first read
        activeHoursStart = null,
        activeHoursEnd = null,
        notificationsEnabled = true,
        ruleParamsOverrideJson = null,
        dueCount = 0,
    )
    val newListId = listRepo.create(list)
    val now = Instant.now()
    memberContactIds.forEach { contactId ->
        listRepo.addMember(listId = newListId, contactId = contactId, addedAt = now)
    }
    // 2026-06-09 validation — fresh memberships have nextDueAt = null
    // (due immediately) but the denormalized ListEntity.dueCount stays 0 until
    // a choke-point recompute. Home's live header counts them while the tile
    // badge reads the stale column; recompute here so the first Home render is
    // consistent.
    listRepo.recomputeDueCountForList(newListId, now)
    return newListId
}
