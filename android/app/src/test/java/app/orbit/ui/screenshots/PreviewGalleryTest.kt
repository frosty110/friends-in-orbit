package app.orbit.ui.screenshots

import android.app.Application
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
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
        captureScreenRoboImage(File(outputDir, fileName(preview, night, fontScale)).path)
    }

    companion object {
        private const val SETTLE_MS = 2_000L

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
            return "$owner.${p.methodName}$index-$mode$scale.png"
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
