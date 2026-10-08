package com.rm.parrotmetric.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.flow.first

/** Number fields show the pad in place of the system keyboard: a touch screen with no keyboard. */
val LocalNumberPad = compositionLocalOf { false }

/** What a pad key does to a field's text: types at the selection, replacing it, or with ⌫ takes away. */
fun padKey(v: TextFieldValue, key: Char): TextFieldValue {
    val s = v.selection.min
    val e = v.selection.max
    if (key == '⌫') {
        if (s != e) return TextFieldValue(v.text.removeRange(s, e), TextRange(s))
        if (s == 0) return v
        return TextFieldValue(v.text.removeRange(s - 1, s), TextRange(s - 1))
    }
    val typed = when (key) {
        '×' -> "*"
        '÷' -> "/"
        '−' -> "-"
        else -> key.toString()
    }
    return TextFieldValue(v.text.replaceRange(s, e, typed), TextRange(s + typed.length))
}

/**
 * A one-line field for a number, sum or expression. With [LocalNumberPad], focusing it shows a
 * number pad beside it instead of the system keyboard; its abc key brings the keyboard back for
 * names and units. ✓ moves to the next field when [next], else does [onDone]. [modifier] places
 * it; [fieldModifier] goes on the text field itself, for focus.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NumberField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier,
    fieldModifier: Modifier,
    textStyle: TextStyle,
    next: Boolean = false,
    onDone: () -> Unit,
) {
    val pad = LocalNumberPad.current
    var focused by remember { mutableStateOf(false) }
    // The system keyboard asked for with abc, until the field loses focus.
    var letters by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val current = androidx.compose.runtime.rememberUpdatedState(value)
    Box(modifier) {
        // The system keyboard waits until abc is pressed.
        InterceptPlatformTextInput({ request, nextHandler ->
            if (pad) snapshotFlow { letters }.first { it }
            nextHandler.startInputMethod(request)
        }) {
            BasicTextField(
                value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().then(fieldModifier).onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) letters = false
                },
                textStyle = textStyle,
                cursorBrush = SolidColor(Palette.mint),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = if (next) ImeAction.Next else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
            )
        }
        if (pad && focused && !letters) {
            Popup(popupPositionProvider = BesideField, properties = PopupProperties(focusable = false)) {
                Pad(
                    onKey = { onValueChange(padKey(current.value, it)) },
                    onLetters = { letters = true },
                    onDone = {
                        if (next) focusManager.moveFocus(FocusDirection.Next)
                        else {
                            onDone()
                            focusManager.clearFocus()
                        }
                    },
                )
            }
        }
    }
}

/** Below the field, or above it where there isn't room, kept on the screen. */
private object BesideField : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val gap = 12
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) below else maxOf(0, anchorBounds.top - gap - popupContentSize.height)
        return IntOffset(x, y)
    }
}

private val rows = listOf("789÷⌫", "456×(", "123−)", "0.a+✓")

@Composable
private fun Pad(onKey: (Char) -> Unit, onLetters: () -> Unit, onDone: () -> Unit) {
    Surface(color = Palette.surface, shape = RoundedCornerShape(20.dp), shadowElevation = 8.dp) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in rows) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in row) {
                    val digit = k.isDigit() || k == '.'
                    val colour = when {
                        k == '✓' -> Palette.mint
                        digit -> Palette.raised
                        else -> Palette.ground
                    }
                    Surface(
                        onClick = {
                            when (k) {
                                '✓' -> onDone()
                                'a' -> onLetters()
                                else -> onKey(k)
                            }
                        },
                        modifier = Modifier.size(52.dp, 46.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = colour,
                        contentColor = if (k == '✓') Palette.ink else Palette.text,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                if (k == 'a') "abc" else k.toString(),
                                fontSize = if (k == 'a') 14.sp else 20.sp,
                                fontWeight = if (k == '✓') FontWeight.Bold else FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}
