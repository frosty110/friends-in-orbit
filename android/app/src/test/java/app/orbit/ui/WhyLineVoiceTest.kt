package app.orbit.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.testutil.VoiceRules
import app.orbit.ui.screens.card.cardWhySince
import app.orbit.ui.screens.home.homeRecencyWhy
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The why-now line as a person reads it, held to voice.md. [VoiceAuditTest]
 * reads the string files, where the span is a `%1$s` placeholder, so it
 * cannot see a never-say phrase that arrives through an argument: until
 * 2026-10-06 "%1$s since you last spoke" rendered "3 days since you last
 * spoke." for every gap under two weeks, the shame framing `VoiceRules`
 * forbids ("days since"). This test renders Card view's and Home's builders
 * for one gap in each of the formatter's buckets (days, weeks, months, years)
 * plus today and yesterday, so an argument can no longer carry a forbidden
 * word past the audit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WhyLineVoiceTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")

    /** One gap per bucket of `spanBucket`, plus the two day words. */
    private val gaps: List<Long> = listOf(0L, 1L, 3L, 21L, 90L, 400L)

    private fun UiText.text(): String = asString(context)

    private fun days(n: Long): Duration = Duration.ofDays(n)

    @Test
    fun `the card's why line breaks no voice rule for any gap`() {
        gaps.forEach { days ->
            val line = cardWhySince(days).text()
            assertTrue(
                VoiceRules.violations(line).isEmpty(),
                "card why line for $days days breaks voice.md: \"$line\" " +
                    "(${VoiceRules.violations(line).joinToString()})",
            )
        }
    }

    @Test
    fun `Home's why line breaks no voice rule for any gap, nor when you have never spoken`() {
        val lastCalls: List<Instant?> = gaps.map { now.minus(Duration.ofDays(it)) } + null
        lastCalls.forEach { lastCalledAt ->
            val line = homeRecencyWhy(lastCalledAt, now).text()
            assertTrue(
                VoiceRules.violations(line).isEmpty(),
                "Home why line for $lastCalledAt breaks voice.md: \"$line\" " +
                    "(${VoiceRules.violations(line).joinToString()})",
            )
        }
    }

    @Test
    fun `both lines say when you spoke, in the app's one ago wording`() {
        assertEquals("You spoke today.", cardWhySince(0).text())
        assertEquals("You spoke yesterday.", cardWhySince(1).text())
        assertEquals("You spoke 3 days ago.", cardWhySince(3).text())
        assertEquals("You spoke 3 weeks ago.", cardWhySince(21).text())
        assertEquals("You spoke today", homeRecencyWhy(now, now).text())
        assertEquals("You spoke 3 days ago", homeRecencyWhy(now.minus(days(3)), now).text())
        assertEquals("You spoke 3 weeks ago", homeRecencyWhy(now.minus(days(21)), now).text())
        assertEquals("You haven't spoken yet", homeRecencyWhy(null, now).text())
    }
}
