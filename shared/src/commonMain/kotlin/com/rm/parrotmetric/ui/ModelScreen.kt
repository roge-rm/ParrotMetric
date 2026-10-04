package com.rm.parrotmetric.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** What the model screen can ask the platform to do. */
interface ModelActions {
    fun newBox()
    fun importFile()
    fun cutHole()
    fun exportStl()
    fun exportStep()
}

@Composable
private fun Tool(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, maxLines = 1)
    }
}

/**
 * The viewport with the model filling the screen and the tools along the
 * bottom. The status line shows the triangle count or the last error.
 */
@Composable
fun ModelScreen(viewport: @Composable () -> Unit, actions: ModelActions, status: String) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize()) {
            viewport()
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(status, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tool("Box", actions::newBox)
                    Tool("Open", actions::importFile)
                    Tool("Cut", actions::cutHole)
                    Tool("STL", actions::exportStl)
                    Tool("STEP", actions::exportStep)
                }
            }
        }
    }
}
