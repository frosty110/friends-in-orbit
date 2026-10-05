# Voice and content rules

**Status:** active
**Last reviewed:** 2026-10-05
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
- **Active voice. Present tense.** "You have 3 people due" not "3 people are due for you."
- **16sp minimum body size.** Used in emotionally loaded moments — don't make people squint.

## Never say

- Streaks, achievements, levels, XP, rewards, unlocks
- "You haven't called X in N days" — shame framing
- "Crush your goals," "stay on track," "beat your record" — hustle framing
- "Great job," "awesome," "keep it going!" — coach framing
- Emoji, unicode glyphs, ASCII art in product copy
- "The contact," "the user," "the entity" — clinical framing

## Always say

- "Patterns," "rhythms," "gaps" — neutral temporal framing
- "Your people" — possessive + human
- "All quiet for now" for the moment nobody is due. (Until 2026-10-05 this said "You're caught up". The queue is continuous by design since the tide-marker change of 2026-05-08, and Home's HOME-6 retired "caught up" and "due" language, so nothing should read as a cleared backlog.)
- "Want to..." — soft invitation, never demand
- "Surprise me" — the lightweight serendipity affordance

## Empty states — tone reference

- No one due: "All quiet for now." Then who comes up next and when, and a way to browse anyway.
- No lists yet: quiet instruction, no urgency
- Permission denied: plain explanation of what's lost, offer to continue without

## Glossary: one word for one idea

Added 2026-10-05 ([UX rubric](../../vision/ux-rubric.md) D7). The same idea had up to four names on screen; a reader should never wonder whether two words mean two things.

| Say | Means | Don't say |
|---|---|---|
| **Later** | Move this person further out on this list. | Skip, defer, pass, snooze |
| **Sooner** | Bring this person forward on this list. | Surface sooner, move up, boost |
| **Nudge** | The notification Orbit sends when someone on a list is worth a call. | Reminder, prompt, notification (in UI copy), alert |
| **List** | A group of people the user keeps in touch with, with its own rhythm. | Orbit (as a noun for a list), group, circle |
| **Rhythm** | How often the user means to talk to people on a list ("every 2 weeks"), and the 7-day strip on Home. | Cadence, frequency, interval, threshold |
| **Call** | A phone call, which Orbit sees in the call log. | Interaction, touchpoint |
| **Connection** | A conversation Orbit couldn't see (WhatsApp, a visit), added by hand with "Log a connection". Only for those. | Using it for a phone call |
| **Note** | Something the user wrote about a person. | Memo, comment |
| **Pause** | Stop nudges for a person or list for a while. | Mute (for people), suspend |

**Time since a call** is always worded by one formatter (`ui/util/RelativeTime.kt`), the same way everywhere: "today", "yesterday", "3 days ago", "2 weeks ago", "3 months ago". Never "27 days ago" on one screen and "3 weeks" on another. Times of day follow the phone's 12 or 24 hour setting, and so do the tick labels under a 24-hour strip ("12a 6a 12p 6p" or "00 06 12 18") and the time picker's dial; the am/pm marker is the language's own.

**People, not contacts.** The people in Orbit are "people" ("Add people", "Ignored 3 people", "Move 1 person"). "Contacts" means only the phone's own address book ("Contacts access is off", "Open in Contacts", "Re-link to a phone contact").

## Where copy lives

Added 2026-10-05 ([UX rubric](../../vision/ux-rubric.md) 3.4: every string can be translated). The words above are decided here; the strings themselves live in Android resources, never as literals in Kotlin.

- **One file per area** in `android/app/src/main/res/values/`: `strings_home.xml`, `strings_card.xml`, `strings_lists.xml` (List settings, including the nudge schedule editor), `strings_onboarding.xml`, `strings_settings.xml`, `strings_contact.xml` (a person's page and its sheets), `strings_calllog.xml`, `strings_browse.xml` (Browse and Search), `strings_picker.xml` (both pickers and their commit snackbars), `strings_notify.xml` (the nudge notification and its channel), `strings_time.xml` (everything `RelativeTime.kt` says: spans, "ago", day headings, durations, day parts, axis ticks) and `strings_components.xml` (shared components; the chrome words every screen uses: Back, Cancel, Save, Done, Continue, Undo, Archive, Delete, and the privacy curtain's "Contact", "List" and "Someone"; and the snackbars several screens share, such as "Ignored {name}" and "Paused {name} for 1 week"). `strings.xml` keeps the app name, the widget copy and the launcher shortcuts. Content descriptions, click labels, custom accessibility actions, toasts and snackbars are copy too.
- **Keys are `area_what`** in snake_case, the area being the file's: `card_call`, `lists_snackbar_archived`, `home_menu_pause_nudges`, `components_action_undo`.
- **Positional arguments** (`%1$s`, `%2$d`), and **`<plurals>` wherever a count appears** ("1 person", "12 people"), even where English doesn't change, because other languages do. Keep a sentence whole and put the variable part in an argument; don't build copy by joining fragments. A short XML comment tells the translator what an argument is or where a string shows when that isn't obvious.
- **Composables** call `stringResource` / `pluralStringResource`. Text for a `semantics { }` block or another non-composable lambda is resolved in composition first and captured.
- **ViewModels never hold a Context.** User-facing text in UI state and one-off events (snackbars, `SnackbarEvent` and `HomeSnackbarEvent`) is `UiText` (`ui/util/UiText.kt`): `UiText.res(R.string.x, args)` or `UiText.plural(R.plurals.x, count, args)`, resolved by the composable with `asString()` (or `asString(context)` inside a snackbar collector). An argument may itself be a `UiText` ("Sarah will come up again {in 2 weeks}"). `UiText.Plain` is only for user data, such as a name the user typed; English copy is never wrapped in it. Tests either compare `UiText` values or resolve them against Robolectric resources (`ApplicationProvider.getApplicationContext()`).
- **Formatters, feeds and mappers return `UiText` too.** `formatSpan`, `formatRelative`, `formatDayHeader` and `formatDuration` (`ui/util/RelativeTime.kt`) build words without a Context; so do the data feeds and mappers that pre-format labels (`HomeFeed`, `ContactMapper`: `Contact.lastCalledLabel`, `avgLengthLabel`, `bestWindowLabel` are `UiText?`, null meaning "nothing to say yet", which each screen words itself). Clock times (`formatClockTime`, `formatWallClock`, `formatAbsolute`) stay `String`: digits plus the locale's own am/pm marker and month name, from java.time.
- **Notifications, widgets and toasts have a Context** and resolve on the spot: `context.getString(...)` or `UiText.asString(context)`. `notify/NotificationCopy.kt` still decides what a nudge says (which sentence, which name) and returns `UiText`; the words are `strings_notify.xml`'s, including the lock-screen version.
- **The domain layer holds no copy.** A use case returns data (`count`, a name) and the ViewModel builds the snackbar from resources; `UndoStack.PendingUndo` carries only the inverse.
- **Not copy**, so it stays in code: log tags, routes, DataStore keys, test tags, Compose animation labels, stored formats (the nudge schedule's `"HH:mm"`, pinned to `Locale.ROOT`), preview fixtures, exception messages nobody sees, and library and license names.
- **Translating.** Add `res/values-xx/` with the same keys. AGP generates the locale list from the folders that exist (`generateLocaleConfig` in `app/build.gradle.kts`; the default strings are declared English in `res/resources.properties`), so the new language appears in Android 13's per-app language setting with no further wiring.

