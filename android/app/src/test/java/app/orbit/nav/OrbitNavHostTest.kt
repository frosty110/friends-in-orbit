package app.orbit.nav

import android.app.Application
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.navigation.NavGraph
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.data.AppPrefs
import app.orbit.domain.FakeListRepository
import app.orbit.domain.listFixture
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The navigation graph's back-stack promises, on the JVM: [OrbitNavHost] over
 * a [TestNavHostController], with [StubScreens] in every slot so no
 * ViewModel and no Hilt graph is created. Each stub draws its route and
 * records the callbacks the graph handed it, and a test fires those the way
 * a tap would, then reads the stack.
 *
 * Until 2026-10-06 none of these were pinned: `RoutesTest` checked the path
 * strings and `AppLinksTest` the intents, but popUpTo, the pop-or-push on
 * Call history and the deep-link guard lived only in graph code.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class OrbitNavHostTest {

    @get:Rule val compose = createComposeRule()

    /**
     * Not `tmp.newPrefs(storeScope)` (testutil/TestDataStore.kt), on purpose.
     * The compose rule's dispatcher is unconfined, so a continuation resumes
     * on whatever thread completes it; a real DataStore completes its reads
     * on its own IO scope, and the Sync step's `pendingListId()` read would
     * hand the navigate that follows it to that thread, where NavController
     * refuses to run (an entry's lifecycle is main-thread only). In the app
     * the Recomposer runs on AndroidUiDispatcher.Main, which is confined, so
     * the real store is fine there. This test is about the graph and needs
     * no persistence: a store over a StateFlow never leaves the main thread.
     */
    private val prefs = AppPrefs(MemoryDataStore())
    private val lists = FakeListRepository(initialLists = listOf(listFixture(id = 5L, name = "In touch")))
    private val screens = StubScreens()
    private lateinit var nav: TestNavHostController

    /** What MainActivity would hand the host from a nudge, widget or shortcut. */
    private var deepLink by mutableStateOf<String?>(null)
    private var consumed = 0

    private fun start(destination: String) {
        nav = TestNavHostController(ApplicationProvider.getApplicationContext()).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
        }
        compose.setContent {
            OrbitNavHost(
                nav = nav,
                listRepo = lists,
                appPrefs = prefs,
                startDestination = destination,
                navigateTo = deepLink,
                onNavigateToConsumed = {
                    consumed++
                    deepLink = null
                },
                screens = screens,
            )
        }
        compose.waitForIdle()
    }

    /** Runs [block] as a tap would, on the main thread, and lets the graph settle. */
    private fun act(block: () -> Unit) {
        compose.runOnUiThread(block)
        compose.waitForIdle()
    }

    private fun navigate(route: String) = act { nav.navigate(route) }

    private val route: String? get() = nav.currentBackStackEntry?.destination?.route
    private val previousRoute: String? get() = nav.previousBackStackEntry?.destination?.route
    private fun arg(name: String): String? = nav.currentBackStackEntry?.arguments?.getString(name)

    /** Screens on the stack; the graph's own root entry is not a screen. */
    private val depth: Int get() = nav.currentBackStack.value.count { it.destination !is NavGraph }

    private fun awaitRoute(expected: String) {
        compose.waitUntil(timeoutMillis = 10_000L) { route == expected }
    }

    // ONB-23

    @Test
    fun done_landsOnHome_withNothingToGoBackTo() {
        start(Routes.OnboardWelcome)
        navigate(Routes.OnboardPermContacts)
        navigate(Routes.OnboardDone)
        assertEquals(3, depth)

        act { screens.doneOnFinish() }

        assertEquals(Routes.Home, route)
        assertNull(nav.previousBackStackEntry, "back from Home must leave the app, never re-enter onboarding")
        assertEquals(1, depth)
    }

    // The resumed permission step (onb-1)

    @Test
    fun aResumedPermissionStep_hasNoBackArrow() {
        // AppViewModel.resolveOnboardingResume makes the saved step the start
        // destination, so it is the only entry on the stack.
        start(Routes.OnboardPermCallLog)

        assertTrue(screens.permCallLogShown)
        assertNull(screens.permCallLogOnBack, "popBackStack() on a one-entry stack would leave the NavHost blank")
    }

    @Test
    fun aPermissionStep_reachedFromWelcome_goesBackToWelcome() {
        start(Routes.OnboardWelcome)
        act { screens.welcomeOnContinue() }
        assertEquals(Routes.OnboardPermContacts, route)
        val back = assertNotNull(screens.permContactsOnBack)

        act { back() }

        assertEquals(Routes.OnboardWelcome, route)
    }

    // The sync gate and the first-list step

    @Test
    fun syncContinue_withAPendingList_opensItStraightAway_withoutPreview() {
        runBlocking { prefs.setOnboardingListId(5L) }
        start(Routes.OnboardSync)

        act { screens.syncOnContinue() }
        awaitRoute(Routes.OnboardFirstList)

        assertEquals("5", arg("listId"))
        assertEquals(Routes.OnboardSync, previousRoute, "no Preview between Sync and the list being built")
        assertEquals(2, depth)
    }

    @Test
    fun syncContinue_withNoPendingList_offersPreview_andBackFromTheFirstListLandsOnSync() {
        start(Routes.OnboardSync)

        act { screens.syncOnContinue() }
        awaitRoute(Routes.OnboardPreview)
        act { screens.previewOnSkip() }
        awaitRoute(Routes.OnboardFirstList)

        assertEquals(Routes.OnboardSync, previousRoute, "Preview is popped so back does not offer it twice")
        act { nav.popBackStack() }
        assertEquals(Routes.OnboardSync, route)
    }

    @Test
    fun startAgain_fromTheFirstListStep_returnsToSync() {
        start(Routes.OnboardSync)
        navigate(Routes.firstList("5"))

        act { screens.firstListOnStartAgain() }

        assertEquals(Routes.OnboardSync, route)
        assertEquals(1, depth)
    }

    // LOG-05: Call history's denied state hands off to Settings

    @Test
    fun callHistoryOpenSettings_popsBack_whenItCameFromSettings() {
        start(Routes.Home)
        navigate(Routes.Settings)
        navigate(Routes.CallLog)

        act { screens.callLogOnOpenSettings() }

        assertEquals(Routes.Settings, route)
        assertEquals(Routes.Home, previousRoute, "no second Settings stacked on the first")
        assertEquals(2, depth)
    }

    @Test
    fun callHistoryOpenSettings_pushesSettings_whenItCameFromAPerson() {
        start(Routes.Home)
        navigate(Routes.contact("c-7"))
        act { screens.contactOnViewAllCalls() }
        assertEquals(Routes.CallLogPattern, route)

        act { screens.callLogOnOpenSettings() }

        assertEquals(Routes.Settings, route)
        assertEquals(Routes.CallLogPattern, previousRoute)
    }

    // LOG-04: "View all calls" means this person's calls, and back returns to them

    @Test
    fun viewAllCalls_thenBack_landsOnTheSameContactEntry() {
        start(Routes.Home)
        navigate(Routes.contact("c-7"))
        val person = assertNotNull(nav.currentBackStackEntry)

        act { screens.contactOnViewAllCalls() }
        assertEquals(Routes.CallLogPattern, route)
        assertEquals("c-7", arg("contactId"))

        act { nav.popBackStack() }
        assertSame(person, nav.currentBackStackEntry, "the same entry, so the same ViewModel and scroll position")
    }

    // LIST-23

    @Test
    fun lists_rowTapOpensTheDeck_andListSettingsOpensListSettings() {
        start(Routes.Home)
        navigate(Routes.lists())

        act { screens.listsOnOpenList("5") }
        assertEquals(Routes.Card, route)
        assertEquals("5", arg("listId"))

        act { nav.popBackStack() }
        act { screens.listsOnOpenListSettings("5") }
        assertEquals(Routes.ListConfig, route)
        assertEquals("5", arg("listId"))
    }

    // LIST-28: New list is a screen of its own, opened from Home and Lists,
    // and Create (or leaving) returns to whichever opened it.

    @Test
    fun homeNewList_opensTheFlow_andLeavingReturnsHome() {
        start(Routes.Home)

        act { screens.homeOnCreateList() }

        assertEquals(Routes.NewList, route)
        assertEquals(Routes.Home, previousRoute)

        act { screens.newListOnLeave() }
        assertEquals(Routes.Home, route)
        assertEquals(1, depth)
    }

    @Test
    fun listsNewList_opensTheFlow_andLeavingReturnsToLists() {
        start(Routes.Home)
        navigate(Routes.lists())

        act { screens.listsOnCreateList() }

        assertEquals(Routes.NewList, route)
        assertEquals(Routes.Lists, previousRoute)

        act { screens.newListOnLeave() }
        assertEquals(Routes.Lists, route)
    }

    @Test
    fun theFlow_leavesOnce_evenWhenAskedTwice() {
        start(Routes.Home)
        navigate(Routes.lists())
        navigate(Routes.NewList)

        // Create landing as Back is pressed: the second must not pop Lists.
        act {
            screens.newListOnLeave()
            screens.newListOnLeave()
        }

        assertEquals(Routes.Lists, route)
    }

    @Test
    fun theOldOpenCreateRoute_opensTheFlowOverLists_once() {
        start(Routes.Home)

        // What a NAVIGATE_TO written before the flow existed would hand over.
        act { deepLink = Routes.lists(openCreate = true) }
        awaitRoute(Routes.NewList)

        assertEquals(Routes.Lists, previousRoute, "the flow opens over Lists, as the sheet did")
        assertEquals(1, consumed)

        act { screens.newListOnLeave() }
        compose.waitForIdle()
        assertEquals(Routes.Lists, route, "coming back to Lists does not open the flow again")
        assertEquals(2, depth)
    }

    @Test
    fun addPeople_opensThePickerToCollect_andItsSelectionComesBackToTheFlow() {
        start(Routes.Home)
        navigate(Routes.NewList)
        val flow = assertNotNull(nav.currentBackStackEntry)

        act { screens.newListOnChoosePeople(listOf(3L, 7L)) }

        assertEquals(Routes.PickContacts, route)
        assertEquals("collect", arg("mode"))
        assertEquals("3,7", arg("selected"), "the picker opens with who is already chosen")
        assertNull(arg("targetListId"), "there is no list yet")

        act { screens.pickContactsOnCollect(listOf(3L, 9L)) }

        assertSame(flow, nav.currentBackStackEntry, "back on the same flow, with everything entered")
        assertEquals(listOf(3L, 9L), screens.newListChosenPeople)

        act { screens.newListOnChosenPeopleTaken() }
        assertNull(screens.newListChosenPeople, "handed over once")
    }

    @Test
    fun backFromTheCollectPicker_changesNothing() {
        start(Routes.Home)
        navigate(Routes.NewList)
        act { screens.newListOnChoosePeople(emptyList()) }
        assertEquals(Routes.PickContacts, route)
        assertNull(arg("selected"))

        act { nav.popBackStack() }

        assertEquals(Routes.NewList, route)
        assertNull(screens.newListChosenPeople)
    }

    // BROWSE-09

    @Test
    fun cardBrowsePeople_opensBrowse_onTheCardsPerson() {
        start(Routes.Home)
        navigate(Routes.card("3"))

        act { screens.cardOnBrowse("3", 42L) }

        assertEquals(Routes.Browse, route)
        assertEquals("3", arg("listId"))
        assertEquals("42", arg("focus"), "Browse reads the card's person from the focus argument")
    }

    @Test
    fun cardBrowseThisList_opensBrowse_withNoOneToFocus() {
        start(Routes.Home)
        navigate(Routes.card("3"))

        act { screens.cardOnBrowse("3", null) }

        assertEquals(Routes.Browse, route)
        assertEquals("3", arg("listId"))
        assertNull(arg("focus"))
    }

    // CARD-03 / NOTE-04: the card's "Add a note" opens the page for writing
    // about the call. Until 2026-10-07 it opened the person with the note
    // field focused (NOTE-02).

    @Test
    fun cardAddANote_opensTheNotePage_forThatPerson() {
        start(Routes.Home)
        navigate(Routes.card("3"))

        act { screens.cardOnAddNote("c-7", null) }

        assertEquals(Routes.PostCallNote, route)
        assertEquals("c-7", arg("contactId"))
        // The card knows who was called, not which call: the page finds it.
        assertNull(arg("callEventId"))
        assertEquals("c-7", screens.noteShownFor?.first)
    }

    // CARD-11: after a call worth a note the card opens the page for that call
    // by itself, and leaving the page lands back on the deck.

    @Test
    fun cardAfterACallWorthANote_opensTheNotePage_forThatCall_andLeavingReturnsToTheDeck() {
        start(Routes.Home)
        navigate(Routes.card("3"))

        act { screens.cardOnAddNote("c-7", 41L) }

        assertEquals(Routes.PostCallNote, route)
        assertEquals("c-7", arg("contactId"))
        assertEquals("41", arg("callEventId"))
        assertEquals("c-7" to "41", screens.noteShownFor)

        act { screens.noteOnLeave() }
        assertEquals(Routes.Card, route)
        assertEquals("3", arg("listId"))
    }

    // HOME-14 / NOTE-04

    @Test
    fun homeAddANote_opensTheNotePage_forThatCall_andLeavingReturnsHome() {
        start(Routes.Home)

        act { screens.homeOnOpenPostCallNote("7", 41L) }

        assertEquals(Routes.PostCallNote, route)
        assertEquals("7", arg("contactId"))
        assertEquals("41", arg("callEventId"))
        assertEquals("7" to "41", screens.noteShownFor)

        act { screens.noteOnLeave() }
        assertEquals(Routes.Home, route)
    }

    @Test
    fun theNotePage_leavesOnce_evenWhenAskedTwice() {
        start(Routes.Home)
        navigate(Routes.card("3"))
        navigate(Routes.postCallNote("7", 41L))

        // "Not now" tapped twice, or a save landing as it is tapped: the
        // second must not pop the deck under the page.
        act {
            screens.noteOnLeave()
            screens.noteOnLeave()
        }

        assertEquals(Routes.Card, route)
    }

    @Test
    fun theNotification_opensTheNotePage_overHome() {
        start(Routes.Home)

        // What NOTIF-16's tap hands MainActivity, through NAVIGATE_TO.
        act { deepLink = Routes.postCallNote("7", 41L) }

        assertEquals(Routes.PostCallNote, route)
        assertEquals("41", arg("callEventId"))
        assertEquals(Routes.Home, previousRoute)
    }

    @Test
    fun cardOpenDetails_opensThePerson_atTheTop() {
        start(Routes.Home)
        navigate(Routes.card("3"))

        act { screens.cardOnOpenContact("c-7") }

        assertEquals(Routes.Contact, route)
        assertEquals("c-7", arg("contactId"))
        assertNull(arg("focusNote"))
    }

    // HOME-13: the strip's "See your week" and the day sheet's "See the
    // whole week" open the list's Week screen; Back returns Home, and a
    // block opens the person at the top.

    @Test
    fun homeSeeYourWeek_opensTheWeek_forThatList_andBackReturnsHome() {
        start(Routes.Home)

        act { screens.homeOnOpenWeek("5") }

        assertEquals(Routes.Week, route)
        assertEquals("5", arg("listId"))
        assertEquals(Routes.Home, previousRoute)

        act { screens.weekOnBack() }
        assertEquals(Routes.Home, route)
        assertEquals(1, depth)
    }

    @Test
    fun theWeek_opensAPerson_atTheTop() {
        start(Routes.Home)
        navigate(Routes.week("5"))

        act { screens.weekOnOpenContact("7") }

        assertEquals(Routes.Contact, route)
        assertEquals("7", arg("contactId"))
        assertNull(arg("focusNote"))
        assertEquals(Routes.Week, previousRoute)
    }

    // Open settings from the call-log notices

    @Test
    fun openSettings_fromBrowseSearchAndAPerson_leadsToSettings() {
        start(Routes.Home)
        navigate(Routes.browse("3"))
        act { screens.browseOnOpenSettings() }
        assertEquals(Routes.Settings, route)

        act { nav.popBackStack() }
        navigate(Routes.GlobalSearch)
        act { screens.searchOnOpenSettings() }
        assertEquals(Routes.Settings, route)

        act { nav.popBackStack() }
        navigate(Routes.contact("c-7"))
        act { screens.contactOnOpenSettings() }
        assertEquals(Routes.Settings, route)
    }

    // Routes from outside (D-17, T-10-21)

    @Test
    fun anUnknownDeepLink_leavesTheStackAlone_isConsumed_andIsReported() {
        start(Routes.Home)
        assertEquals(0, screens.unknownRoutesReported, "nothing to report before a route arrives")

        act { deepLink = "nope/1" }

        assertEquals(Routes.Home, route)
        assertEquals(1, depth)
        assertEquals(1, consumed)
        assertNull(deepLink)
        // rules.md Code 3: the route is ignored for navigation, never silently.
        assertEquals(1, screens.unknownRoutesReported, "the user is told it could not be opened")
    }

    @Test
    fun aDeepLinkForTheDeckAlreadyOnTop_doesNotStackASecondCopy() {
        start(Routes.Home)
        navigate(Routes.card("3"))
        assertEquals(2, depth)

        act { deepLink = Routes.card("3") }

        assertEquals(2, depth)
        assertEquals(Routes.Card, route)
        assertEquals("3", arg("listId"))
        assertEquals(1, consumed)
    }

    @Test
    fun aDeepLinkForAnotherList_getsItsOwnEntry() {
        start(Routes.Home)
        navigate(Routes.card("3"))

        act { deepLink = Routes.card("4") }

        assertEquals(3, depth)
        assertEquals("4", arg("listId"))
        assertEquals("3", nav.previousBackStackEntry?.arguments?.getString("listId"))
        assertEquals(1, consumed)
        assertEquals(0, screens.unknownRoutesReported, "a route the graph opened is not reported")
    }

    @Test
    fun aDeepLinkToSearch_whileSearchIsOpen_doesNotStackASecondCopy() {
        start(Routes.Home)
        navigate(Routes.GlobalSearch)

        act { deepLink = Routes.GlobalSearch }

        assertEquals(2, depth)
        assertEquals(Routes.GlobalSearch, route)
    }
}

/** A DataStore<Preferences> over a StateFlow; see [OrbitNavHostTest.prefs]. */
private class MemoryDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> get() = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val next = transform(state.value)
        state.value = next
        return next
    }
}

/**
 * One labelled box per route. Each slot keeps the callbacks the graph passed
 * so a test can fire them; nothing here has a ViewModel.
 */
private class StubScreens : OrbitNavScreens {

    lateinit var welcomeOnContinue: () -> Unit
    var permContactsOnBack: (() -> Unit)? = null
    var permCallLogShown = false
    var permCallLogOnBack: (() -> Unit)? = null
    lateinit var syncOnContinue: () -> Unit
    lateinit var previewOnSkip: () -> Unit
    lateinit var firstListOnStartAgain: () -> Unit
    lateinit var doneOnFinish: () -> Unit
    lateinit var cardOnOpenContact: (String) -> Unit
    lateinit var cardOnAddNote: (String, Long?) -> Unit
    lateinit var homeOnOpenPostCallNote: (String, Long) -> Unit
    lateinit var homeOnOpenWeek: (String) -> Unit
    lateinit var weekOnBack: () -> Unit
    lateinit var weekOnOpenContact: (String) -> Unit
    lateinit var noteOnLeave: () -> Unit

    /** The person and call the note page was last composed for. */
    var noteShownFor: Pair<String, String?>? = null
    lateinit var cardOnBrowse: (String, Long?) -> Unit
    lateinit var browseOnOpenSettings: () -> Unit
    lateinit var searchOnOpenSettings: () -> Unit
    lateinit var contactOnViewAllCalls: () -> Unit
    lateinit var contactOnOpenSettings: () -> Unit
    lateinit var listsOnOpenList: (String) -> Unit
    lateinit var listsOnOpenListSettings: (String) -> Unit
    lateinit var listsOnCreateList: () -> Unit
    lateinit var homeOnCreateList: () -> Unit
    lateinit var newListOnChoosePeople: (List<Long>) -> Unit
    lateinit var newListOnLeave: () -> Unit
    lateinit var newListOnChosenPeopleTaken: () -> Unit
    lateinit var pickContactsOnCollect: (List<Long>) -> Unit

    /** What the graph last handed New list as the picker's result. */
    var newListChosenPeople: List<Long>? = null
    lateinit var callLogOnOpenSettings: () -> Unit

    /** The count the host last composed [UnknownRouteNotice] with; -1 until it has. */
    var unknownRoutesReported = -1

    @Composable
    private fun Stub(route: String) {
        BasicText(text = route, modifier = Modifier.testTag(route))
    }

    @Composable
    override fun Home(
        onOpenList: (listId: String) -> Unit,
        onOpenSearch: () -> Unit,
        onOpenSettings: () -> Unit,
        onOpenLists: () -> Unit,
        onCreateList: () -> Unit,
        onAddPeopleToList: (listId: String) -> Unit,
        onOpenListSettings: (listId: String) -> Unit,
        onOpenContactWithFocus: (contactId: String, focusNote: Boolean) -> Unit,
        onOpenPostCallNote: (contactId: String, callEventId: Long) -> Unit,
        onOpenWeek: (listId: String) -> Unit,
    ) {
        homeOnOpenPostCallNote = onOpenPostCallNote
        homeOnOpenWeek = onOpenWeek
        homeOnCreateList = onCreateList
        Stub(Routes.Home)
    }

    @Composable
    override fun Week(
        listId: String,
        onBack: () -> Unit,
        onOpenContact: (contactId: String) -> Unit,
    ) {
        weekOnBack = onBack
        weekOnOpenContact = onOpenContact
        Stub(Routes.week(listId))
    }

    @Composable
    override fun Card(
        listId: String,
        onBack: () -> Unit,
        onOpenContact: (contactId: String) -> Unit,
        onAddNote: (contactId: String, callEventId: Long?) -> Unit,
        onBrowse: (listId: String, focusContactId: Long?) -> Unit,
        onEditList: (listId: String) -> Unit,
        onAddContacts: (listId: String) -> Unit,
        onOpenSettings: () -> Unit,
    ) {
        cardOnOpenContact = onOpenContact
        cardOnAddNote = onAddNote
        cardOnBrowse = onBrowse
        Stub(Routes.card(listId))
    }

    @Composable
    override fun Browse(
        listId: String,
        onBack: () -> Unit,
        onOpenContact: (contactId: String) -> Unit,
        onAddContacts: (listId: String) -> Unit,
        onOpenSettings: () -> Unit,
    ) {
        browseOnOpenSettings = onOpenSettings
        Stub(Routes.browse(listId))
    }

    @Composable
    override fun Search(
        onBack: () -> Unit,
        onOpenContact: (contactId: String) -> Unit,
        onAddToLists: (contactId: String) -> Unit,
        onOpenSettings: () -> Unit,
    ) {
        searchOnOpenSettings = onOpenSettings
        Stub(Routes.GlobalSearch)
    }

    @Composable
    override fun Contact(
        contactId: String,
        onBack: () -> Unit,
        onAddToLists: (contactId: String) -> Unit,
        onRelink: (contactId: Long) -> Unit,
        onViewAllCalls: () -> Unit,
        onOpenSettings: () -> Unit,
    ) {
        contactOnViewAllCalls = onViewAllCalls
        contactOnOpenSettings = onOpenSettings
        Stub(Routes.contact(contactId))
    }

    @Composable
    override fun Lists(
        onBack: () -> Unit,
        onOpenList: (listId: String) -> Unit,
        onOpenListSettings: (listId: String) -> Unit,
        onAddContacts: (listId: String) -> Unit,
        onCreateList: () -> Unit,
    ) {
        listsOnOpenList = onOpenList
        listsOnOpenListSettings = onOpenListSettings
        listsOnCreateList = onCreateList
        Stub(Routes.Lists)
    }

    @Composable
    override fun NewList(
        chosenPeople: List<Long>?,
        onChosenPeopleTaken: () -> Unit,
        onChoosePeople: (selectedContactIds: List<Long>) -> Unit,
        onLeave: () -> Unit,
    ) {
        newListChosenPeople = chosenPeople
        newListOnChosenPeopleTaken = onChosenPeopleTaken
        newListOnChoosePeople = onChoosePeople
        newListOnLeave = onLeave
        Stub(Routes.NewList)
    }

    @Composable
    override fun ListConfig(
        listId: String,
        onBack: () -> Unit,
        onSave: () -> Unit,
        onAddContacts: (listId: String) -> Unit,
    ) = Stub(Routes.listConfig(listId))

    @Composable
    override fun Settings(onBack: () -> Unit, onOpenIgnored: () -> Unit, onOpenCallHistory: () -> Unit) =
        Stub(Routes.Settings)

    @Composable
    override fun SettingsIgnored(onBack: () -> Unit) = Stub(Routes.SettingsIgnored)

    @Composable
    override fun CallLog(
        onBack: () -> Unit,
        onOpenContact: (contactId: Long, callEventId: Long) -> Unit,
        onOpenSettings: () -> Unit,
    ) {
        callLogOnOpenSettings = onOpenSettings
        Stub(Routes.CallLog)
    }

    @Composable
    override fun OnboardWelcome(onContinue: () -> Unit) {
        welcomeOnContinue = onContinue
        Stub(Routes.OnboardWelcome)
    }

    @Composable
    override fun OnboardPermContacts(onBack: (() -> Unit)?, onContinue: () -> Unit) {
        permContactsOnBack = onBack
        Stub(Routes.OnboardPermContacts)
    }

    @Composable
    override fun OnboardPermCallLog(onBack: (() -> Unit)?, onContinue: () -> Unit) {
        permCallLogShown = true
        permCallLogOnBack = onBack
        Stub(Routes.OnboardPermCallLog)
    }

    @Composable
    override fun OnboardPermNotifications(onBack: (() -> Unit)?, onContinue: () -> Unit) =
        Stub(Routes.OnboardPermNotifs)

    @Composable
    override fun OnboardSync(onContinue: () -> Unit) {
        syncOnContinue = onContinue
        Stub(Routes.OnboardSync)
    }

    @Composable
    override fun OnboardPreview(
        onAccept: (defaultName: String, contactIds: List<Long>) -> Unit,
        onSkip: () -> Unit,
    ) {
        previewOnSkip = onSkip
        Stub(Routes.OnboardPreview)
    }

    @Composable
    override fun OnboardFirstList(
        listId: String,
        onDone: () -> Unit,
        onAddAnother: () -> Unit,
        onAddContacts: () -> Unit,
        onStartAgain: () -> Unit,
    ) {
        firstListOnStartAgain = onStartAgain
        Stub(Routes.firstList(listId))
    }

    @Composable
    override fun OnboardDone(onFinish: () -> Unit) {
        doneOnFinish = onFinish
        Stub(Routes.OnboardDone)
    }

    @Composable
    override fun PickContacts(onBack: () -> Unit, onCommit: () -> Unit, onCollect: (contactIds: List<Long>) -> Unit) {
        pickContactsOnCollect = onCollect
        Stub("pick/contacts")
    }

    @Composable
    override fun PickLists(onBack: () -> Unit, onCommit: () -> Unit) = Stub("pick/lists")

    @Composable
    override fun PostCallNote(contactId: String, callEventId: String?, onLeave: () -> Unit) {
        noteShownFor = contactId to callEventId
        noteOnLeave = onLeave
        Stub(Routes.PostCallNote)
    }

    @Composable
    override fun CommitSnackbarHost(modifier: Modifier) = Unit

    @Composable
    override fun UnknownRouteNotice(occurrences: Int) {
        unknownRoutesReported = occurrences
    }
}
