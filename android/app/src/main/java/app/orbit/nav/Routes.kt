package app.orbit.nav

// Single source of truth for nav paths. Screens reference these, never literals.
object Routes {
    const val Home = "home"
    const val Card = "card/{listId}"

    // BROWSE-09: `focus` is an optional query arg, the id of the person on the
    // card when its menu's "Browse people" opened Browse; Browse marks that row
    // "On your card" and scrolls to it. "Browse this list" (All quiet, nobody
    // on the card) leaves it out, so `browse/3` still matches. Build it with
    // [browse].
    const val Browse = "browse/{listId}?focus={focus}"

    // NOTE-02 / LOG-03 — optional query args for "open contact and focus the
    // Notes input" / "open contact and scroll to a specific call event". When
    // both args are absent the path matches `contact/{id}`
    // because Navigation Compose treats `?key={key}` as truly optional with
    // nullable defaults configured in the composable() registration.
    const val Contact = "contact/{contactId}?focusNote={focusNote}&scrollToCallEventId={scrollToCallEventId}"

    // `openCreate` is an optional Bool query arg (default false). When true,
    // the graph opens New list over Lists as soon as Lists is shown, once
    // (LIST-28). Nothing in the app builds it any more: Home's "New list" and
    // "Create your first list" and the Lists screen's "New list" go to
    // [NewList] directly. It stays so a route handed in from outside (a
    // NAVIGATE_TO extra written before the flow existed) still lands in the
    // creation flow, as it used to land in the create sheet.
    const val Lists = "lists?openCreate={openCreate}"

    // LIST-28: New list, step by step (Start with, Name, How often, Add
    // people). Its own route rather than a sheet on Lists, so Home opens it
    // directly and Create returns to whichever screen opened it.
    const val NewList = "lists/new"
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
    //   - selected: mode=collect only (LIST-28), New list's People step: the
    //     people already chosen, comma-separated, so the picker opens with them
    //     ticked. Collect takes no targetListId (the list does not exist yet)
    //     and writes nothing: its button hands the selection back to New
    //     list. Build it with [collectPeople].
    //   As of 2026-10-06 nothing navigates to mode=move or mode=copy: moving and
    //   copying people between lists happen from Browse's multi-select, through
    //   its own list sheet, not through this picker. The modes stay registered
    //   because the picker's commit paths and their tests cover them; a caller
    //   that reaches for them should check the picker page view first.
    // PickLists: the reverse picker: given a person, pick which lists to add them to.
    const val PickContacts =
        "pick/contacts?targetListId={targetListId}&mode={mode}" +
            "&sourceListId={sourceListId}&relinkContactId={relinkContactId}&selected={selected}"
    const val PickLists = "pick/lists?contactId={contactId}"

    // HOME-13: one list's calls, week by week, from Home's strip ("See your
    // week") and its day sheet ("See the whole week"). It always opens on
    // this week, which holds every day the strip shows. Build it with [week].
    const val Week = "week/{listId}"

    fun card(listId: String) = "card/$listId"
    fun week(listId: String) = "week/$listId"
    fun browse(listId: String, focusContactId: Long? = null) =
        if (focusContactId == null) "browse/$listId" else "browse/$listId?focus=$focusContactId"
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

    /**
     * LIST-28: the picker in Collect mode for New list's People step, opened
     * with [selectedContactIds] ticked. Its own builder for the same reason
     * as [relinkContact]: there is no list id to pass.
     */
    fun collectPeople(selectedContactIds: Collection<Long> = emptyList()): String =
        if (selectedContactIds.isEmpty()) {
            "pick/contacts?mode=collect"
        } else {
            "pick/contacts?mode=collect&selected=${selectedContactIds.joinToString(",")}"
        }
}
