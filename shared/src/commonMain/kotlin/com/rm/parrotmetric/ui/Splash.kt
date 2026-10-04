package com.rm.parrotmetric.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.parrotmetric.app.APP_VERSION
import kotlinx.coroutines.delay

/** The full icon with the version under it, over everything for a moment at launch. Not shown if [show] is false. */
@Composable
fun LaunchSplash(icon: Painter?, show: Boolean = true) {
    var shown by remember { mutableStateOf(show) }
    LaunchedEffect(Unit) {
        delay(1400)
        shown = false
    }
    AnimatedVisibility(shown, enter = EnterTransition.None, exit = fadeOut(tween(400))) {
        Box(
            Modifier.fillMaxSize().background(Palette.ground).pointerInput(Unit) {
                // Taps stop here while it shows.
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(150.dp)) }
                Text("Version $APP_VERSION", fontSize = 14.sp, color = Palette.muted)
            }
        }
    }
}
