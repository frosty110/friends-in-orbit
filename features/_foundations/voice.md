# Voice and content rules

**Status:** active
**Last reviewed:** 2026-10-08
**Canonical for:** voice, tone, never-say list, empty-state framing
**Ground truth:** enforced at notification-formatter level (`features/notifications/README.md`); elsewhere enforced by review

When this doc disagrees with `README.md` §Content fundamentals, this doc wins.

## Voice

- **Warm but not saccharine.** This is an app for calling people you care about. Avoid cute, avoid clinical.
- **Direct, short sentences.** Matches the reduce-cognitive-load mission.
- **Second person.** "Your people," never "the contact."
- **Calm and unpressured.** Tone should feel safe and supportive — never clinical, never demanding. Reaching out is always the user's choice.
- **Empty states feel like a friend, not a coach.** "All quiet for now," not "Great job, keep it going!"

## Writing rules

- **Sentence case.** Everywhere. No title case except brand name "Orbit."
- **No exclamation marks.** Ever.
- **No emoji in product copy.** (Okay in chat conversations with the builder; never in the app.)
- **Active voice. Present tense.** "Spoke yesterday" not "A call was made yesterday."
- **16sp minimum body size.** Used in emotionally loaded moments — don't make people squint.

## Never say

- Streaks, achievements, levels, XP, rewards, unlocks
- "You haven't called X in N days" — shame framing
- "Crush your goals," "stay on track," "beat your record" — hustle framing
- "Great job," "awesome," "keep it going!" — coach framing
- Emoji, unicode glyphs, ASCII art in product copy
- "The contact," "the user," "the entity" — clinical framing
- "Due" as a deadline ("due today", "not due yet", "3 people due"): deadline framing. Orbit suggests; it never sets a deadline. Where a row needs to say whose turn it is, say "Up now" (Browse's rows); for a quiet moment say who comes up next and when. Card view puts nothing over the name since 2026-10-08 (CARD-04). (Added 2026-10-05. HOME-6 retired the word on Home in June; this makes the rule explicit for every screen, and the string audit `VoiceAuditTest` holds the resources to it.)

## Always say

- "Patterns," "rhythms," "gaps" — neutral temporal framing
- "Your people" — possessive + human
- "All quiet for now" for the moment nobody comes up. (Until 2026-10-05 this said "You're caught up". The queue is continuous by design since the tide-marker change of 2026-05-08, and Home's HOME-6 retired "caught up" and "due" language, so nothing should read as a cleared backlog.)
- "Want to..." — soft invitation, never demand

## Empty states — tone reference

- Nobody comes up right now: "All quiet for now." Then who comes up next and when, and a way to browse anyway.
- No lists yet: quiet instruction, no urgency
- Permission denied: plain explanation of what's lost, offer to continue without

## Glossary: one word for one idea

Added 2026-10-05 ([UX rubric](../../vision/ux-rubric.md) D7). The same idea had up to four names on screen; a reader should never wonder whether two words mean two things.

| Say | Means | Don't say |
|---|---|---|
| **Later** | Move this person further out on this list. | Skip, defer, pass, snooze |
| **Sooner** | Bring this person forward on this list. | Surface sooner, move up, boost |
| **Move up** / **Move down** | Move one row one place in an order the user arranges by hand: a list on Lists, a person in Browse's order (BROWSE-08). TalkBack's actions beside a drag handle, which is named "Reorder {name}". A drop in Browse says which way: "Moved Kai earlier" / "Moved Kai later". Added 2026-10-07. | Sooner or Later for a drag (those are the card's moves, by the rhythm); "move up" for Sooner |
| **Nudge** | The notification Orbit sends when someone on a list is worth a call. | Reminder, prompt, notification (in UI copy), alert |
| **After a call** | The other notification (NOTIF-16, 2026-10-07): "How was your call with Kai?" after a call worth a note, and the name of its channel in Android's settings. It is about a call that happened, not a suggestion, so it is never called a nudge. | Nudge (for this one), reminder |
| **List** | A group of people the user keeps in touch with, with its own rhythm. | Orbit (as a noun for a list), group, circle |
| **Rhythm** | How often the user means to talk to people on a list ("every 2 weeks"), and the 7-day strip on Home. A list's rhythm is said as its interval ("Every 3 days", "Every day"), never by an engine's name ("Late night rhythm"); List settings' control for it is "How often" (LIST-30). | Cadence, frequency, interval, threshold |
| **This week** | The seven days ending today: what the rhythm strip on Home shows, and the first week of the Week screen (HOME-13). Weeks there are rolling sevens ending on today's weekday, never Monday to Sunday, so "This week" always means the strip's days. An earlier week is named by its dates ("28 Sep to 4 Oct"). The ways in are "See your week" (the strip) and "See the whole week" (a day's sheet). Added 2026-10-07. | Last 7 days (the strip's old heading), calendar week, "Week of 28 Sep" |
| **When to nudge** | Where a list's nudge days and times are set, and the only thing that decides when its nudge comes (LIST-25). Said as one line: "Weekdays at 10am". "Time of day" (Mornings, Evenings and so on) was a second section for the same thing from 2026-10-07 and went on 2026-10-08; don't bring it back as a word for a nudge setting. | Time of day, active hours, always active, window, quiet hours |
| **Call** | A phone call, which Orbit sees in the call log. | Interaction, touchpoint |
| **Connection** | A conversation Orbit couldn't see (WhatsApp, a visit), added by hand with "Log a connection". Only for those. | Using it for a phone call |
| **Note** | Something the user wrote about a person. | Memo, comment |
| **Pause** | Stop surfacing and nudging a person for a while. Three lengths, the same three wherever a pause is offered and in one shared sheet (`PauseDurationSheet`): "1 week", "1 month", "Until you unpause". The snackbar says the same: "Paused {name} for 1 week", "Paused {name} until you unpause". | Mute (for people), suspend, "Indefinitely" |
| **Unpause** | End a person's pause so they surface again. The inverse of Pause has this one name in the menu ("Unpause"), the status line ("Paused until you unpause") and the snackbar ("Unpaused {name}"). | Resume (for a person), un-pause, restore |
| **Pause nudges** / **Resume nudges** | A list's nudges are paused and resumed (Home's long-press menu). The list itself is not paused and its people still surface, so "Unpause" would say the wrong thing about a list. | Unpause (for a list), mute, mute prompts |
| **Ignore** / **Unignore** | Hide a person from Orbit's suggestions while keeping their history; and the one word that reverses it. One spelling, no hyphen, everywhere: the menu item, the Ignored screen's button, the snackbar ("Unignored {name}"). | Un-ignore, hide, block, restore |
| **Attempt** | A reach-out that did not connect: a voicemail, no answer. Logged by hand from "Log a connection" as "Couldn't reach them", or read from the call log; shown as "Attempted" in history ("You tried to reach them" on one person's page); kept out of Last call, Total calls and Average length, because nobody talked. | Missed call (the phone's word, and the other direction), failed call |
| **Next up** | The person a list would surface first: on Home's cards and as the heading over Browse's numbered queue. One spelling. | Up next (Browse's heading until 2026-10-05), next due |
| **Recently called** | The label for people with a recent call: Browse's filter chip and the picker's sort. | Called recently (Browse's chip until 2026-10-05), recent |
| **Add to lists** | The action that files one person into lists, from Contact detail and from a Search result; the picker it opens is titled "Add {name} to lists". | Add to list, file, assign |
| **Open settings** / **Open phone settings** | "Open settings" leads to Orbit's own Settings (a call-log notice on Card view or Call history). "Open phone settings" leads to Android's page for Orbit (a permission denied twice). Never a bare "Settings" for either. | Open Android Settings, Go to settings |
| **Open details** | What a tap on a person's row or on the card face does, on screen and to TalkBack, everywhere a person can be opened. | View details, Open contact, Show more |
| **Quick actions** | The long-press menu on a row or a card, as TalkBack names the gesture, on Home, Browse, Call history and the picker. | Show quick actions, More actions (for a long-press) |
| **More actions for {x}** | The three-dots button that opens an overflow menu, named for what it acts on: "More actions for Inner orbit", "More actions for this note", or "More actions" over a selection. Under the privacy curtain the name is masked like any other. | List options, Options, Menu |

**Stat labels** read the same on every screen: "Last call", "Total calls", "Average length", "Longest gap". No abbreviations ("Avg"), and "Last called" only inside a sentence ("Last called 3 weeks ago").

**Time since a call** is always worded by one formatter (`ui/util/RelativeTime.kt`), the same way everywhere: "today", "yesterday", "3 days ago", "2 weeks ago", "3 months ago". Never "27 days ago" on one screen and "3 weeks" on another. **When you last spoke**, the line under a person on Home's cards and on Card view, says it short, without "You": "Spoke today", "Spoke yesterday", "Spoke 3 weeks ago"; on Home, "No calls yet" for someone never called (Card view shows no line until there is a call, and its stats say "Never called"). Card view's line is a sentence, so it ends in a full stop ("Spoke 3 weeks ago."). (Changed 2026-10-08 at the owner's word, "Let's reduce the wordage by removing the 'you'"; until then "You spoke 3 weeks ago" and "You haven't spoken yet".) Minutes matter in two places, and both use `formatRelativeFine` from the same file ("just now" under a minute, lowercase because it follows a label: "Last synced just now"; then minutes and hours as plurals, then the day-grained words above): a sync status row in Settings ("Last synced 5 minutes ago"), so it never says "today" about a sync that finished a moment ago, and Home's calls waiting for a note ("14 min · 2 hours ago", HOME-14, added 2026-10-07), which are all from the last day, so "today" would tell them apart from nothing. Times of day follow the phone's 12 or 24 hour setting, and so do the tick labels under a 24-hour strip ("12a 6a 12p 6p" or "00 06 12 18"), the Week screen's hour labels, which are clock times like any other ("3am", "12pm" or "03:00", "15:00", HOME-13), and the time picker's dial; the am/pm marker is the language's own.

**People, not contacts.** The people in Orbit are "people" ("Add people", "Ignored 3 people", "Move 1 person"). "Contacts" means only the phone's own address book ("Contacts access is off", "Open in Contacts", "Re-link to a phone contact").

## Where copy lives

Added 2026-10-05 ([UX rubric](../../vision/ux-rubric.md) 3.4: every string can be translated). The words above are decided here; the strings themselves live in Android resources, never as literals in Kotlin.

- **One file per area** in `android/app/src/main/res/values/`: `strings_home.xml`, `strings_card.xml`, `strings_lists.xml` (List settings, including the nudge schedule editor), `strings_onboarding.xml`, `strings_settings.xml`, `strings_contact.xml` (a person's page and its sheets), `strings_calllog.xml`, `strings_browse.xml` (Browse and Search), `strings_picker.xml` (both pickers and their commit snackbars), `strings_notify.xml` (the nudge notification and the notification after a call, and their channels), `strings_note.xml` (the page for writing about a call), `strings_time.xml` (everything `RelativeTime.kt` says: spans, "ago", day headings, durations, day parts, axis ticks) and `strings_components.xml` (shared components; the chrome words every screen uses: Back, Go back, Cancel, Save, Done, Continue, Undo, Archive, Delete, Open settings, Open phone settings, Open details, and the privacy curtain's "Contact", "List" and "Someone"; and the snackbars several screens share, such as "Ignored {name}" and "Paused {name} for 1 week"). `strings.xml` keeps the app name, the widget copy and the launcher shortcuts. Content descriptions, click labels, custom accessibility actions, toasts and snackbars are copy too.
- **Keys are `area_what`** in snake_case, the area being the file's: `card_call`, `lists_snackbar_archived`, `home_menu_pause_nudges`, `components_action_undo`.
- **Positional arguments** (`%1$s`, `%2$d`), and **`<plurals>` wherever a count appears** ("1 person", "12 people"), even where English doesn't change, because other languages do. Keep a sentence whole and put the variable part in an argument; don't build copy by joining fragments. A short XML comment tells the translator what an argument is or where a string shows when that isn't obvious.
- **Composables** call `stringResource` / `pluralStringResource`. Text for a `semantics { }` block or another non-composable lambda is resolved in composition first and captured.
- **ViewModels never hold a Context.** User-facing text in UI state and one-off events (snackbars, `SnackbarEvent` and `HomeSnackbarEvent`) is `UiText` (`ui/util/UiText.kt`): `UiText.res(R.string.x, args)` or `UiText.plural(R.plurals.x, count, args)`, resolved by the composable with `asString()` (or `asString(context)` inside a snackbar collector). An argument may itself be a `UiText` ("Sarah will come up again {in 2 weeks}"). `UiText.Plain` is only for user data, such as a name the user typed; English copy is never wrapped in it. Tests either compare `UiText` values or resolve them against Robolectric resources (`ApplicationProvider.getApplicationContext()`).
- **Formatters, feeds and mappers return `UiText` too.** `formatSpan`, `formatRelative`, `formatDayHeader` and `formatDuration` (`ui/util/RelativeTime.kt`) build words without a Context; so do the data feeds and mappers that pre-format labels (`HomeFeed`, `ContactMapper`: `Contact.lastCalledLabel`, `avgLengthLabel`, `bestWindowLabel` are `UiText?`, null meaning "nothing to say yet", which each screen words itself). Clock times (`formatClockTime`, `formatWallClock`, `formatAbsolute`) stay `String`: digits plus the locale's own am/pm marker and month name, from java.time.
- **Notifications, widgets and toasts have a Context** and resolve on the spot: `context.getString(...)` or `UiText.asString(context)`. `notify/NotificationCopy.kt` still decides what a nudge says (which sentence, which name) and returns `UiText`; the words are `strings_notify.xml`'s, including the lock-screen version.
- **The domain layer holds no copy.** A use case returns data (`count`, a name) and the ViewModel builds the snackbar from resources; `UndoStack.PendingUndo` carries only the inverse.
- **Not copy**, so it stays in code: log tags, routes, DataStore keys, test tags, Compose animation labels, stored formats (the nudge schedule's `"HH:mm"`, pinned to `Locale.ROOT`), preview fixtures, exception messages nobody sees, and library and license names.
- **Translating.** Add `res/values-xx/` with the same keys. AGP generates the locale list from the folders that exist (`generateLocaleConfig` in `app/build.gradle.kts`; the default strings are declared English in `res/resources.properties`), so the new language appears in Android 13's per-app language setting with no further wiring.

