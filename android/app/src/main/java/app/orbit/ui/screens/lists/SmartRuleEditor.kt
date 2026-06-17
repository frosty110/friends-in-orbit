package app.orbit.ui.screens.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import app.orbit.domain.smart.SmartListRule
import app.orbit.ui.theme.OrbitTheme

/**
 * Smart-list rule parameter editor (SMART-06).
 *
 * Dispatches over the [SmartListRule] sealed family with one branch per
 * subtype:
 *  - [SmartListRule.RecentlyAddedNotCalled] → days slider, 7..180 (default 30)
 *  - [SmartListRule.LongGap] → numeric input, ≥ 1 (no default)
 *  - [SmartListRule.CommonlyCalled] → percent slider, 10..50 (default 20)
 *  - [SmartListRule.RarelyCalled] → percent slider, 10..50 (default 50)
 *  - [SmartListRule.NeverCalled] → no-params placeholder
 *
 * Save-on-change semantics — sliders use `onValueChangeFinished` (not
 * `onValueChange`). Numeric input commits on
 * focus loss (the consumer can drive that via the value parameter).
 *
 * Token-clean — no inline color hex literals, no RoundedCornerShape, no fontSize literals.
 *
 * Consumed by the `ListConfigScreen` rewrite.
 */
@Composable
fun SmartRuleEditor(
    rule: SmartListRule,
    onChange: (SmartListRule) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp)) {
        when (rule) {
            is SmartListRule.RecentlyAddedNotCalled -> DaysSlider(
                label = "Added within the last",
                value = rule.daysWindow,
                range = 7..180,
                onCommit = { onChange(rule.copy(daysWindow = it)) },
            )
            is SmartListRule.LongGap -> DaysNumberInput(
                label = "No call in the last",
                value = rule.daysThreshold,
                onCommit = { onChange(rule.copy(daysThreshold = it)) },
            )
            is SmartListRule.CommonlyCalled -> PercentSlider(
                label = "Top",
                readoutSuffix = " of contacts with call history",
                value = rule.topPercent,
                onCommit = { onChange(rule.copy(topPercent = it)) },
            )
            is SmartListRule.RarelyCalled -> PercentSlider(
                label = "Bottom",
                readoutSuffix = " of contacts with call history",
                value = rule.bottomPercent,
                onCommit = { onChange(rule.copy(bottomPercent = it)) },
            )
            SmartListRule.NeverCalled -> NoParamsPlaceholder()
        }
    }
}

@Composable
private fun DaysSlider(
    label: String,
    value: Int,
    range: IntRange,
    onCommit: (Int) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = label,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${current.toInt()} days",
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.accentPress),
            )
        }
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onCommit(current.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = OrbitTheme.colors.accent,
                activeTrackColor = OrbitTheme.colors.accent,
                inactiveTrackColor = OrbitTheme.colors.line,
            ),
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${range.first}d",
                style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
            )
            Text(
                text = "${range.last}d",
                style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
            )
        }
    }
}

@Composable
private fun PercentSlider(
    label: String,
    readoutSuffix: String,
    value: Int,
    onCommit: (Int) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = label,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${current.toInt()}%",
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.accentPress),
            )
        }
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onCommit(current.toInt()) },
            valueRange = 10f..50f,
            colors = SliderDefaults.colors(
                thumbColor = OrbitTheme.colors.accent,
                activeTrackColor = OrbitTheme.colors.accent,
                inactiveTrackColor = OrbitTheme.colors.line,
            ),
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "10%",
                style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
            )
            Text(
                text = "50%",
                style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${current.toInt()}%$readoutSuffix",
            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
        )
    }
}

@Composable
private fun DaysNumberInput(
    label: String,
    value: Int,
    onCommit: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = text,
                onValueChange = { input ->
                    val digits = input.filter { it.isDigit() }.take(3)
                    text = digits
                    val parsed = digits.toIntOrNull()
                    if (parsed != null && parsed >= 1) {
                        onCommit(parsed)
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = OrbitTheme.colors.bgSubtle,
                    unfocusedContainerColor = OrbitTheme.colors.bgSubtle,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = OrbitTheme.colors.fg,
                    unfocusedTextColor = OrbitTheme.colors.fg,
                ),
                modifier = Modifier.width(96.dp),
            )
            Text(
                text = "days",
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
            )
        }
    }
}

@Composable
private fun NoParamsPlaceholder() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            text = "No parameters to tune. This list shows everyone you haven't called yet.",
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
            modifier = Modifier.background(OrbitTheme.colors.surface).padding(8.dp),
        ) {
            SmartRuleEditor(
                rule = SmartListRule.RecentlyAddedNotCalled(daysWindow = 30),
                onChange = {},
            )
        }
    }
}

@Preview(name = "SmartRuleEditor — NeverCalled, dark", showBackground = true)
@Composable
private fun SmartRuleEditorNeverCalledDarkPreview() {
    OrbitTheme(darkTheme = true) {
        Box(
            modifier = Modifier.background(OrbitTheme.colors.surface).padding(8.dp),
        ) {
            SmartRuleEditor(
                rule = SmartListRule.NeverCalled,
                onChange = {},
            )
        }
    }
}

// endregion
