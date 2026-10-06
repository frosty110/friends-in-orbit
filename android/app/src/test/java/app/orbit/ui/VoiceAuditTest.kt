package app.orbit.ui

import app.orbit.testutil.VoiceRules
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every translatable string resource, held to voice.md mechanically: no
 * exclamation marks, no dashes or arrows, no emoji, none of the never-say
 * phrases (including "due" as a deadline), "contact" only for the phone's own
 * address book, and a count always a `<plurals>`. `CopyAuditTest` checks what
 * the nudge formatters produce; this reads the files themselves, plain JUnit
 * over `src/main/res/values/strings*.xml` from the module directory, where
 * Gradle runs the unit tests (the `PhosphorIconsTest` precedent).
 *
 * There is no sentence-case heuristic: names, "Orbit", "Android", theme names
 * and the mid-sentence "Settings" make a capital letter ambiguous, and the
 * glossary review reads case by eye.
 *
 * Until 2026-10-06 nothing read the string files as a whole, and three audit
 * findings (a deadline "due", en dashes, an arrow) existed because of it.
 */
class VoiceAuditTest {

    private data class Copy(val file: String, val key: String, val text: String, val isPlural: Boolean) {
        val where: String get() = "$file:$key"
    }

    private val valuesDir = File("src/main/res/values")

    /**
     * The keys allowed to say "contact": the phone's own address book, its
     * permission and its app (voice.md "People, not contacts"), and the two
     * words the privacy curtain stands in with. Anything else that says it
     * fails here and gets a review.
     */
    private val addressBookKeys: List<Regex> = listOf(
        // The picker is the address book, start to finish.
        "picker_.*",
        // Settings: the Contacts section and permission row, the thresholds
        // over the address book, the call-history row, and the reset body
        // ("Your phone's contacts stay").
        "settings_section_contacts", "settings_perm_.*", "settings_thresholds_.*",
        "settings_call_history_sub", "settings_reset_dialog_body",
        // Onboarding's Contacts ask and what is lost without it.
        "onb_perm_contacts_.*", "onb_skip_lost_contacts", "onb_first_list_helper_no_contacts.*",
        "onb_welcome_beat_choice",
        // Empty states that name where people come from.
        "browse_search_empty_body", "calllog_empty_body", "lists_delete_body",
        // Smart-list percentages are over the address book.
        "lists_smart_percent_.*",
        // A person's page: the phone record behind them, and re-linking to one.
        "contact_menu_open_in_contacts", "contact_not_found_body", "contact_orphan_title", "contact_orphan_body",
        // The phone's contacts app.
        "components_toast_no_contacts_app",
        // The privacy curtain's stand-in word (voice.md "Where copy lives"),
        // and the unpause banner's heading under it.
        "components_curtain_contact", "contact_unpaused_heading",
    ).map { Regex(it) }

    /**
     * `<string>`s allowed to carry `%d`: the number is not a count of things,
     * so nothing in the sentence inflects with it.
     */
    private val numberNotCountKeys: List<Regex> = listOf(
        // Percentages.
        "lists_rule_summary_commonly_called", "lists_rule_summary_rarely_called",
        "lists_smart_top", "lists_smart_bottom", "lists_smart_percent.*",
        "settings_thresholds_commonly", "settings_thresholds_rarely",
        // Dates and clock durations: digits in a fixed order.
        "time_day_named", "time_day_named_year", "time_duration_hours_minutes",
        // A bare number beside a filter chip's label ("Recently called · 12").
        "picker_filter_applied",
    ).map { Regex(it) }

    private val contactWord = Regex("(?<!\\p{L})contacts?(?!\\p{L})", RegexOption.IGNORE_CASE)
    private val countArgument = Regex("%(\\d+\\$)?d")

    private fun translatableCopy(): List<Copy> {
        val files = valuesDir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }
            ?.sortedBy { it.name }.orEmpty()
        assertTrue(files.size >= 10, "string files found under $valuesDir: ${files.map { it.name }}")
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return files.flatMap { file ->
            val root = builder.parse(file).documentElement
            val nodes = root.childNodes
            (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
                .filter { it.getAttribute("translatable") != "false" }
                .flatMap { el ->
                    val key = el.getAttribute("name")
                    when (el.tag) {
                        "string" -> listOf(Copy(file.name, key, unescape(el.textContent), isPlural = false))
                        "plurals" -> {
                            val items = el.getElementsByTagName("item")
                            (0 until items.length).map { i ->
                                val item = items.item(i) as Element
                                Copy(file.name, "$key[${item.getAttribute("quantity")}]", unescape(item.textContent), isPlural = true)
                            }
                        }
                        else -> emptyList()
                    }
                }
        }
    }

    private val Element.tag: String get() = tagName

    /** Android's resource escapes, so the rules see what the user reads. */
    private fun unescape(raw: String): String =
        raw.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("%%", "%")

    @Test
    fun everyString_followsTheVoiceRules() {
        val broken = translatableCopy()
            .map { it to VoiceRules.violations(it.text) }
            .filter { (_, found) -> found.isNotEmpty() }
            .map { (copy, found) -> "${copy.where} (${found.joinToString()}): \"${copy.text}\"" }
        assertTrue(broken.isEmpty(), "strings that break voice.md:\n" + broken.joinToString("\n"))
    }

    @Test
    fun contactMeansOnlyThePhonesAddressBook() {
        val offenders = translatableCopy()
            .filter { contactWord.containsMatchIn(it.text) }
            .filter { copy -> addressBookKeys.none { it.matches(copy.key.substringBefore('[')) } }
            .map { "${it.where}: \"${it.text}\"" }
        assertTrue(
            offenders.isEmpty(),
            "the people in Orbit are people; \"contact\" is the phone's address book (voice.md). " +
                "Reword these, or add the key to addressBookKeys with its reason:\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun everyCount_isAPlural() {
        val offenders = translatableCopy()
            .filter { !it.isPlural && countArgument.containsMatchIn(it.text) }
            .filter { copy -> numberNotCountKeys.none { it.matches(copy.key) } }
            .map { "${it.where}: \"${it.text}\"" }
        assertTrue(
            offenders.isEmpty(),
            "a count belongs in a <plurals>, even where English does not change (voice.md). " +
                "Convert these, or add the key to numberNotCountKeys if the number is not a count:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun theAllowLists_nameOnlyKeysThatExist() {
        // An allow-list entry for a key that was renamed or deleted would
        // silently stop guarding anything.
        val keys = translatableCopy().map { it.key.substringBefore('[') }.toSet()
        val stale = (addressBookKeys + numberNotCountKeys).filter { pattern -> keys.none { pattern.matches(it) } }
        assertTrue(stale.isEmpty(), "allow-list entries matching no string: ${stale.map { it.pattern }}")
    }
}
