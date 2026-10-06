package app.orbit.launcher

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * LAUNCH-02: the adaptive launcher icon and both shortcut icons carry a
 * `<monochrome>` layer, so Android 13+ can draw Orbit as a themed icon tinted
 * with the rest of the home screen. The resources are read as files from the
 * module directory, where Gradle runs the unit tests (the `PhosphorIconsTest`
 * precedent); whether the launcher tints the result is a device check.
 */
class LauncherIconTest {

    private val res = File("src/main/res")

    private val adaptiveIcons = listOf(
        "mipmap-anydpi-v26/ic_launcher.xml",
        "mipmap-anydpi-v26/ic_launcher_round.xml",
        "drawable/ic_shortcut_call_next.xml",
        "drawable/ic_shortcut_search.xml",
    )

    @Test
    fun everyAdaptiveIcon_declaresAMonochromeLayer() {
        val missing = adaptiveIcons.filter { path ->
            val file = File(res, path)
            assertTrue(file.isFile, "$path exists")
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            assertEquals("adaptive-icon", root.tagName, "$path is an adaptive icon")
            val monochrome = root.getElementsByTagName("monochrome")
            monochrome.length != 1 ||
                (monochrome.item(0) as org.w3c.dom.Element).getAttribute("android:drawable").isBlank()
        }
        assertTrue(missing.isEmpty(), "adaptive icons without a <monochrome android:drawable=...> layer: $missing")
    }
}
