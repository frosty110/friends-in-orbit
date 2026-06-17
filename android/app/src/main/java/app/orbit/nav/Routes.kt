package app.orbit.nav

// Single source of truth for nav paths. Screens reference these, never literals.
object Routes {
    const val Home              = "home"
    const val Card              = "card/{listId}"
    const val Browse            = "browse/{listId}"
    // NOTE-02 / LOG-03 — optional query args for "open contact and focus the
    // Notes input" / "open contact and scroll to a specific call event". When
    // both args are absent the path matches `contact/{id}`
    // because Navigation Compose treats `?key={key}` as truly optional with
    // nullable defaults configured in the composable() registration.
    const val Contact           = "contact/{contactId}?focusNote={focusNote}&scrollToCallEventId={scrollToCallEventId}"
    // `openCreate` is an optional Bool query arg (default false). When true,
    // ListsManagerScreen initializes its create-list bottom sheet expanded —
    // used by Home's "Create your first list" / "New list" CTAs so a single
    // tap from Home lands the user directly in the list-creation form.
    const val Lists             = "lists?openCreate={openCreate}"
    const val ListConfig        = "lists/{listId}/config"
    const val Settings          = "settings"
    /** IGNORE-06 — Settings → Ignored full nav destination. */
    const val SettingsIgnored   = "settings/ignored"
    /** LOG-01 — In-app call log full nav destination. */
    const val CallLog           = "call-log"
    const val GlobalSearch      = "search"

    // Onboarding flow (post-2026-04-28 whole-app review):
    // Welcome → Contacts perm → Call log perm → Notifications perm
    //   → Reading your call history (blocking sync gate)
    //   → [if ≥3 contacts match recency rule] H/β preview screen
    //   → Make your first list (production List Configuration reused)
    //   → Done → Home.
    // Order: Contacts first (most-impactful permission lands first). Sync is
    // non-skippable (G2). First-list creation is required for activation (E1).
    // The OnboardFirstList route carries a {listId} path arg so the screen can
    // hydrate the production ListConfigViewModel via SavedStateHandle.
    const val OnboardWelcome       = "onboard/welcome"
    const val OnboardPermContacts  = "onboard/permissions/contacts"
    const val OnboardPermCallLog   = "onboard/permissions/call-log"
    const val OnboardPermNotifs    = "onboard/permissions/notifications"
    const val OnboardSync          = "onboard/sync"
    const val OnboardPreview       = "onboard/preview"
    const val OnboardFirstList     = "onboard/first-list/{listId}"
    const val OnboardDone          = "onboard/done"

    // Picker routes (BULK-05 / BULK-06).
    // PickContacts: "Add contacts" entry — pick from address book into a target list.
    //   - targetListId: which list contacts will be added to
    //   - mode: "add" (default) | "move" | "copy" — drives BatchCounter CTA copy
    //   - sourceListId: REQUIRED for mode=move (which list the contacts leave);
    //     a move route without it lands on the picker's NotFound terminal state
    //     (the Move commit dispatches MoveContactsUseCase).
    // PickLists: reverse picker — given a contact, pick which lists to add them to.
    const val PickContacts =
        "pick/contacts?targetListId={targetListId}&mode={mode}&sourceListId={sourceListId}"
    const val PickLists    = "pick/lists?contactId={contactId}"

    fun card(listId: String)         = "card/$listId"
    fun browse(listId: String)       = "browse/$listId"
    fun contact(contactId: String)   = "contact/$contactId"
    fun listConfig(listId: String)   = "lists/$listId/config"
    fun firstList(listId: String)    = "onboard/first-list/$listId"
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
        scrollToCallEventId: Long? = null,
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
}
