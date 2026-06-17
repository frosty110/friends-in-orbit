package app.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme

/**
 * Reusable search field for Browse + Global Search. Visual only —
 * the consumer wires the debounced `snapshotFlow { query }.debounce(250)` and
 * the VM `onSearchChanged` callback.
 *
 * BROWSE-02: pill (`shapes.full`) bg `colors.bgSubtle`, leading
 * magnifying-glass, optional clear-x affordance when query non-empty. Field
 * height = `spacing.tapMin` (48dp), matching tap-target floor.
 *
 * The keyboard's action key is [ImeAction.Search]; pressing it
 * dismisses the keyboard (search is live-filtering, there is nothing to
 * submit). Optional [focusRequester] lets a consumer auto-focus the field on
 * entry (GlobalSearch opens straight into typing).
 */
@Composable
fun OrbitSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(OrbitTheme.spacing.tapMin)
            .clip(OrbitTheme.shapes.full)
            .background(OrbitTheme.colors.bgSubtle)
            .padding(horizontal = OrbitTheme.spacing.x4),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PhIcon(
                name = "magnifying-glass",
                size = 18.dp,
                tint = OrbitTheme.colors.fgMuted,
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = OrbitTheme.type.body,
                        color = OrbitTheme.colors.fgSubtle,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    textStyle = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    cursorBrush = SolidColor(OrbitTheme.colors.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (focusRequester != null) {
                                Modifier.focusRequester(focusRequester)
                            } else {
                                Modifier
                            },
                        ),
                )
            }
            if (query.isNotEmpty()) {
                OrbitIconButton(
                    icon = "x",
                    onClick = { onQueryChange("") },
                    contentDescription = "Clear search",
                )
            }
        }
    }
}
