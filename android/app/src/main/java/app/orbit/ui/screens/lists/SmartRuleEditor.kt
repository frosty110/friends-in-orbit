package app.orbit.ui.screens.lists

import android.content.res.Configuration
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.R
import app.orbit.domain.smart.SmartListRule
import app.orbit.ui.components.OrbitSlider
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
 * Each rule reads as one sentence over its control, with the current setting
 * as the sentence's argument ("Added in the last 30 days", "The top 20% of
 * the people you call"). Until 2026-10-06 the words were split around the
 * control (a label on the left, "30 days" on the right, "days" after a
 * number field), which fixed the word order for every language (voice.md:
 * keep a sentence whole). The slider's TalkBack value stays its own words.
 *
 * Save-on-change semantics: sliders commit on `onValueChangeFinished` (not
 * `onValueChange`). The number field commits on focus loss or IME Done, from
 * a local typing buffer it alone owns (rules.md Code 7); it used to commit on
 * every keystroke while also re-seeding its buffer from the saved value,
 * which made two writers of the same text.
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
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x4)) {
        when (rule) {
            is SmartListRule.RecentlyAddedNotCalled -> DaysSlider(
                sentenceRes = R.plurals.lists_smart_added_within,
                value = rule.daysWindow,
                range = 7..180,
                onCommit = { onChange(rule.copy(daysWindow = it)) },
            )
            is SmartListRule.LongGap -> DaysNumberInput(
                sentenceRes = R.plurals.lists_smart_no_call_in,
                value = rule.daysThreshold,
                onCommit = { onChange(rule.copy(daysThreshold = it)) },
            )
            is SmartListRule.CommonlyCalled -> PercentSlider(
                sentenceRes = R.string.lists_smart_top,
                value = rule.topPercent,
                onCommit = { onChange(rule.copy(topPercent = it)) },
            )
            is SmartListRule.RarelyCalled -> PercentSlider(
                sentenceRes = R.string.lists_smart_bottom,
                value = rule.bottomPercent,
                onCommit = { onChange(rule.copy(bottomPercent = it)) },
            )
            SmartListRule.NeverCalled -> NoParamsPlaceholder()
        }
    }
}

@Composable
private fun DaysSlider(
    @PluralsRes sentenceRes: Int,
    value: Int,
    range: IntRange,
    onCommit: (Int) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val days = current.toInt()
    // The sentence follows the thumb, so the words always say the setting
    // the slider shows.
    val sentence = pluralStringResource(sentenceRes, days, days)
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = sentence,
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            modifier = Modifier.fillMaxWidth(),
        )
        OrbitSlider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onCommit(current.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            label = sentence,
            valueDescription = pluralStringResource(R.plurals.lists_smart_days, days, days),
            modifier = Modifier.padding(top = OrbitTheme.spacing.x1),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = OrbitTheme.spacing.x1),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = pluralStringResource(R.plurals.lists_smart_days, range.first, range.first),
                style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
            )
            Text(
                text = pluralStringResource(R.plurals.lists_smart_days, range.last, range.last),
                style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
            )
        }
    }
}

@Composable
private fun PercentSlider(
    @StringRes sentenceRes: Int,
    value: Int,
    onCommit: (Int) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val percent = current.toInt()
    val sentence = stringResource(sentenceRes, percent)
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = sentence,
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            modifier = Modifier.fillMaxWidth(),
        )
        OrbitSlider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onCommit(current.toInt()) },
            valueRange = 10f..50f,
            label = sentence,
            valueDescription = stringResource(R.string.lists_smart_percent_a11y, percent),
            modifier = Modifier.padding(top = OrbitTheme.spacing.x1),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = OrbitTheme.spacing.x1),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.lists_smart_percent, 10),
                style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
            )
            Text(
                text = stringResource(R.string.lists_smart_percent, 50),
                style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
            )
        }
    }
}

/**
 * A whole number of days, typed. The buffer is the field's alone: seeded once
 * from the saved [value], never re-seeded by it, so the saved value flows one
 * way and nothing overwrites what the user is typing (rules.md Code 7). It
 * commits once per gesture, whichever path ends it: IME Done, or focus
 * leaving the field without Done. A commit happens when the text parses to
 * 1 or more and differs from [value]; otherwise the field reverts to the
 * saved number, so a cleared field never writes and never stays blank.
 *
 * The `parsed != value` guard does not make a second commit harmless: the
 * saved value changes only after the write round-trips through Room, so a
 * repeat within the same gesture sees the same [value] and writes again
 * (two identical writes, and two failure snackbars when the write fails).
 * That is why Done disarms the focus-loss branch before it clears focus.
 *
 * The field's TalkBack name is the sentence over it: the sentence is a
 * sibling node, so without this the field announced only its digits
 * (rules.md Design 7; the passphrase sheets' precedent).
 */
@Composable
private fun DaysNumberInput(
    @PluralsRes sentenceRes: Int,
    value: Int,
    onCommit: (Int) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(value.toString()) }
    // Guards the focus-loss commit: onFocusChanged reports unfocused once as
    // the field enters composition, which must not count as a blur, and Done
    // sets it false before clearing focus so that blur does not commit twice.
    var hasFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // The sentence shows what is typed while it is a valid number, else the
    // saved setting, so it never reads "No call in the last  days".
    val shown = text.toIntOrNull()?.takeIf { it >= 1 } ?: value
    val sentence = pluralStringResource(sentenceRes, shown, shown)

    fun commit() {
        val parsed = text.toIntOrNull()
        if (parsed != null && parsed >= 1) {
            if (parsed != value) onCommit(parsed)
        } else {
            text = value.toString()
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = sentence,
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        TextField(
            value = text,
            onValueChange = { input -> text = input.filter { it.isDigit() }.take(3) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                // Done owns this gesture's commit. clearFocus() fires the
                // blur branch synchronously, so it is disarmed first; the
                // commit itself does not wait on focus actually clearing.
                hasFocused = false
                commit()
                focusManager.clearFocus()
            }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = OrbitTheme.colors.bgSubtle,
                unfocusedContainerColor = OrbitTheme.colors.bgSubtle,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = OrbitTheme.colors.fg,
                unfocusedTextColor = OrbitTheme.colors.fg,
            ),
            modifier = Modifier
                .width(96.dp)
                .semantics { contentDescription = sentence }
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        hasFocused = true
                    } else if (hasFocused) {
                        hasFocused = false
                        commit()
                    }
                },
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
