package app.orbit.nav

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.orbit.data.AppPrefs
import app.orbit.data.repository.ListRepository
import app.orbit.ui.screens.onboarding.OnboardingListStarter
import app.orbit.ui.screens.onboarding.OnboardingStep
import app.orbit.ui.theme.LocalReducedMotion
import kotlinx.coroutines.launch

/**
 * Navigation graph: one [composable] block per route. The screens themselves
 * come through [OrbitNavScreens], one slot per route; the app passes
 * [OrbitNavScreens.Real], whose screens each resolve their own Hilt
 * ViewModel, and `OrbitNavHostTest` passes labelled stubs so the graph's
 * back-stack promises run on the JVM.
 *
 * Onboarding flow: Welcome, two permission asks (Contacts, then Call log),
 * Sync (blocking call-log gate), Preview (auto-skips when fewer than 3
 * candidates), FirstList (production List Configuration reused), Done, Home.
 * Nudges are asked for on Done, where the first list exists (ONB-30); the
 * notifications route survives only for installs that saved it as a resume
 * step. The single transactional `setOnboardingComplete(true)` write lives in
 * [OnboardingDoneViewModel.init]; the NavHost carries no duplicate write.
 *
 * The "Make this my first list" / "Start blank" / "Add another list" CTAs
 * go through [OnboardingListStarter], which creates the onboarding list at
 * navigate-time, or reuses the one already in progress so returning through
 * Sync never leaves a duplicate behind. The user's typed name is written by
 * [ListConfigViewModel.setName] inside the OnboardingFirstListScreen.
 *
 * @param listRepo ListRepository instance threaded from MainActivity (where
 *   Hilt resolves it via field injection). Handed to [OnboardingListStarter]
 *   for inline list-creation at navigate-time. Chosen over an injected
 *   bootstrapper to keep the diff minimal: one extra parameter on this
 *   composable + one `@Inject` on MainActivity.
 * @param navigateTo Optional route string from a nudge, a widget or a launcher
 *   shortcut ([AppLinks]). When non-null the [LaunchedEffect] inside this composable
 *   calls [nav.navigate] and then invokes [onNavigateToConsumed] to clear the value
 *   in [MainActivity] so a recomposition does not re-navigate. The route is a
 *   fully-formed path ("card/{listId}", "search") built from [Routes].
 *
 *   Security: nav.navigate only resolves against declared Routes. It throws for an
 *   unknown or malformed string; the effect below catches that, leaves the stack
 *   where it is and tells the user "Couldn't open that." through
 *   [OrbitNavScreens.UnknownRouteNotice] (T-10-21; rules.md Code 3). Orbit's own
 *   PendingIntents are FLAG_IMMUTABLE (T-10-20), but MainActivity is exported, so
 *   another app can still start it with any extra; the catch is what makes that
 *   harmless, and the notice is what keeps a broken route of Orbit's own (a widget,
 *   a nudge, a shortcut) from being an invisible no-op. (Until 2026-10-05 this said
 *   "a no-op", which was wrong: an unknown route crashed the app. Until 2026-10-06
 *   the catch was silent.)
 * @param onNavigateToConsumed Callback invoked after navigation so the Activity
 *   clears the navigateTo state and prevents re-navigation on recomposition.
 * @param screens The screen for each route. Defaults to the app's own.
 */
@Composable
fun OrbitNavHost(
    nav: NavHostController,
    listRepo: ListRepository,
    appPrefs: AppPrefs,
    startDestination: String = Routes.Home,
    navigateTo: String? = null,
    onNavigateToConsumed: () -> Unit = {},
    screens: OrbitNavScreens = OrbitNavScreens.Real,
) {
    // D-17: consume the NAVIGATE_TO extra from a notification tap.
    // Keyed on the value so it re-fires each time a new (non-null) destination
    // arrives (cold start or warm onNewIntent). After navigation, call
    // onNavigateToConsumed so the Activity clears the value, which prevents
    // re-navigation on config changes or recompositions.
    // Routes the graph refused, counted so the notice below can report each
    // one. `remember`, not rememberSaveable: MainActivity is recreated on
    // rotation, and a restored count would make the notice's keyed effect
    // announce the same failure again, while the route itself was consumed.
    var unknownRoutes by remember { mutableIntStateOf(0) }

    LaunchedEffect(navigateTo) {
        if (!navigateTo.isNullOrBlank()) {
            // A route for the screen already on top is left alone: a nudge for
            // the list whose deck is open must not stack a second deck, which
            // is the other half of AppLinks's SINGLE_TOP promise (the Activity
            // flags only stop a second Activity). A different list still gets
            // its own entry and ViewModel, which is why this is a comparison
            // and not launchSingleTop: the ViewModels read their ids once,
            // from SavedStateHandle, so reusing the entry would keep showing
            // the old list.
            if (nav.currentBackStackEntry?.shownRoute() != navigateTo) {
                // MainActivity is exported, so any app can hand it a route, and
                // Orbit's own widgets, nudges and shortcuts hand it theirs.
                // Navigation throws IllegalArgumentException for a route outside
                // the graph; its matcher is the one source of truth for what the
                // graph accepts, so there is no second check here. The catch
                // keeps the app where it is instead of crashing, and counts the
                // miss so UnknownRouteNotice tells the user (rules.md Code 3: a
                // dispatch that short-circuits surfaces, it never exits
                // quietly). Only that exception: anything else is a real bug
                // and must surface.
                try {
                    nav.navigate(navigateTo)
                } catch (_: IllegalArgumentException) {
                    unknownRoutes++
                }
            }
            onNavigateToConsumed()
        }
    }

    // Picker-commit lifecycle: the pickers pop on commit, so their
    // result snackbar ("Added N · Undo" / "Couldn't save that") must outlive
    // the picker's own composition. The host collects the app-lifetime
    // PickerCommitBus and renders above whatever screen the pop lands on;
    // the unknown-route notice publishes "Couldn't open that." on the same
    // bus. The graph itself is split into [OrbitNavGraph] so the overlay Box
    // doesn't re-indent every route.
    Box(modifier = Modifier.fillMaxSize()) {
        OrbitNavGraph(
            nav = nav,
            listRepo = listRepo,
            appPrefs = appPrefs,
            startDestination = startDestination,
            screens = screens,
        )
        screens.CommitSnackbarHost(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
        )
        screens.UnknownRouteNotice(occurrences = unknownRoutes)
    }
}

/**
 * The route the top entry is showing, as a navigable string: its route
 * pattern with the arguments filled back in, a query argument that is null
 * left out, so `card/{listId}` showing list 3 reads `card/3` and matches what
 * [Routes.card] builds. Used by the deep-link guard above to recognise "the
 * screen already on top"; a route it cannot reproduce exactly simply
 * navigates as before, so a mismatch is never worse than the old behaviour.
 */
internal fun NavBackStackEntry.shownRoute(): String? {
    val pattern = destination.route ?: return null
    val args = arguments
    val path = PLACEHOLDER.replace(pattern.substringBefore('?')) { m ->
        args.valueOf(m.groupValues[1]) ?: m.value
    }
    val query = pattern.substringAfter('?', missingDelimiterValue = "")
        .split('&')
        .filter { it.isNotEmpty() }
        .mapNotNull { pair ->
            val key = pair.substringBefore('=')
            val raw = pair.substringAfter('=', missingDelimiterValue = "")
            val placeholder = PLACEHOLDER.matchEntire(raw)?.groupValues?.get(1)
            val value = if (placeholder == null) raw else args.valueOf(placeholder) ?: return@mapNotNull null
            "$key=$value"
        }
        .joinToString("&")
    return if (query.isEmpty()) path else "$path?$query"
}

private val PLACEHOLDER = Regex("\\{([^}]+)\\}")

// Bundle.get is deprecated in favour of typed getters, but the type here is
// whatever the route declared (String, Bool, Long), and all of them print the
// way Routes writes them.
@Suppress("DEPRECATION")
private fun Bundle?.valueOf(name: String): String? = this?.get(name)?.toString()

/**
 * A path argument the route pattern requires. Navigation only matches the
 * route when it is present, so its absence is a programming error (a route
 * built by hand instead of through [Routes]) and fails loudly here rather than
 * opening the screen on an invented id and dressing the bug up as "Couldn't
 * load" (rules.md Code 3). Until 2026-10-06 these fell back to preview fixture
 * ids ("inner", "c-sarah").
 */
private fun NavBackStackEntry.requiredString(name: String): String =
    requireNotNull(arguments?.getString(name)) { "$name missing on ${destination.route}" }

@Composable
private fun OrbitNavGraph(
    nav: NavHostController,
    listRepo: ListRepository,
    appPrefs: AppPrefs,
    startDestination: String,
    screens: OrbitNavScreens,
) {
    val onboardingLists = remember(listRepo, appPrefs) { OnboardingListStarter(listRepo, appPrefs) }
    val reducedMotion = LocalReducedMotion.current
    val motion = remember(reducedMotion) { OrbitNavMotion(reducedMotion) }
    val openContact: (String) -> Unit = { contactId -> nav.navigate(Routes.contact(contactId)) }
    val openSettings: () -> Unit = { nav.navigate(Routes.Settings) }
    NavHost(
        navController = nav,
        startDestination = startDestination,
        enterTransition = motion.enter,
        exitTransition = motion.exit,
        popEnterTransition = motion.popEnter,
        popExitTransition = motion.popExit,
    ) {
        composable(Routes.Home) {
            screens.Home(
                onOpenList = { listId -> nav.navigate(Routes.card(listId)) },
                onOpenSearch = { nav.navigate(Routes.GlobalSearch) },
                onOpenSettings = openSettings,
                onOpenLists = { nav.navigate(Routes.lists()) },
                onCreateList = { nav.navigate(Routes.lists(openCreate = true)) },
                // Long-press quick-actions: navigation legs (add people, list settings).
                onAddPeopleToList = { listId -> nav.navigate(Routes.pickContacts(listId)) },
                onOpenListSettings = { listId -> nav.navigate(Routes.listConfig(listId)) },
                // NOTE-02: PostCallBanner "Add a note" tap routes to
                // ContactDetail with focusNote=true so the Notes input claims
                // focus once the screen settles.
                onOpenContactWithFocus = { id, focus ->
                    nav.navigate(Routes.contactWithFocus(id, focus))
                }
            )
        }
        composable(
            Routes.Card,
            arguments = listOf(navArgument("listId") { type = NavType.StringType })
        ) { entry ->
            screens.Card(
                listId = entry.requiredString("listId"),
                onBack = { nav.popBackStack() },
                onOpenContact = openContact,
                // CARD-03 / NOTE-02: "Add a note" lands in the note field, the
                // same as Home's "Add a note"; a plain tap on the face opens
                // the person at the top.
                onAddNote = { contactId -> nav.navigate(Routes.contactWithFocus(contactId, focusNote = true)) },
                onBrowse = { listId -> nav.navigate(Routes.browse(listId)) },
                onEditList = { listId -> nav.navigate(Routes.listConfig(listId)) },
                onAddContacts = { listId -> nav.navigate(Routes.pickContacts(listId)) },
                // 2026-06-09: the call-log-denied notice deep-links to Settings,
                // where the permission row hosts the grant flow.
                onOpenSettings = openSettings
            )
        }
        composable(
            Routes.Browse,
            arguments = listOf(navArgument("listId") { type = NavType.StringType })
        ) { entry ->
            screens.Browse(
                listId = entry.requiredString("listId"),
                onBack = { nav.popBackStack() },
                onOpenContact = openContact,
                onAddContacts = { listId -> nav.navigate(Routes.pickContacts(listId)) },
                // Browse's call-log-denied state and notice offer the same
                // "Open settings" as Card view and Call history (voice.md).
                onOpenSettings = openSettings
            )
        }
        composable(Routes.GlobalSearch) {
            screens.Search(
                onBack = { nav.popBackStack() },
                onOpenContact = openContact,
                // "Add to lists" routes to the existing list picker; the
                // VM-side commit surfaces on the app-level snackbar host,
                // so navigation alone is enough. The picker VM
                // strips the "c-" prefix from the UI contact id.
                onAddToLists = { contactId -> nav.navigate(Routes.pickLists(contactId)) },
                onOpenSettings = openSettings
            )
        }
        composable(
            Routes.Contact,
            arguments = listOf(
                navArgument("contactId") { type = NavType.StringType },
                // NOTE-02 / LOG-03: optional StringType args (default
                // null). The ContactDetailViewModel reads them via
                // SavedStateHandle and translates: "1" means focus the Notes
                // input, a parsed Long means scroll to the matching call event row.
                navArgument("focusNote") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("scrollToCallEventId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { entry ->
            val contactIdArg = entry.requiredString("contactId")
            screens.Contact(
                contactId = contactIdArg,
                onBack = { nav.popBackStack() },
                onAddToLists = { contactId -> nav.navigate(Routes.pickLists(contactId)) },
                // CONTACT-06 / CONTACT-07: Re-link opens the picker in Relink
                // mode for this orphan; the commit merges and pops back here.
                onRelink = { cid -> nav.navigate(Routes.relinkContact(cid.toString())) },
                // LOG-04: "View all calls" means this person's calls; back
                // pops to this screen.
                onViewAllCalls = { nav.navigate(Routes.callLogFor(contactIdArg)) },
                // The call-log notice's "Open settings" leads to Orbit's own
                // Settings, as on Card view and Call history.
                onOpenSettings = openSettings
            )
        }
        composable(
            Routes.Lists,
            arguments = listOf(
                navArgument("openCreate") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { entry ->
            screens.Lists(
                onBack = { nav.popBackStack() },
                // LIST-23: tapping a list opens its deck, as on Home. List
                // settings is one step away in the row's menu, and is where
                // the archived row's settings icon and a just-created list
                // land; until 2026-10-06 all three opened the deck.
                onOpenList = { listId -> nav.navigate(Routes.card(listId)) },
                onOpenListSettings = { listId -> nav.navigate(Routes.listConfig(listId)) },
                onAddContacts = { listId -> nav.navigate(Routes.pickContacts(listId)) },
                openCreateOnLaunch = entry.arguments?.getBoolean("openCreate") == true
            )
        }
        composable(
            Routes.ListConfig,
            arguments = listOf(navArgument("listId") { type = NavType.StringType })
        ) { entry ->
            screens.ListConfig(
                listId = entry.requiredString("listId"),
                onBack = { nav.popBackStack() },
                onSave = { nav.popBackStack() },
                onAddContacts = { lid -> nav.navigate(Routes.pickContacts(lid)) }
            )
        }
        composable(Routes.Settings) {
            screens.Settings(
                onBack = { nav.popBackStack() },
                onOpenIgnored = { nav.navigate(Routes.SettingsIgnored) },
                onOpenCallHistory = { nav.navigate(Routes.CallLog) }
            )
        }
        // IGNORE-06: Settings > Ignored full nav destination.
        composable(Routes.SettingsIgnored) {
            screens.SettingsIgnored(onBack = { nav.popBackStack() })
        }
        // LOG-01: chronological in-app call log. LOG-04: optionally one
        // person's (the CallLogViewModel reads `contactId` from SavedStateHandle).
        composable(
            Routes.CallLogPattern,
            arguments = listOf(
                navArgument("contactId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) {
            screens.CallLog(
                onBack = { nav.popBackStack() },
                // LOG-05: the denied state hands off to Settings, which owns
                // the grant and the resync it needs (Card view precedent).
                // Opened from Settings, it goes back rather than stacking a
                // second Settings on top.
                onOpenSettings = {
                    if (nav.previousBackStackEntry?.destination?.route == Routes.Settings) {
                        nav.popBackStack()
                    } else {
                        nav.navigate(Routes.Settings)
                    }
                },
                onOpenContact = { contactId, callEventId ->
                    // I2: named-arg `focusNote = true` for clarity; the
                    // helper produces "contact/{id}?focusNote=1&scrollToCallEventId={...}"
                    // which the ContactDetailViewModel parses via SavedStateHandle.
                    nav.navigate(
                        Routes.contactWithFocus(
                            contactId = contactId.toString(),
                            focusNote = true,
                            scrollToCallEventId = callEventId
                        )
                    )
                }
            )
        }

        // Onboarding flow. Welcome, Contacts perm, Call log perm, Sync
        // (blocking call-log gate), Preview (auto-skips when fewer than 3
        // candidates), FirstList (production List Configuration reused),
        // Done, Home. Notifications are asked for on Done (ONB-30), not as a
        // step. Permission order is fixed: most-impactful permission first so
        // a half-bail still leaves Orbit functional. Sync is non-skippable.
        // First-list creation is required for activation.
        //
        // A resumed step (AppViewModel.resolveOnboardingResume makes it the
        // start destination) is the first entry on the stack, so it gets no
        // back arrow: popBackStack() on a one-entry stack leaves the NavHost
        // blank, and no control beats a tap that empties the screen
        // (rules.md Code 3, G4).
        composable(Routes.OnboardWelcome) {
            screens.OnboardWelcome(
                onContinue = { nav.navigate(Routes.OnboardPermContacts) }
            )
        }
        composable(Routes.OnboardPermContacts) {
            // Persist the step on entry so a mid-flow crash returns the user
            // here on re-launch (read by AppViewModel.resolveOnboardingResume).
            LaunchedEffect(
                Unit
            ) { appPrefs.setLastOnboardingStep(OnboardingStep.PermContacts.name) }
            screens.OnboardPermContacts(
                onBack = nav.backOrNull(),
                onContinue = { nav.navigate(Routes.OnboardPermCallLog) }
            )
        }
        composable(Routes.OnboardPermCallLog) {
            LaunchedEffect(Unit) { appPrefs.setLastOnboardingStep(OnboardingStep.PermCallLog.name) }
            // ONB-30: notifications are no longer asked up front. Three
            // permission screens stood between Welcome and the user's own
            // people; nudges are asked for on the Done screen, where the
            // first list (and what a nudge is) now exists.
            screens.OnboardPermCallLog(
                onBack = nav.backOrNull(),
                onContinue = { nav.navigate(Routes.OnboardSync) }
            )
        }
        // Kept for installs that saved this step before ONB-30, so a resume
        // still lands somewhere real; nothing navigates here any more.
        composable(Routes.OnboardPermNotifs) {
            LaunchedEffect(
                Unit
            ) { appPrefs.setLastOnboardingStep(OnboardingStep.PermNotifications.name) }
            screens.OnboardPermNotifications(
                onBack = nav.backOrNull(),
                onContinue = { nav.navigate(Routes.OnboardSync) }
            )
        }
        composable(Routes.OnboardSync) {
            LaunchedEffect(Unit) { appPrefs.setLastOnboardingStep(OnboardingStep.Sync.name) }
            val scope = rememberCoroutineScope()
            screens.OnboardSync(
                onContinue = {
                    scope.launch {
                        // Back from the first-list step, or a cold-start
                        // resume from it, lands here: continue into the list
                        // already being built instead of offering Preview
                        // again (which would make a second one).
                        val pending = onboardingLists.pendingListId()
                        if (pending != null) {
                            nav.navigate(Routes.firstList(pending.toString())) {
                                popUpTo(Routes.OnboardSync) { inclusive = false }
                                launchSingleTop = true
                            }
                        } else {
                            nav.navigate(Routes.OnboardPreview)
                        }
                    }
                }
            )
        }
        composable(Routes.OnboardPreview) {
            // The preview auto-skips when fewer than 3 candidates match, so
            // onSkip routes the user to a freshly-created empty list. The
            // "Make this my first list" path creates a list pre-populated with
            // the candidate contacts; both paths land on OnboardFirstList(listId).
            val scope = rememberCoroutineScope()
            screens.OnboardPreview(
                onAccept = { defaultName, contactIds ->
                    scope.launch {
                        val newListId = onboardingLists.startOrResume(
                            defaultName = defaultName,
                            memberContactIds = contactIds
                        )
                        nav.navigate(Routes.firstList(newListId.toString())) {
                            popUpTo(Routes.OnboardSync) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                onSkip = {
                    scope.launch {
                        val newListId = onboardingLists.startOrResume(
                            defaultName = "",
                            memberContactIds = emptyList()
                        )
                        nav.navigate(Routes.firstList(newListId.toString())) {
                            popUpTo(Routes.OnboardSync) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                }
            )
        }
        composable(
            Routes.OnboardFirstList,
            arguments = listOf(navArgument("listId") { type = NavType.StringType })
        ) { entry ->
            val listId = entry.requiredString("listId")
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { appPrefs.setLastOnboardingStep(OnboardingStep.FirstList.name) }
            screens.OnboardFirstList(
                listId = listId,
                onDone = {
                    nav.navigate(Routes.OnboardDone) {
                        popUpTo(Routes.OnboardWelcome) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onAddAnother = {
                    scope.launch {
                        val nextListId = onboardingLists.startAnother()
                        nav.navigate(Routes.firstList(nextListId.toString())) {
                            popUpTo(Routes.OnboardFirstList) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
                onAddContacts = { nav.navigate(Routes.pickContacts(listId)) },
                // NotFound's "Start again": back to the Sync step, which sits
                // directly below (every route into first-list pops to it), so
                // its Continue sets up a fresh list.
                onStartAgain = { nav.popBackStack(Routes.OnboardSync, inclusive = false) }
            )
        }
        composable(Routes.OnboardDone) {
            screens.OnboardDone(
                onFinish = {
                    // A2 / ONB-23: onboarding terminates on Home, clearing the
                    // entire back stack so a back press from Home doesn't
                    // re-enter onboarding.
                    nav.navigate(Routes.Home) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        // Picker routes (BULK-05 / BULK-06). Onboarding's "Add people" (the
        // first-list step, through ListConfigBody) opens this same standard
        // Add picker; the first-list step owns skipping and its gating, so
        // the picker has no onboarding shape of its own. Until 2026-10-06 a
        // comment here described an in-flow picker that did not exist and the
        // call site passed a dead `onSkip = null`.
        //
        // onCommit pops immediately; the commit write runs on the
        // app scope inside the VM and its outcome surfaces on the app-level
        // commit snackbar host mounted in OrbitNavHost above.
        composable(
            Routes.PickContacts,
            arguments = listOf(
                // Nullable because a Relink route carries a contact instead
                // (relinkContactId below). A list mode without it still lands
                // on the picker's NotFound state rather than crashing here.
                navArgument("targetListId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
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
                // Required for mode=relink only (CONTACT-07).
                navArgument("relinkContactId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) {
            screens.PickContacts(
                onBack = { nav.popBackStack() },
                onCommit = { nav.popBackStack() }
            )
        }
        composable(
            Routes.PickLists,
            arguments = listOf(navArgument("contactId") { type = NavType.StringType })
        ) {
            screens.PickLists(
                onBack = { nav.popBackStack() },
                onCommit = { nav.popBackStack() }
            )
        }
    }
}

/**
 * A back leg when there is somewhere to go back to, null when this is the
 * first entry on the stack (a resumed onboarding step), so the screen hides
 * its arrow instead of offering a tap that would empty the NavHost.
 */
private fun NavHostController.backOrNull(): (() -> Unit)? =
    if (previousBackStackEntry == null) null else { { popBackStack() } }
