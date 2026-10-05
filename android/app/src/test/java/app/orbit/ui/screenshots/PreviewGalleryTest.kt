package app.orbit.ui.screenshots

import android.app.Application
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
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
            ) {
                preview()
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        // The whole screen, not the root node, so dialogs (their own window)
        // are captured too.
        val name = fileName(preview, night, fontScale)
        captureScreenRoboImage(File(outputDir, name).path)
        auditAccessibility(name.removeSuffix(".png"))
    }

    /**
     * Automated accessibility check over every rendered preview (UX rubric
     * 4.3, gate G2): every enabled control with a click action must have a
     * name TalkBack can read (its own content description or merged text),
     * and must be at least 48x48dp (rules.md §Design 3). Findings go to
     * build/screenshots/a11y-report.md; with -Porbit.a11y.strict a finding
     * fails the preview's test.
     */
    private fun auditAccessibility(preview: String) {
        val density = compose.density.density
        val minPx = TAP_MIN_DP * density - 0.5f
        val nodes = compose.onAllNodes(SemanticsMatcher("any node") { true }).fetchSemanticsNodes()
        val controls = nodes.filter { SemanticsActions.OnClick in it.config && SemanticsProperties.Disabled !in it.config }
        val findings = controls.mapNotNull { node ->
            val config = node.config
            val label = buildList {
                config.getOrNull(SemanticsProperties.ContentDescription)?.let { addAll(it) }
                config.getOrNull(SemanticsProperties.Text)?.let { texts -> addAll(texts.map { it.text }) }
                config.getOrNull(SemanticsProperties.EditableText)?.let { add(it.text) }
                config.getOrNull(SemanticsActions.OnClick)?.label?.let { add(it) }
            }.joinToString(" ").trim()
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
        // One file per preview: Robolectric runs each test in a sandbox class
        // loader, so static state never reaches the @AfterClass report.
        File(a11yDir, "$preview.txt").writeText((listOf("controls=${controls.size}") + findings).joinToString("\n"))
        if (findings.isNotEmpty()) {
            if (System.getProperty("orbit.a11y.strict") == "true") {
                throw AssertionError("Accessibility findings in $preview:\n" + findings.joinToString("\n"))
            }
        }
    }

    companion object {
        private const val SETTLE_MS = 2_000L
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
                appendLine("Generated by PreviewGalleryTest: enabled clickable nodes with no label, or under 48x48dp.")
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
            return "$owner.${p.methodName}$index-$mode$scale$size.png"
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
