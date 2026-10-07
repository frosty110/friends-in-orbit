package app.orbit.nav

// Single source of truth for nav paths. Screens reference these, never literals.
object Routes {
    const val Home = "home"
    const val Card = "card/{listId}"
    const val Browse = "browse/{listId}"

    // NOTE-02 / LOG-03 — optional query args for "open contact and focus the
    // Notes input" / "open contact and scroll to a specific call event". When
    // both args are absent the path matches `contact/{id}`
    // because Navigation Compose treats `?key={key}` as truly optional with
    // nullable defaults configured in the composable() registration.
    const val Contact = "contact/{contactId}?focusNote={focusNote}&scrollToCallEventId={scrollToCallEventId}"

    // `openCreate` is an optional Bool query arg (default false). When true,
    // ListsManagerScreen initializes its create-list bottom sheet expanded —
    // used by Home's "Create your first list" / "New list" CTAs so a single
    // tap from Home lands the user directly in the list-creation form.
    const val Lists = "lists?openCreate={openCreate}"
    const val ListConfig = "lists/{listId}/config"
    const val Settings = "settings"

    /** IGNORE-06 — Settings → Ignored full nav destination. */
    const val SettingsIgnored = "settings/ignored"

    /** LOG-01: In-app call log full nav destination (everyone's calls). */
    const val CallLog = "call-log"

    /**
     * LOG-04: the call log's registration pattern. The optional `contactId`
     * narrows it to one person ("View all calls" on Contact detail). Navigating
     * to the bare [CallLog] still matches, with the argument absent, so
     * Settings' entry is unchanged.
     */
    const val CallLogPattern = "call-log?contactId={contactId}"
    const val GlobalSearch = "search"

    // Onboarding flow (post-2026-04-28 whole-app review, two asks since
    // ONB-30): Welcome, Contacts perm, Call log perm, Reading your call
    // history (blocking sync gate), the preview screen when 3 or more people
    // match the recency rule, Make your first list (production List
    // Configuration reused), Done (where nudges are asked for), Home.
    // Order: Contacts first (most-impactful permission lands first). Sync is
    // non-skippable (G2). First-list creation is required for activation (E1).
    // OnboardPermNotifs is no longer in the flow: it stays registered so an
    // install that saved it as its resume step before ONB-30 still lands on a
    // real screen, which continues to Sync.
    // The OnboardFirstList route carries a {listId} path arg so the screen can
    // hydrate the production ListConfigViewModel via SavedStateHandle.
    const val OnboardWelcome = "onboard/welcome"
    const val OnboardPermContacts = "onboard/permissions/contacts"
    const val OnboardPermCallLog = "onboard/permissions/call-log"
    const val OnboardPermNotifs = "onboard/permissions/notifications"
    const val OnboardSync = "onboard/sync"
    const val OnboardPreview = "onboard/preview"
    const val OnboardFirstList = "onboard/first-list/{listId}"
    const val OnboardDone = "onboard/done"

    // Picker routes (BULK-05 / BULK-06).
    // PickContacts: the "Add people" entry: pick from the address book into a target list.
    //   - targetListId: which list the people will be added to
    //   - mode: "add" (default) | "move" | "copy" | "relink"; drives BatchCounter CTA copy
    //   - sourceListId: REQUIRED for mode=move (which list the people leave);
    //     a move route without it lands on the picker's NotFound terminal state
    //     (the Move commit dispatches MoveContactsUseCase).
    //   - relinkContactId: REQUIRED for mode=relink (CONTACT-07), which takes no
    //     targetListId; the orphan being re-linked. Build it with [relinkContact].
    //   As of 2026-10-06 nothing navigates to mode=move or mode=copy: moving and
    //   copying people between lists happen from Browse's multi-select, through
    //   its own list sheet, not through this picker. The modes stay registered
    //   because the picker's commit paths and their tests cover them; a caller
    //   that reaches for them should check the picker page view first.
    // PickLists: the reverse picker: given a person, pick which lists to add them to.
    const val PickContacts =
        "pick/contacts?targetListId={targetListId}&mode={mode}" +
            "&sourceListId={sourceListId}&relinkContactId={relinkContactId}"
    const val PickLists = "pick/lists?contactId={contactId}"

    fun card(listId: String) = "card/$listId"
    fun browse(listId: String) = "browse/$listId"
    fun contact(contactId: String) = "contact/$contactId"
    fun listConfig(listId: String) = "lists/$listId/config"
    fun firstList(listId: String) = "onboard/first-list/$listId"
    fun lists(openCreate: Boolean = false): String =
        if (openCreate) "lists?openCreate=true" else "lists?openCreate=false"

    /**
     * NOTE-02 / LOG-03 — contact route with optional focus / scroll args.
     *
     * Emits the same `contact/$id` path when both args are absent so existing
     * callers don't change. When `focusNote = true` the destination ContactDetail
     * VM reads it via SavedStateHandle and emits a one-shot focus signal that
     * the NotesSection consumes via a FocusRequester (NOTE-02). When
     * `scrollToCallEventId` is non-null the destination LazyColumn scrolls to
     * that row (LOG-03).
     */
    fun contactWithFocus(
        contactId: String,
        focusNote: Boolean = false,
        scrollToCallEventId: Long? = null
    ): String {
        val params = buildList<String> {
            if (focusNote) add("focusNote=1")
            if (scrollToCallEventId != null) add("scrollToCallEventId=$scrollToCallEventId")
        }
        return if (params.isEmpty()) {
            contact(contactId)
        } else {
            "${contact(contactId)}?${params.joinToString("&")}"
        }
    }

    /**
     * [sourceListId] is required when `mode = "move"` (the list contacts are
     * moved away from); the picker routes a move without it to NotFound
     * rather than silently dropping the commit.
     */
    fun pickContacts(targetListId: String, mode: String = "add", sourceListId: String? = null) =
        if (sourceListId == null) {
            "pick/contacts?targetListId=$targetListId&mode=$mode"
        } else {
            "pick/contacts?targetListId=$targetListId&mode=$mode&sourceListId=$sourceListId"
        }
    fun pickLists(contactId: String) = "pick/lists?contactId=$contactId"

    /** LOG-04: the call log narrowed to one person. */
    fun callLogFor(contactId: String) = "call-log?contactId=$contactId"

    /**
     * NOTE-04: the page for writing about a call. `contactId` is the person
     * (a bare id or the UI's "c-" form); the optional `callEventId` names the
     * call the page describes ("You called Kai · 14 min · Today at 4:30pm").
     * Without it the page describes the person's latest connected call, which
     * is what a caller that knows only who was called (Card view's "Called
     * Kai" snackbar) means. Build it with [postCallNote].
     */
    const val PostCallNote = "note/{contactId}?callEventId={callEventId}"

    /** NOTE-04: see [PostCallNote]. Home's stack and the notification pass the call. */
    fun postCallNote(contactId: String, callEventId: Long? = null): String =
        if (callEventId == null) "note/$contactId" else "note/$contactId?callEventId=$callEventId"

    /**
     * CONTACT-07: the picker in Relink mode for one orphaned contact. Its own
     * builder, not [pickContacts]: that one's first argument is a LIST id, and
     * passing a contact id there is exactly how Re-link used to add people to
     * an unrelated list.
     */
    fun relinkContact(orphanContactId: String) =
        "pick/contacts?mode=relink&relinkContactId=$orphanContactId"
}
