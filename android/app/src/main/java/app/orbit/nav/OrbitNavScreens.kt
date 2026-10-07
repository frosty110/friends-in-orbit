package app.orbit.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.orbit.ui.screens.browse.BrowseListScreen
import app.orbit.ui.screens.browse.GlobalSearchScreen
import app.orbit.ui.screens.calllog.CallLogScreen
import app.orbit.ui.screens.card.CardViewScreen
import app.orbit.ui.screens.contact.ContactDetailScreen
import app.orbit.ui.screens.home.HomeScreen
import app.orbit.ui.screens.lists.ListConfigScreen
import app.orbit.ui.screens.lists.ListsManagerScreen
import app.orbit.ui.screens.note.PostCallNoteScreen
import app.orbit.ui.screens.onboarding.OnboardingDoneScreen
import app.orbit.ui.screens.onboarding.OnboardingFirstListScreen
import app.orbit.ui.screens.onboarding.OnboardingPermCallLogScreen
import app.orbit.ui.screens.onboarding.OnboardingPermContactsScreen
import app.orbit.ui.screens.onboarding.OnboardingPermNotificationsScreen
import app.orbit.ui.screens.onboarding.OnboardingPreviewScreen
import app.orbit.ui.screens.onboarding.OnboardingSyncScreen
import app.orbit.ui.screens.onboarding.OnboardingWelcomeScreen
import app.orbit.ui.screens.picker.ContactPickerScreen
import app.orbit.ui.screens.picker.ListPickerScreen
import app.orbit.ui.screens.picker.PickerCommitSnackbarHost
import app.orbit.ui.screens.picker.UnknownRouteSnackbar
import app.orbit.ui.screens.settings.SettingsScreen
import app.orbit.ui.screens.settings.ignored.SettingsIgnoredScreen

/**
 * The screens the navigation graph composes, one slot per route, plus the
 * commit snackbar host drawn over the graph.
 *
 * The graph ([OrbitNavHost]) decides where every callback leads and reads the
 * route arguments; a screen decides what is on it. Keeping the two apart here
 * is what lets the graph's promises (ONB-23's cleared stack, LIST-23, LOG-05's
 * pop-or-push, the deep-link guard) run on the JVM: `OrbitNavHostTest` puts
 * labelled stubs in these slots, so no `hiltViewModel()` is ever created and
 * no Hilt graph is needed, while the app composes the real screens through
 * [Real]. Each slot takes the real screen's own parameters minus its
 * ViewModels, which the real screen resolves itself; a stub records the
 * callbacks so a test can fire them.
 */
interface OrbitNavScreens {

    @Composable
    fun Home(
        onOpenList: (listId: String) -> Unit,
        onOpenSearch: () -> Unit,
        onOpenSettings: () -> Unit,
        onOpenLists: () -> Unit,
        onCreateList: () -> Unit,
        onAddPeopleToList: (listId: String) -> Unit,
        onOpenListSettings: (listId: String) -> Unit,
        onOpenContactWithFocus: (contactId: String, focusNote: Boolean) -> Unit,
        onOpenPostCallNote: (contactId: String, callEventId: Long) -> Unit,
    )

    @Composable
    fun Card(
        listId: String,
        onBack: () -> Unit,
        onOpenContact: (contactId: String) -> Unit,
        onAddNote: (contactId: String, callEventId: Long?) -> Unit,
        onBrowse: (listId: String, focusContactId: Long?) -> Unit,
        onEditList: (listId: String) -> Unit,
        onAddContacts: (listId: String) -> Unit,
        onOpenSettings: () -> Unit,
    )

    @Composable
    fun Browse(
        listId: String,
        onBack: () -> Unit,
        onOpenContact: (contactId: String) -> Unit,
        onAddContacts: (listId: String) -> Unit,
        onOpenSettings: () -> Unit,
    )

    @Composable
    fun Search(
        onBack: () -> Unit,
        onOpenContact: (contactId: String) -> Unit,
        onAddToLists: (contactId: String) -> Unit,
        onOpenSettings: () -> Unit,
    )

    @Composable
    fun Contact(
        contactId: String,
        onBack: () -> Unit,
        onAddToLists: (contactId: String) -> Unit,
        onRelink: (contactId: Long) -> Unit,
        onViewAllCalls: () -> Unit,
        onOpenSettings: () -> Unit,
    )

    @Composable
    fun Lists(
        onBack: () -> Unit,
        onOpenList: (listId: String) -> Unit,
        onOpenListSettings: (listId: String) -> Unit,
        onAddContacts: (listId: String) -> Unit,
        openCreateOnLaunch: Boolean,
    )

    @Composable
    fun ListConfig(
        listId: String,
        onBack: () -> Unit,
        onSave: () -> Unit,
        onAddContacts: (listId: String) -> Unit,
    )

    @Composable
    fun Settings(
        onBack: () -> Unit,
        onOpenIgnored: () -> Unit,
        onOpenCallHistory: () -> Unit,
    )

    @Composable
    fun SettingsIgnored(onBack: () -> Unit)

    @Composable
    fun CallLog(
        onBack: () -> Unit,
        onOpenContact: (contactId: Long, callEventId: Long) -> Unit,
        onOpenSettings: () -> Unit,
    )

    @Composable
    fun OnboardWelcome(onContinue: () -> Unit)

    /** [onBack] is null when there is nothing to go back to (a resumed step). */
    @Composable
    fun OnboardPermContacts(onBack: (() -> Unit)?, onContinue: () -> Unit)

    @Composable
    fun OnboardPermCallLog(onBack: (() -> Unit)?, onContinue: () -> Unit)

    @Composable
    fun OnboardPermNotifications(onBack: (() -> Unit)?, onContinue: () -> Unit)

    @Composable
    fun OnboardSync(onContinue: () -> Unit)

    @Composable
    fun OnboardPreview(
        onAccept: (defaultName: String, contactIds: List<Long>) -> Unit,
        onSkip: () -> Unit,
    )

    @Composable
    fun OnboardFirstList(
        listId: String,
        onDone: () -> Unit,
        onAddAnother: () -> Unit,
        onAddContacts: () -> Unit,
        onStartAgain: () -> Unit,
    )

    @Composable
    fun OnboardDone(onFinish: () -> Unit)

    @Composable
    fun PickContacts(onBack: () -> Unit, onCommit: () -> Unit)

    @Composable
    fun PickLists(onBack: () -> Unit, onCommit: () -> Unit)

    /**
     * NOTE-04: the page for writing about a call. The real screen reads
     * [contactId] and [callEventId] from its own SavedStateHandle; they are in
     * the slot so a stub can show which call the graph opened.
     */
    @Composable
    fun PostCallNote(contactId: String, callEventId: String?, onLeave: () -> Unit)

    /**
     * The app-level snackbar host for picker commits. It is drawn over the
     * whole graph because the pickers pop on commit, so their "Added N" and
     * "Couldn't save that" must outlive the picker's own composition.
     */
    @Composable
    fun CommitSnackbarHost(modifier: Modifier)

    /**
     * Reports a route handed to the app that the graph could not open: a
     * widget, nudge or shortcut whose route no longer exists, or another
     * app's extra. [occurrences] counts the misses so far, and each rise
     * says "Couldn't open that." once (rules.md Code 3). The real one
     * publishes on the app-level snackbar bus ([PickerCommitSnackbarHost]'s
     * ViewModel, through Hilt), so the message shows over whatever screen
     * is open; `OrbitNavHostTest`'s stub records the count it was given.
     */
    @Composable
    fun UnknownRouteNotice(occurrences: Int)

    /** The app's screens, each resolving its own ViewModel through Hilt. */
    object Real : OrbitNavScreens {

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
        ) = HomeScreen(
            onOpenList = onOpenList,
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onOpenLists = onOpenLists,
            onCreateList = onCreateList,
            onAddPeopleToList = onAddPeopleToList,
            onOpenListSettings = onOpenListSettings,
            onOpenContactWithFocus = onOpenContactWithFocus,
            onOpenPostCallNote = onOpenPostCallNote,
        )

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
        ) = CardViewScreen(
            listId = listId,
            onBack = onBack,
            // `onCall` is the screen's older name for "open this person": the
            // dial itself happens inside the screen, from the labelled Call
            // button only (CARD-01), and this callback is just the default
            // for onOpenContact. Both are given the same leg so the name
            // cannot mislead.
            onCall = onOpenContact,
            onOpenContact = onOpenContact,
            onAddNote = onAddNote,
            onBrowse = onBrowse,
            onEditList = onEditList,
            onAddContacts = onAddContacts,
            onOpenSettings = onOpenSettings,
        )

        @Composable
        override fun Browse(
            listId: String,
            onBack: () -> Unit,
            onOpenContact: (contactId: String) -> Unit,
            onAddContacts: (listId: String) -> Unit,
            onOpenSettings: () -> Unit,
        ) = BrowseListScreen(
            listId = listId,
            onBack = onBack,
            onOpenContact = onOpenContact,
            onAddContacts = onAddContacts,
            onOpenSettings = onOpenSettings,
        )

        @Composable
        override fun Search(
            onBack: () -> Unit,
            onOpenContact: (contactId: String) -> Unit,
            onAddToLists: (contactId: String) -> Unit,
            onOpenSettings: () -> Unit,
        ) = GlobalSearchScreen(
            onBack = onBack,
            onOpenContact = onOpenContact,
            onAddToLists = onAddToLists,
            onOpenSettings = onOpenSettings,
        )

        @Composable
        override fun Contact(
            contactId: String,
            onBack: () -> Unit,
            onAddToLists: (contactId: String) -> Unit,
            onRelink: (contactId: Long) -> Unit,
            onViewAllCalls: () -> Unit,
            onOpenSettings: () -> Unit,
        ) = ContactDetailScreen(
            contactId = contactId,
            onBack = onBack,
            onAddToLists = onAddToLists,
            onRelink = onRelink,
            onViewAllCalls = onViewAllCalls,
            onOpenSettings = onOpenSettings,
        )

        @Composable
        override fun Lists(
            onBack: () -> Unit,
            onOpenList: (listId: String) -> Unit,
            onOpenListSettings: (listId: String) -> Unit,
            onAddContacts: (listId: String) -> Unit,
            openCreateOnLaunch: Boolean,
        ) = ListsManagerScreen(
            onBack = onBack,
            onOpenList = onOpenList,
            onOpenListSettings = onOpenListSettings,
            onAddContacts = onAddContacts,
            openCreateOnLaunch = openCreateOnLaunch,
        )

        @Composable
        override fun ListConfig(
            listId: String,
            onBack: () -> Unit,
            onSave: () -> Unit,
            onAddContacts: (listId: String) -> Unit,
        ) = ListConfigScreen(
            listId = listId,
            onBack = onBack,
            onSave = onSave,
            onAddContacts = onAddContacts,
        )

        @Composable
        override fun Settings(
            onBack: () -> Unit,
            onOpenIgnored: () -> Unit,
            onOpenCallHistory: () -> Unit,
        ) = SettingsScreen(
            onBack = onBack,
            onOpenIgnored = onOpenIgnored,
            onOpenCallHistory = onOpenCallHistory,
        )

        @Composable
        override fun SettingsIgnored(onBack: () -> Unit) = SettingsIgnoredScreen(onBack = onBack)

        @Composable
        override fun CallLog(
            onBack: () -> Unit,
            onOpenContact: (contactId: Long, callEventId: Long) -> Unit,
            onOpenSettings: () -> Unit,
        ) = CallLogScreen(
            onBack = onBack,
            onOpenContact = onOpenContact,
            onOpenSettings = onOpenSettings,
        )

        @Composable
        override fun OnboardWelcome(onContinue: () -> Unit) = OnboardingWelcomeScreen(onContinue = onContinue)

        @Composable
        override fun OnboardPermContacts(onBack: (() -> Unit)?, onContinue: () -> Unit) =
            OnboardingPermContactsScreen(onBack = onBack, onContinue = onContinue)

        @Composable
        override fun OnboardPermCallLog(onBack: (() -> Unit)?, onContinue: () -> Unit) =
            OnboardingPermCallLogScreen(onBack = onBack, onContinue = onContinue)

        @Composable
        override fun OnboardPermNotifications(onBack: (() -> Unit)?, onContinue: () -> Unit) =
            OnboardingPermNotificationsScreen(onBack = onBack, onContinue = onContinue)

        @Composable
        override fun OnboardSync(onContinue: () -> Unit) = OnboardingSyncScreen(onContinue = onContinue)

        @Composable
        override fun OnboardPreview(
            onAccept: (defaultName: String, contactIds: List<Long>) -> Unit,
            onSkip: () -> Unit,
        ) = OnboardingPreviewScreen(onAccept = onAccept, onSkip = onSkip)

        @Composable
        override fun OnboardFirstList(
            listId: String,
            onDone: () -> Unit,
            onAddAnother: () -> Unit,
            onAddContacts: () -> Unit,
            onStartAgain: () -> Unit,
        ) = OnboardingFirstListScreen(
            listId = listId,
            onDone = onDone,
            onAddAnother = onAddAnother,
            onAddContacts = onAddContacts,
            onStartAgain = onStartAgain,
        )

        @Composable
        override fun OnboardDone(onFinish: () -> Unit) = OnboardingDoneScreen(onFinish = onFinish)

        @Composable
        override fun PickContacts(onBack: () -> Unit, onCommit: () -> Unit) =
            ContactPickerScreen(onBack = onBack, onCommit = onCommit)

        @Composable
        override fun PickLists(onBack: () -> Unit, onCommit: () -> Unit) =
            ListPickerScreen(onBack = onBack, onCommit = onCommit)

        @Composable
        override fun PostCallNote(contactId: String, callEventId: String?, onLeave: () -> Unit) =
            PostCallNoteScreen(onLeave = onLeave)

        @Composable
        override fun CommitSnackbarHost(modifier: Modifier) =
            PickerCommitSnackbarHost(modifier = modifier)

        @Composable
        override fun UnknownRouteNotice(occurrences: Int) =
            UnknownRouteSnackbar(occurrences = occurrences)
    }
}
