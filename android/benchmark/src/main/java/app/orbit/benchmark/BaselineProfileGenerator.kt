package app.orbit.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records a Baseline Profile from a real launch. Run on a device, then copy
 * the generated `*-baseline-prof.txt` over `app/src/main/baseline-prof.txt`
 * (the hand-written starter that ships until then).
 *
 * The journey is the launch and first screen only: deeper journeys (Card
 * view, a swipe) need a device with onboarding finished and a list, which a
 * fresh benchmark install does not have.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = TARGET_PACKAGE) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
    }
}
