package com.rm.parrotmetric.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Scrolls a row sideways, by touch and by any mouse wheel: the plain wheel
 * moves it too, as a mouse has no other way to reach what's past the edge.
 */
fun Modifier.sideways(state: ScrollState): Modifier = composed {
    val scope = rememberCoroutineScope()
    pointerInput(state) {
        val notch = 48.dp.toPx()
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.first()
                val d = change.scrollDelta
                if (d.y != 0f && d.x == 0f && !change.isConsumed) {
                    change.consume()
                    scope.launch { state.scrollBy(d.y.coerceIn(-3f, 3f) * notch) }
                }
            }
        }
    }.horizontalScroll(state)
}
