package app.orbit.ui.screenshots

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.theme.OrbitTheme
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.AfterClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview
import java.io.File

/**
 * Renders every `@Preview` in the app to a PNG on the JVM, so visual changes
 * are reviewed as images rather than inferred from code. Robolectric's native
 * graphics draw the real Compose tree; nothing is mocked.
 *
 * Each preview renders exactly as annotated: `@PreviewLightDark` gives a light
 * and a dark image, `@PreviewFontScale` one per scale. Dark mode and font
 * scale are applied through [LocalConfiguration] and [LocalDensity], which is
 * what `isSystemInDarkTheme()` and `sp` read, so one activity serves every
 * variant without being recreated.
 *
 * Runs only with `-Pscreenshots` (see app/build.gradle.kts) and writes to
 * `build/screenshots/`. It records, it does not compare: the images are for
 * people to look at, and committing goldens is a later decision.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class PreviewGalleryTest(private val preview: ComposablePreview<AndroidPreviewInfo>) {

    @get:Rule val compose = createComposeRule()

    @Test
    fun capture() {
        val info = preview.previewInfo
        val night = (info.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val fontScale = if (info.fontScale > 0f) info.fontScale else 1f

        // -Porbit.screenshots.qualifiers=w360dp-h740dp (or a landscape set)
        // renders at another size, for gate G3 (360dp phones, landscape).
        qualifiers?.let { RuntimeEnvironment.setQualifiers(it) }
        // A focused text field blinks its cursor forever, so Compose never
        // reports idle. Drive the clock by hand instead: long enough for every
        // finite entry animation to land, then capture.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val base = LocalConfiguration.current
            val config = Configuration(base).apply {
                uiMode = (base.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                this.fontScale = fontScale
            }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalDensity provides Density(density.density, fontScale),
                LocalPrivacyCurtain provides (curtainMode || LocalPrivacyCurtain.current),
            ) {
                // A component preview draws no surface of its own (in the app
                // its screen does), so the host paints the theme's background
                // first; otherwise a dark preview puts light text on the
                // window's white and cannot be judged. The host follows the
                // preview's configured night mode, which is why a dark preview
                // declares `uiMode = UI_MODE_NIGHT_YES` rather than only
                // forcing `OrbitTheme(darkTheme = true)` (28 of 143 dark
                // renders were unreadable before 2026-10-06).
                OrbitTheme {
                    Box(Modifier.fillMaxSize().background(OrbitTheme.colors.bg)) {
                        preview()
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        // The whole screen, not the root node, so dialogs (their own window)
        // are captured too.
        val name = fileName(preview, night, fontScale)
        captureScreenRoboImage(File(outputDir, name).path)
        auditAccessibility(name.removeSuffix(".png"))
        if (curtainMode) auditCurtain(name.removeSuffix(".png"))
    }

    /**
     * PRIV-03 over every preview (-Porbit.screenshots.curtain): with the
     * privacy curtain down, no preview person's name or list name may reach
     * any text, field or TalkBack label. Every screen has to opt in to the
     * curtain, and three surfaces were found leaking one at a time
     * (2026-10-05), so this checks them all at once. Findings go to
     * build/screenshots/curtain-report.md; with -Porbit.a11y.strict they fail.
     *
     * A field is read through its EditableText, what is drawn and spoken. Its
     * InputText, the buffer exposed for autofill, is left out on purpose: a
     * masked field keeps its real text there (CurtainMask draws over the
     * buffer rather than replacing it, so a name field never saves "List").
     */
    private fun auditCurtain(preview: String) {
        val owner = preview.substringBefore('.')
        val leaks = if (owner in CURTAIN_EXEMPT) {
            emptyList()
        } else {
            compose.onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .flatMap { node ->
                    val c = node.config
                    c.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                        c.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                        listOfNotNull(c.getOrNull(SemanticsProperties.EditableText)?.text) +
                        listOfNotNull(c.getOrNull(SemanticsProperties.PaneTitle))
                }
                .filter { text -> FIXTURE_NAMES.any { it.containsMatchIn(text) } }
                .distinct()
        }
        // One finding per line: a text with a line break would read as two.
        File(curtainDir, "$preview.txt").writeText(leaks.joinToString("\n") { it.replace('\n', ' ') })
        if (leaks.isNotEmpty() && System.getProperty("orbit.a11y.strict") == "true") {
            throw AssertionError("Names under the curtain in $preview:\n" + leaks.joinToString("\n"))
        }
    }

    /**
     * Automated accessibility check over every rendered preview (UX rubric
     * 4.3, gate G2):
     *  - every enabled control with a click action must have a name TalkBack
     *    can read (its own content description or merged text), and must be
     *    at least 48x48dp (rules.md §Design 3);
     *  - every slider (a node with SetProgress) must carry a StateDescription,
     *    the words TalkBack reads in place of a bare percentage of the track
     *    (OrbitSlider's valueDescription, rubric D8);
     *  - every toggle (a node with ToggleableState) must carry a Role, so it
     *    announces as a switch or a checkbox and not as text with a state.
     * Findings go to build/screenshots/a11y-report.md; with -Porbit.a11y.strict
     * a finding fails the preview's test.
     *
     * What it does not check, so a "None" is not mistaken for "accessible":
     * pixels (what is drawn, text overflow or clipping, the contrast of the
     * render; ThemeContrastTest gates the tokens, not the pixels), roles
     * beyond the two above (a labelled 48dp control without Role.Button
     * passes), headings and pane titles (OrbitAppBarTest and the components'
     * own tests cover those), focus order, and motion.
     */
    private fun auditAccessibility(preview: String) {
        val density = compose.density.density
        val minPx = TAP_MIN_DP * density - 0.5f
        val nodes = compose.onAllNodes(SemanticsMatcher("any node") { true }).fetchSemanticsNodes()
        val controls = nodes.filter { SemanticsActions.OnClick in it.config && SemanticsProperties.Disabled !in it.config }
        val controlFindings = controls.mapNotNull { node ->
            val label = labelOf(node)
            // Layout size, not on-screen bounds: a control scrolled off or
            // clipped by a scrolling row is not too small, just not visible.
            val w = node.size.width.toFloat()
            val h = node.size.height.toFloat()
            if (w == 0f || h == 0f) return@mapNotNull null
            val problems = buildList {
                if (label.isEmpty()) add("no label")
                if (w < minPx || h < minPx) add("target ${(w / density).toInt()}x${(h / density).toInt()}dp")
            }
            if (problems.isEmpty()) null else "${label.ifEmpty { "(unlabelled)" }.take(40)}: ${problems.joinToString(", ")}"
        }
        val sliderFindings = nodes
            .filter { SemanticsActions.SetProgress in it.config && SemanticsProperties.StateDescription !in it.config }
            .map { "${labelOf(it).ifEmpty { "(unlabelled)" }.take(40)}: slider with no state description" }
        val toggleFindings = nodes
            .filter { SemanticsProperties.ToggleableState in it.config && SemanticsProperties.Role !in it.config }
            .map { "${labelOf(it).ifEmpty { "(unlabelled)" }.take(40)}: toggle with no role" }
        val findings = controlFindings + sliderFindings + toggleFindings
        // One file per preview: Robolectric runs each test in a sandbox class
        // loader, so static state never reaches the @AfterClass report.
        File(a11yDir, "$preview.txt").writeText((listOf("controls=${controls.size}") + findings).joinToString("\n"))
        if (findings.isNotEmpty()) {
            if (System.getProperty("orbit.a11y.strict") == "true") {
                throw AssertionError("Accessibility findings in $preview:\n" + findings.joinToString("\n"))
            }
        }
    }

    /** What TalkBack would read for a node: its description, text, field text or click label. */
    private fun labelOf(node: SemanticsNode): String {
        val config = node.config
        return buildList {
            config.getOrNull(SemanticsProperties.ContentDescription)?.let { addAll(it) }
            config.getOrNull(SemanticsProperties.Text)?.let { texts -> addAll(texts.map { it.text }) }
            config.getOrNull(SemanticsProperties.EditableText)?.let { add(it.text) }
            config.getOrNull(SemanticsActions.OnClick)?.label?.let { add(it) }
        }.joinToString(" ").trim()
    }

    companion object {
        private const val SETTLE_MS = 2_000L
        private val curtainMode: Boolean = System.getProperty("orbit.screenshots.curtain") == "true"
        private val curtainDir: File by lazy { File(outputDir, "curtain").apply { mkdirs() } }

        // The people and list names the previews use. Whole words and case
        // sensitive, so "Sam" never matches "Same" and copy such as "the late
        // night rhythm" is not a name. "Late night" itself is left out: it is
        // also a smart-list rule's name, which is copy, not anyone's data.
        // Extend this when a preview adds a new name.
        private val FIXTURE_NAMES = listOf(
            "Avery", "Alex", "Sarah", "Priya", "Marcus", "Kai", "Mara", "Sam", "Jordan", "Bartholomew", "Maya",
            "Inner orbit", "People who ground me", "climbing gym",
        ).map { Regex("\\b${Regex.escape(it)}\\b") } +
            // Search's previews type "maya" into the field in lowercase, the
            // way a person types. Until 2026-10-06 the query was not in this
            // list, so the curtain run passed whether or not the search pill
            // masked what was typed (browse-8).
            Regex("\\bmaya\\b")

        // Previews exempt from the curtain check, each for a reason:
        // - copy, not anyone's data: the template and rule pickers offer
        //   "Inner orbit" and "Late night" by name;
        // - components that take an already-masked label, or the curtain as a
        //   parameter, from their screen (the screens are checked): Chip;
        //   RhythmDaySheet (whose previews pass curtain = false on purpose;
        //   Home passes the real value); UnpauseBanner and RuleOverrideSection
        //   (Contact detail passes the curtain and a masked list name; its own
        //   curtain previews are clean). Home's stack of calls waiting for a
        //   note (NotesWaitingStack) replaced PostCallBanner on 2026-10-07 and
        //   reads the curtain itself, so it is audited, not exempt.
        private val CURTAIN_EXEMPT = setOf(
            "RuleTemplatePicker", "SmartRuleEditor", "CreateListBottomSheet",
            "Chip", "RhythmDaySheet", "UnpauseBanner", "RuleOverrideSection",
        )
        private val qualifiers: String? = System.getProperty("orbit.screenshots.qualifiers")?.takeIf { it.isNotBlank() }
        private const val TAP_MIN_DP = 48f

        private val a11yDir: File by lazy { File(outputDir, "a11y").apply { mkdirs() } }

        @JvmStatic
        @AfterClass
        fun writeAccessibilityReport() {
            val files = a11yDir.listFiles { f -> f.extension == "txt" }.orEmpty().sortedBy { it.name }
            var controlsChecked = 0
            val a11yFindings = sortedMapOf<String, List<String>>()
            files.forEach { f ->
                val lines = f.readLines()
                controlsChecked += lines.firstOrNull()?.substringAfter("controls=")?.toIntOrNull() ?: 0
                if (lines.size > 1) a11yFindings[f.nameWithoutExtension] = lines.drop(1)
            }
            val previewsChecked = files.size
            val report = buildString {
                appendLine("# Accessibility findings")
                appendLine()
                appendLine("Generated by PreviewGalleryTest: enabled clickable nodes with no label, or under 48x48dp; sliders with no state description; toggles with no role.")
                appendLine()
                appendLine("Checked $controlsChecked controls across $previewsChecked previews.")
                appendLine()
                if (a11yFindings.isEmpty()) appendLine("None.")
                a11yFindings.forEach { (preview, items) ->
                    appendLine("## $preview")
                    items.forEach { appendLine("- $it") }
                    appendLine()
                }
            }
            File(outputDir, "a11y-report.md").writeText(report)
            if (curtainMode) writeCurtainReport()
        }

        private fun writeCurtainReport() {
            val files = curtainDir.listFiles { f -> f.extension == "txt" }.orEmpty().sortedBy { it.name }
            val leaks = files.associate { it.nameWithoutExtension to it.readText().lines().filter(String::isNotBlank) }
                .filterValues { it.isNotEmpty() }
            val report = buildString {
                appendLine("# Privacy curtain findings")
                appendLine()
                appendLine("Generated by PreviewGalleryTest with the curtain down (PRIV-03): preview names that still reach text, fields or TalkBack labels.")
                appendLine()
                appendLine("Checked ${files.size} previews.")
                appendLine()
                if (leaks.isEmpty()) appendLine("None.")
                leaks.forEach { (preview, items) ->
                    appendLine("## $preview")
                    items.forEach { appendLine("- $it") }
                    appendLine()
                }
            }
            File(outputDir, "curtain-report.md").writeText(report)
        }

        // Dialogs whose text field takes focus on open. Under Robolectric
        // their window never reports idle (60s timeout), even with the clock
        // paused, so they cannot be captured on the JVM. Review them on a
        // device; everything else in the app renders here.
        private val NEVER_IDLE = setOf(
            "RenameListDialogLightPreview",
            "RenameListDialogDarkPreview",
            "CreateListNameDialogPreview",
        )

        private val outputDir: File by lazy {
            File(System.getProperty("orbit.screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        }

        private fun fileName(p: ComposablePreview<AndroidPreviewInfo>, night: Boolean, fontScale: Float): String {
            val owner = p.declaringClass.substringAfterLast('.').removeSuffix("Kt")
            val mode = if (night) "dark" else "light"
            val scale = if (fontScale != 1f) "-font${fontScale.toString().replace('.', '_')}" else ""
            val index = p.previewIndex?.takeIf { it > 0 }?.let { "-$it" }.orEmpty()
            val size = qualifiers?.let { "-" + it.replace(Regex("[^A-Za-z0-9]+"), "_") }.orEmpty()
            val curtain = if (curtainMode) "-curtain" else ""
            return "$owner.${p.methodName}$index-$mode$scale$size$curtain.png"
        }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun previews(): List<ComposablePreview<AndroidPreviewInfo>> {
            // -Porbit.screenshots.only=<regex> renders just the matching
            // previews (matched against "File.method"), for fast iteration.
            val only = System.getProperty("orbit.screenshots.only")?.takeIf { it.isNotBlank() }?.toRegex()
            return AndroidComposablePreviewScanner()
                .scanPackageTrees("app.orbit")
                .includePrivatePreviews()
                .getPreviews()
                .filter { p -> p.methodName !in NEVER_IDLE }
                .filter { p -> only == null || only.containsMatchIn("${p.declaringClass.substringAfterLast('.')}.${p.methodName}") }
        }
    }
}
