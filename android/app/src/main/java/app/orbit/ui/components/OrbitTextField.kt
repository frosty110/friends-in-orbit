package app.orbit.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The one text field (2026-10-07). Before it, fields were Material's filled
 * `TextField` restyled to a `bgSubtle` fill, Material's `OutlinedTextField`,
 * or a hand-drawn `BasicTextField`, each with its own keyboard handling, and
 * the filled ones had no outline at all, so nothing but a faint fill said
 * "type here" (rules.md §Design 4 wants the field's outline at 3:1).
 *
 * - **Label above the field**, inside the same tap target and the same
 *   TalkBack node, so tapping the words focuses the field and TalkBack says
 *   "Name your list, edit box" (design/README.md, "labels sit above their
 *   field"). Pass [label] null when a sentence or heading right above already
 *   names it, and give [contentDescription] instead.
 * - **Outlined in `fgSubtle`**, the colour `ThemeContrastTest` holds at 3:1 on
 *   the surface, over a quiet `bgSubtle` fill; focus draws a 2dp ink ring,
 *   an error a 2dp `danger` ring with the message below. No accent: the cursor
 *   is ink too (rules.md §Design 5).
 * - **Stays above the keyboard** ([keepAboveKeyboard]): label, field and the
 *   line under it, with room to spare, every time, not only when Compose's
 *   own heuristic happens to fire.
 * - **Keyboard defaults that fit text**: sentence capitalisation, and Done on
 *   a single line; pass [keyboardOptions] for anything else (a password).
 * - 48dp tall at least, growing with its text at any font scale.
 */
@Composable
fun OrbitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String?,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    errorText: String? = null,
    contentDescription: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = OrbitTextFieldDefaults.keyboardOptions(singleLine),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = interaction,
        textStyle = orbitFieldTextStyle(enabled),
        cursorBrush = SolidColor(OrbitTheme.colors.fg),
        modifier = modifier.orbitField(contentDescription, errorText),
        decorationBox = { innerTextField ->
            OrbitTextFieldDecoration(
                innerTextField = innerTextField,
                isEmpty = value.isEmpty(),
                interaction = interaction,
                label = label,
                placeholder = placeholder,
                supportingText = supportingText,
                errorText = errorText,
                singleLine = singleLine,
                trailing = trailing,
            )
        },
    )
}

/**
 * The same field over a [TextFieldValue], for the one caller that places the
 * cursor itself: List settings' rename from the title (LIST-26) opens with
 * the cursor after the old name, where a rename starts, rather than before
 * it. Everything else (look, keyboard, staying above it) is the String
 * field's.
 */
@Composable
fun OrbitTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String?,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    errorText: String? = null,
    contentDescription: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = OrbitTextFieldDefaults.keyboardOptions(singleLine),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = interaction,
        textStyle = orbitFieldTextStyle(enabled),
        cursorBrush = SolidColor(OrbitTheme.colors.fg),
        modifier = modifier.orbitField(contentDescription, errorText),
        decorationBox = { innerTextField ->
            OrbitTextFieldDecoration(
                innerTextField = innerTextField,
                isEmpty = value.text.isEmpty(),
                interaction = interaction,
                label = label,
                placeholder = placeholder,
                supportingText = supportingText,
                errorText = errorText,
                singleLine = singleLine,
                trailing = trailing,
            )
        },
    )
}

@Composable
private fun orbitFieldTextStyle(enabled: Boolean) =
    OrbitTheme.type.body.copy(color = if (enabled) OrbitTheme.colors.fg else OrbitTheme.colors.fgMuted)

/** Full width, kept above the keyboard, and named for TalkBack. */
private fun Modifier.orbitField(contentDescription: String?, errorText: String?): Modifier = composed {
    val margin = OrbitTheme.spacing.x4
    this
        .fillMaxWidth()
        .keepAboveKeyboard(margin)
        .semantics {
            if (contentDescription != null) this.contentDescription = contentDescription
            if (errorText != null) error(errorText)
        }
}

/** Label, outlined box, placeholder, trailing slot and the line under it. */
@Composable
private fun OrbitTextFieldDecoration(
    innerTextField: @Composable () -> Unit,
    isEmpty: Boolean,
    interaction: MutableInteractionSource,
    label: String?,
    placeholder: String?,
    supportingText: String?,
    errorText: String?,
    singleLine: Boolean,
    trailing: @Composable (() -> Unit)?,
) {
    val c = OrbitTheme.colors
    val focused by interaction.collectIsFocusedAsState()
    val isError = errorText != null
    val ring = when {
        isError -> c.danger
        focused -> c.fg
        else -> c.fgSubtle
    }
    val ringWidth = if (isError || focused) 2.dp else 1.dp
    Column(verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2)) {
        if (label != null) {
            Text(
                text = label,
                style = OrbitTheme.type.body,
                color = c.fgMuted,
            )
        }
        Row(
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = OrbitTheme.spacing.tapMin)
                .background(c.bgSubtle, OrbitTheme.shapes.md)
                .border(ringWidth, ring, OrbitTheme.shapes.md)
                .padding(start = OrbitTheme.spacing.x4, end = if (trailing != null) 0.dp else OrbitTheme.spacing.x4),
        ) {
            // One line sits centred in the 48dp box; several start at
            // the top, where the first line of a note is written.
            Box(
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = OrbitTheme.spacing.x3),
            ) {
                if (isEmpty && placeholder != null) {
                    Text(text = placeholder, style = OrbitTheme.type.body, color = c.fgSubtle)
                }
                innerTextField()
            }
            trailing?.invoke()
        }
        val below = errorText ?: supportingText
        if (below != null) {
            Text(
                text = below,
                style = OrbitTheme.type.meta,
                color = if (isError) c.danger else c.fgMuted,
            )
        }
    }
}

object OrbitTextFieldDefaults {
    /** Sentence case, and Done on one line (a newline on several). */
    fun keyboardOptions(singleLine: Boolean): KeyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.Sentences,
        imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
    )
}

/**
 * Keeps this element, and [margin] of room around it, in view above the
 * keyboard while anything inside it has focus.
 *
 * Compose already tries: a scroll container relocates a focused child when
 * the keyboard shrinks it. But it skips the shrink altogether while another
 * bring-into-view scroll is still animating, and tapping a field that needs
 * scrolling starts one at the very moment the keyboard begins to slide; it
 * relocates only a child that was wholly visible before; and an unfocused
 * field tapped may scroll only its cursor into view (foundation 1.8
 * `ContentInViewNode.onRemeasured`, and the open b/216790855 in
 * `CoreTextField`). Whether a field ended up under the keyboard therefore
 * depended on where it sat when tapped, which is what "sometimes it gets
 * covered" was. This asks again once the keyboard has stopped moving, for
 * the whole element plus room, so the answer no longer depends on timing.
 *
 * It needs a scrolling ancestor ([OrbitScreen]'s bodies, sheets and dialogs
 * that scroll); with none, there is nowhere to scroll and it does nothing.
 */
fun Modifier.keepAboveKeyboard(margin: Dp): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val marginPx = with(density) { margin.toPx() }

    LaunchedEffect(hasFocus) {
        if (!hasFocus) return@LaunchedEffect
        // The keyboard's inset changes every frame while it slides; collectLatest
        // restarts the wait each time, so the request goes out once it settles
        // (and at once when the keyboard was already up).
        snapshotFlow { ime.getBottom(density) }.collectLatest {
            delay(KeyboardSettleMs)
            requester.bringIntoView(
                Rect(0f, -marginPx, size.width.toFloat(), size.height + marginPx),
            )
        }
    }

    this
        .bringIntoViewRequester(requester)
        .onSizeChanged { size = it }
        .onFocusChanged { hasFocus = it.hasFocus }
}

/** Longer than a frame, shorter than a person notices. */
private const val KeyboardSettleMs = 64L

@Preview(name = "OrbitTextField, light", showBackground = true)
@Composable
private fun OrbitTextFieldPreviewLight() {
    OrbitTheme(darkTheme = false) {
        Column(
            verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x4),
            modifier = Modifier.background(OrbitTheme.colors.surface).padding(OrbitTheme.spacing.x4),
        ) {
            OrbitTextField(value = "", onValueChange = {}, label = "Name your list", placeholder = "Name this list")
            OrbitTextField(value = "Cousins", onValueChange = {}, label = "Name", supportingText = "You can change it later.")
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "OrbitTextField, dark", showBackground = true)
@Composable
private fun OrbitTextFieldPreviewDark() {
    OrbitTheme(darkTheme = true) {
        Column(
            verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x4),
            modifier = Modifier.background(OrbitTheme.colors.surface).padding(OrbitTheme.spacing.x4),
        ) {
            OrbitTextField(value = "", onValueChange = {}, label = "Password", errorText = "Use at least 8 characters.")
            OrbitTextField(
                value = "",
                onValueChange = {},
                label = null,
                contentDescription = "Add a note",
                placeholder = "Add a note",
                singleLine = false,
                minLines = 3,
            )
        }
    }
}
