package com.rm.parrotmetric.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/** The app's colours, taken from its icon. */
object Palette {
    val ground = Color(0xFF1A201F)
    val surface = Color(0xFF232A29)
    val raised = Color(0xFF2C3433)
    val line = Color(0xFF384341)
    val text = Color(0xFFF2E4CF)
    val muted = Color(0xFFAEB4AE)
    val faint = Color(0xFF6E7A77)

    val teal = Color(0xFF2E7D7A)
    val mint = Color(0xFFA8F2EC)
    val yellow = Color(0xFFFFD23F)
    val orange = Color(0xFFFF7A3D)
    val ink = Color(0xFF191527)

    // Each tool group has its own colour, used on its icons.
    val sketch = Color(0xFF8CC8F0)
    val create = mint
    val modify = orange
    val construct = yellow
    val inspect = Color(0xFFC9A8EE)

    val scheme = darkColorScheme(
        primary = mint,
        onPrimary = ink,
        secondary = orange,
        onSecondary = ink,
        background = ground,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = raised,
        onSurfaceVariant = muted,
        outline = line,
    )
}
