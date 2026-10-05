package com.rm.parrotmetric.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.utf16CodePoint

/**
 * Something a key does, for the key handlers, the key list and tool search.
 * [keys] are written the way the list shows them: "E", "Shift+E", "Ctrl+Z",
 * "Esc"; the first is the one shown, the rest work too. None is fine: tool
 * search still finds it.
 */
class Shortcut(val label: String, val keys: List<String>, val group: String, val enabled: Boolean = true, val run: () -> Unit)

/** Runs the first enabled shortcut for the key [name]. False if there's none. */
fun List<Shortcut>.press(name: String): Boolean {
    val s = firstOrNull { name in it.keys && it.enabled } ?: return false
    s.run()
    return true
}

private val letters = listOf(
    Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
    Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
).zip('A'..'Z').toMap()

private val named = mapOf(
    Key.Escape to "Esc", Key.Enter to "Enter", Key.NumPadEnter to "Enter", Key.Backspace to "Backspace",
    Key.Delete to "Delete", Key.Tab to "Tab", Key.F1 to "F1", Key.F6 to "F6",
)

/**
 * A key press as [Shortcut.keys] writes it, or null for keys with no use.
 *
 * Alt works as Ctrl, since some phone keyboards have no Ctrl. On those, Alt
 * with a letter also types digits and symbols; a key that types a digit or
 * symbol is that character, with no Ctrl.
 */
fun keyName(e: KeyEvent): String? {
    val typed = e.utf16CodePoint.takeIf { it in 33..126 }?.toChar()
    if (typed != null && !typed.isLetter() && e.key !in named) {
        // Digits and symbols are what they type, whatever's held.
        return if ((e.isCtrlPressed || e.isMetaPressed) && typed.isDigit()) "Ctrl+$typed" else typed.toString()
    }
    val ctrl = e.isCtrlPressed || e.isMetaPressed || e.isAltPressed
    val base = letters[e.key]?.toString() ?: named[e.key] ?: return null
    return (if (ctrl) "Ctrl+" else "") + (if (e.isShiftPressed) "Shift+" else "") + base
}

/** The character a key types, for numbers typed into fields and sizes. */
fun typedChar(e: KeyEvent): Char? = e.utf16CodePoint.takeIf { it in 32..126 }?.toChar()

/** Characters that start a number or carry one on. */
fun startsNumber(c: Char) = c.isDigit() || c == '.'

/**
 * The number fields on screen, so keys can reach them: a digit typed with no
 * field focused starts the first one, and Tab moves between them, top to
 * bottom.
 */
class FieldChain {
    class Field(val focus: FocusRequester, val start: (Char) -> Unit) {
        var focused = false
        var y = 0f
        var x = 0f
    }

    private val fields = mutableListOf<Field>()

    fun add(f: Field) { fields += f }
    fun remove(f: Field) { fields -= f }

    private fun inOrder() = fields.sortedWith(compareBy({ it.y }, { it.x }))

    val any get() = fields.isNotEmpty()
    val focused get() = fields.any { it.focused }

    /** Starts the first field with [c] typed into it. False if there's none. */
    fun startFirst(c: Char): Boolean {
        val f = inOrder().firstOrNull() ?: return false
        f.start(c)
        return true
    }

    /** Focuses the next field, or the one before with [back], round from the end. */
    fun next(back: Boolean): Boolean {
        val all = inOrder()
        if (all.isEmpty()) return false
        val i = all.indexOfFirst { it.focused }
        val to = when {
            i < 0 -> if (back) all.size - 1 else 0
            back -> (i - 1 + all.size) % all.size
            else -> (i + 1) % all.size
        }
        all[to].focus.requestFocus()
        return true
    }
}

val LocalFieldChain = compositionLocalOf<FieldChain?> { null }
