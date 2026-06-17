package app.orbit.domain.rule

import app.orbit.data.entity.RuleKind
import app.orbit.data.entity.RuleTemplateEntity
import app.orbit.domain.JsonProvider
import app.orbit.domain.ruleTemplateFixture
import kotlin.test.assertFails
import kotlin.test.assertTrue
import org.junit.Test

/**
 * DOM-03/DOM-04 — [engineFor] dispatch. Verifies the params overload picks the
 * right engine per sealed subtype, and that the template overload dispatches on
 * the *decoded params*, not on `template.kind` (the documented contract).
 */
class EngineFactoryTest {

    @Test
    fun `params overload maps each subtype to its engine`() {
        assertTrue(engineFor(RuleParams.KeepInTouch()) is KeepInTouchEngine)
        assertTrue(engineFor(RuleParams.LateNight()) is LateNightEngine)
        assertTrue(engineFor(RuleParams.Energize()) is EnergizeEngine)
    }

    @Test
    fun `template overload dispatches on decoded params, not on kind`() {
        // kind says KEEP_IN_TOUCH but the stored params are Energize — the engine
        // must follow the params so a kind/params mismatch can't silently run the
        // wrong engine.
        val template = ruleTemplateFixture(
            kind = RuleKind.KEEP_IN_TOUCH,
            params = RuleParams.Energize(),
        )

        assertTrue(engineFor(template, JsonProvider.json) is EnergizeEngine)
    }

    @Test
    fun `template overload throws on malformed params json`() {
        val template = RuleTemplateEntity(
            id = 1L,
            name = "broken",
            kind = RuleKind.KEEP_IN_TOUCH,
            paramsJson = "not valid json",
        )

        assertFails { engineFor(template, JsonProvider.json) }
    }
}
