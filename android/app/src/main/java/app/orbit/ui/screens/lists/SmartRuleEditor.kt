package app.orbit.ui.screens.lists

import android.content.res.Configuration
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.R
import app.orbit.domain.smart.SmartListRule
import app.orbit.ui.components.OrbitWheelPicker
import app.orbit.ui.components.dayLandmark
import app.orbit.ui.theme.OrbitTheme

/**
 * Smart-list rule parameter editor (SMART-06).
 *
 * Dispatches over the [SmartListRule] sealed family with one branch per
 * subtype, each setting chosen on the shared day or number wheel
 * ([OrbitWheelPicker], ADR 0011):
 *  - [SmartListRule.RecentlyAddedNotCalled] → days, 7..180 (default 30)
 *  - [SmartListRule.LongGap] → days, 1..365
 *  - [SmartListRule.CommonlyCalled] → percent, 10..50 (default 20)
 *  - [SmartListRule.RarelyCalled] → percent, 10..50 (default 50)
 *  - [SmartListRule.NeverCalled] → no-params placeholder
 *
 * Until 2026-10-07 the days were a slider (7 to 180, a day a few pixels
 * wide), the long gap a typed number field (the one input on List settings
 * that raised the keyboard, mid-page where it landed), and the percentages
 * sliders that moved continuously and told TalkBack 2% at a time. One wheel
 * now: precise, flicked, no keyboard.
 *
 * A stored value outside a wheel's usual range widens the range to include
 * it rather than being clamped: showing a different number than the rule runs,
 * and re-saving it on the first touch, is the bug ADR 0010 recorded.
 *
 * Each rule reads as one sentence over its wheel, with the current setting as
 * the sentence's argument ("Added in the last 30 days", "The top 20% of the
 * people you call"), so a translator can order the words (voice.md: keep a
 * sentence whole). The sentence follows the wheel as it turns and names it
 * for TalkBack; the wheel's value is its own words ("30 days").
 *
 * Save-on-change: each wheel commits once, when it comes to rest.
 *
 * Token-clean: no inline color hex literals, no RoundedCornerShape, no fontSize literals.
 */
@Composable
fun SmartRuleEditor(
    rule: SmartListRule,
    onChange: (SmartListRule) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x4)) {
        when (rule) {
            is SmartListRule.RecentlyAddedNotCalled -> DaysWheel(
                sentenceRes = R.plurals.lists_smart_added_within,
                value = rule.daysWindow,
                usualRange = 7..180,
                onCommit = { onChange(rule.copy(daysWindow = it)) },
            )
            is SmartListRule.LongGap -> DaysWheel(
                sentenceRes = R.plurals.lists_smart_no_call_in,
                value = rule.daysThreshold,
                usualRange = 1..365,
                onCommit = { onChange(rule.copy(daysThreshold = it)) },
            )
            is SmartListRule.CommonlyCalled -> PercentWheel(
                sentenceRes = R.string.lists_smart_top,
                value = rule.topPercent,
                onCommit = { onChange(rule.copy(topPercent = it)) },
            )
            is SmartListRule.RarelyCalled -> PercentWheel(
                sentenceRes = R.string.lists_smart_bottom,
                value = rule.bottomPercent,
                onCommit = { onChange(rule.copy(bottomPercent = it)) },
            )
            SmartListRule.NeverCalled -> NoParamsPlaceholder()
        }
    }
}

/** [usual] widened to include [value], so a stored setting is never clamped. */
internal fun rangeIncluding(usual: IntRange, value: Int): IntRange =
    minOf(usual.first, value)..maxOf(usual.last, value)

@Composable
private fun DaysWheel(
    @PluralsRes sentenceRes: Int,
    value: Int,
    usualRange: IntRange,
    onCommit: (Int) -> Unit,
) {
    var shown by remember(value) { mutableIntStateOf(value) }
    val sentence = pluralStringResource(sentenceRes, shown, shown)
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = sentence,
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            modifier = Modifier.fillMaxWidth(),
        )
        OrbitWheelPicker(
            value = value,
            range = rangeIncluding(usualRange, value),
            onValueChange = { shown = it },
            onValueCommit = onCommit,
            label = sentence,
            valueDescription = { d -> pluralStringResource(R.plurals.lists_smart_days, d, d) },
            caption = { d -> dayLandmark(d) },
            modifier = Modifier.padding(top = OrbitTheme.spacing.x3),
        )
    }
}

@Composable
private fun PercentWheel(
    @StringRes sentenceRes: Int,
    value: Int,
    onCommit: (Int) -> Unit,
) {
    var shown by remember(value) { mutableIntStateOf(value) }
    val sentence = stringResource(sentenceRes, shown)
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = sentence,
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            modifier = Modifier.fillMaxWidth(),
        )
        OrbitWheelPicker(
            value = value,
            range = rangeIncluding(10..50, value),
            onValueChange = { shown = it },
            onValueCommit = onCommit,
            label = sentence,
            valueDescription = { p -> stringResource(R.string.lists_smart_percent_a11y, p) },
            modifier = Modifier.padding(top = OrbitTheme.spacing.x3),
        )
    }
}

@Composable
private fun NoParamsPlaceholder() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = OrbitTheme.spacing.x1),
    ) {
        Text(
            text = stringResource(R.string.lists_smart_no_params),
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
        )
    }
}

// region Previews

@Preview(name = "SmartRuleEditor — RecentlyAddedNotCalled, light", showBackground = true)
@Composable
private fun SmartRuleEditorRecentlyAddedLightPreview() {
    OrbitTheme(darkTheme = false) {
        Box(
            modifier = Modifier.background(OrbitTheme.colors.surface).padding(OrbitTheme.spacing.x2),
        ) {
            SmartRuleEditor(
                rule = SmartListRule.RecentlyAddedNotCalled(daysWindow = 30),
                onChange = {},
            )
        }
    }
}

@Preview(name = "SmartRuleEditor: LongGap, light", showBackground = true)
@Composable
private fun SmartRuleEditorLongGapLightPreview() {
    OrbitTheme(darkTheme = false) {
        Box(
            modifier = Modifier.background(OrbitTheme.colors.surface).padding(OrbitTheme.spacing.x2),
        ) {
            SmartRuleEditor(
                rule = SmartListRule.LongGap(daysThreshold = 90),
                onChange = {},
            )
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "SmartRuleEditor: CommonlyCalled, dark", showBackground = true)
@Composable
private fun SmartRuleEditorCommonlyCalledDarkPreview() {
    OrbitTheme(darkTheme = true) {
        Box(
            modifier = Modifier.background(OrbitTheme.colors.surface).padding(OrbitTheme.spacing.x2),
        ) {
            SmartRuleEditor(
                rule = SmartListRule.CommonlyCalled(topPercent = 20),
                onChange = {},
            )
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "SmartRuleEditor — NeverCalled, dark", showBackground = true)
@Composable
private fun SmartRuleEditorNeverCalledDarkPreview() {
    OrbitTheme(darkTheme = true) {
        Box(
            modifier = Modifier.background(OrbitTheme.colors.surface).padding(OrbitTheme.spacing.x2),
        ) {
            SmartRuleEditor(
                rule = SmartListRule.NeverCalled,
                onChange = {},
            )
        }
    }
}

// endregion
