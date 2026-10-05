package app.orbit.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the icon set that PhIcon draws from.
 *
 * Two real bugs this would have caught: a missing icon ("speaker-slash" was
 * referenced but never shipped, so the control drew blank), and fill-only
 * icons converted as transparent (the "more" dots, the dots in "info" and
 * "warning", "minus", the drag handle), because the converter read an unset
 * SVG fill as none. Plain file checks on the JVM; no Android needed.
 * Gradle runs unit tests from the module directory, so paths are relative to
 * android/app.
 */
class PhosphorIconsTest {

    private val drawables = File("src/main/res/drawable")
    private val sources = File("src/main/java")

    @Test
    fun `every generated icon path paints something`() {
        val invisible = drawables.listFiles { f -> f.name.startsWith("ph_") && f.name.endsWith(".xml") }!!
            .flatMap { file ->
                PATH.findAll(file.readText()).mapNotNull { m ->
                    val attrs = m.value
                    val filled = FILL.find(attrs)?.groupValues?.get(1)?.let { it != "#00000000" } ?: false
                    val stroked = attrs.contains("android:strokeColor")
                    if (filled || stroked) null else file.name
                }.toList()
            }
        assertTrue("Icons with a path that draws nothing: ${invisible.distinct()}", invisible.isEmpty())
    }

    @Test
    fun `every icon name used in the app exists`() {
        val used = sources.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file -> ICON_NAME.findAll(file.readText()).map { it.groupValues[1] to file.name } }
            .toList()
        val missing = used.filter { (name, _) -> name !in PhosphorIcons }.distinct()
        assertTrue("Icon names with no VectorDrawable: $missing", missing.isEmpty())
        assertTrue("Expected to find icon usages; is the source path right?", used.size > 20)
    }

    private companion object {
        val PATH = Regex("<path[^>]*/>", RegexOption.DOT_MATCHES_ALL)
        val FILL = Regex("""android:fillColor="([^"]+)"""")
        // PhIcon(name = "x"), icon = "x", leadingIcon = "x", OrbitIconButton("x", ...)
        val ICON_NAME = Regex("""(?:PhIcon\(\s*name\s*=\s*|\bicon\s*=\s*|leadingIcon\s*=\s*|OrbitIconButton\(\s*(?:icon\s*=\s*)?)"([a-z][a-z0-9-]+)"""")
    }
}
