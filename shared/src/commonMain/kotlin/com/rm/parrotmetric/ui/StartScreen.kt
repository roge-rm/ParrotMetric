package com.rm.parrotmetric.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.parrotmetric.ui.design.Segmented

/** Which screen is up: the start menu, the model, or settings. */
enum class AppScreen { Start, Model, Settings, Help, Drawing }

/** How much detail curved surfaces get on screen: by the device's speed, or set. */
enum class DisplayDetail(val label: String) {
    Automatic("Automatic"),
    Low("Low"),
    Medium("Medium"),
    High("High"),
}

/** A full screen page on the plain background, its content in a narrow column that scrolls. */
@Composable
private fun Page(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.ground).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}

@Composable
private fun BigButton(text: String, icon: ImageVector? = null, primary: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (primary) Palette.mint else Palette.surface,
        contentColor = if (primary) Palette.ink else Palette.text,
    ) {
        Row(Modifier.height(54.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(20.dp), tint = if (primary) Palette.ink else Palette.mint)
                Box(Modifier.size(14.dp))
            }
            Text(text, fontSize = 16.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
        }
    }
}

/** The first screen: carry on with the last design, start a new one, open one, settings, or quit. */
@Composable
fun StartScreen(icon: Painter?, state: ModelState, actions: ModelActions) {
    Page {
        Row(Modifier.padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Image(icon, null, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)))
                Box(Modifier.size(14.dp))
            }
            Text("ParrotMetric", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        }
        val last = state.lastDesign
        if (last != null) {
            BigButton("Continue ${last.first}", Icons.open, primary = true, onClick = actions::continueLast)
            Text(
                if (last.second == 1) "1 step" else "${last.second} steps",
                Modifier.padding(start = 6.dp, bottom = 6.dp), fontSize = 13.sp, color = Palette.muted,
            )
        }
        BigButton("New design", Icons.newFile, primary = last == null) { actions.newDesign(); actions.showScreen(AppScreen.Model) }
        BigButton("Open…", Icons.open, onClick = actions::openFile)
        BigButton("Settings", Icons.parameters) { actions.showScreen(AppScreen.Settings) }
        BigButton("Help", Icons.help) { actions.showScreen(AppScreen.Help) }
        if (state.canQuit) BigButton("Quit", Icons.close, onClick = actions::quit)
        val folder = state.folderName
        if (folder != null) {
            Text("In $folder", Modifier.padding(start = 6.dp, top = 14.dp, bottom = 2.dp), fontSize = 14.sp, color = Palette.muted)
            if (state.projects.isEmpty()) Text("No designs yet", Modifier.padding(start = 6.dp), fontSize = 14.sp, color = Palette.faint)
            for (p in state.projects.take(30)) {
                Surface(
                    onClick = { actions.openProject(p.name) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = Palette.surface,
                    contentColor = Palette.text,
                ) {
                    Row(Modifier.height(46.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name.removeSuffix(".pmet"), Modifier.weight(1f), fontSize = 15.sp, maxLines = 1)
                        Text(ago(p.modified), fontSize = 13.sp, color = Palette.muted)
                    }
                }
            }
        }
    }
}

/** How long ago a time in ms since 1970 was, roughly: "just now", "5 min ago", "3 days ago". */
private fun ago(ms: Long): String {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val min = (now - ms) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 60 * 24 -> "${min / 60} h ago"
        min < 60 * 24 * 60 -> "${min / (60 * 24)} days ago"
        else -> "${min / (60 * 24 * 30)} months ago"
    }
}

/** Settings: the screen layout and how much detail the model shows. */
@Composable
fun SettingsScreen(state: ModelState, actions: ModelActions, onBack: () -> Unit) {
    Page {
        Text("Settings", Modifier.padding(bottom = 8.dp), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
        Text("Layout", fontSize = 14.sp, color = Palette.muted)
        Segmented(listOf("Automatic", "Phone", "Large screen"), state.layout.ordinal) { actions.setLayout(LayoutMode.entries[it]) }
        Box(Modifier.size(6.dp))
        Text("Display detail", fontSize = 14.sp, color = Palette.muted)
        Segmented(DisplayDetail.entries.map { it.label }, state.detail.ordinal) { actions.setDetail(DisplayDetail.entries[it]) }
        if (state.detail == DisplayDetail.Automatic) {
            Text(
                state.autoDetail?.let { "${it.label} on this device" } ?: "Measuring this device…",
                Modifier.padding(start = 6.dp), fontSize = 13.sp, color = Palette.muted,
            )
        }
        if (state.canChooseFolder || state.canUseServer) {
            var editing by remember { mutableStateOf(false) }
            Box(Modifier.size(6.dp))
            Text("Projects folder", fontSize = 14.sp, color = Palette.muted)
            Text(state.folderName ?: "None", Modifier.padding(start = 6.dp, top = 4.dp), fontSize = 15.sp, color = Palette.text, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.canChooseFolder) TextButton(onClick = actions::chooseFolder) { Text("Choose folder…", color = Palette.mint) }
                if (state.canUseServer) TextButton(onClick = { editing = !editing }) { Text("WebDAV server…", color = Palette.mint) }
                if (state.folderName != null) TextButton(onClick = actions::forgetFolder) { Text("Stop using", color = Palette.muted) }
            }
            if (editing) ServerForm(state, actions) { editing = false }
        }
        Box(Modifier.size(12.dp))
        BigButton("Back", primary = true, onClick = onBack)
    }
}

/** The address and login for a WebDAV server; closes once it's in use. */
@Composable
private fun ServerForm(state: ModelState, actions: ModelActions, close: () -> Unit) {
    var url by remember { mutableStateOf(state.server?.url ?: "https://") }
    var user by remember { mutableStateOf(state.server?.user ?: "") }
    var password by remember { mutableStateOf(state.server?.password ?: "") }
    var tried by remember { mutableStateOf(false) }
    LaunchedEffect(state.connecting) {
        if (tried && !state.connecting && state.serverProblem == null) close()
    }
    Column(Modifier.padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Folder address", fontSize = 13.sp, color = Palette.muted)
        val focus = LocalFocusManager.current
        val next = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
        val connect = { tried = true; actions.useServer(url, user, password) }
        FormField(url, { url = it }, KeyboardType.Uri, ImeAction.Next, next)
        Text("User name", fontSize = 13.sp, color = Palette.muted)
        FormField(user, { user = it }, KeyboardType.Text, ImeAction.Next, next)
        Text("Password", fontSize = 13.sp, color = Palette.muted)
        FormField(password, { password = it }, KeyboardType.Password, ImeAction.Done, KeyboardActions(onDone = { connect() }), PasswordVisualTransformation())
        state.serverProblem?.let { Text(it, fontSize = 13.sp, color = Palette.orange) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = connect, enabled = !state.connecting) {
                Text(if (state.connecting) "Connecting…" else "Connect", color = Palette.mint)
            }
            TextButton(onClick = close) { Text("Cancel", color = Palette.muted) }
        }
    }
}

@Composable
private fun FormField(
    text: String, change: (String) -> Unit, type: KeyboardType, ime: ImeAction, keys: KeyboardActions,
    look: VisualTransformation = VisualTransformation.None,
) = BasicTextField(
    text, change,
    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.surface).padding(12.dp),
    textStyle = TextStyle(color = Palette.text, fontSize = 16.sp),
    cursorBrush = SolidColor(Palette.mint),
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false, imeAction = ime),
    keyboardActions = keys,
    visualTransformation = look,
)
