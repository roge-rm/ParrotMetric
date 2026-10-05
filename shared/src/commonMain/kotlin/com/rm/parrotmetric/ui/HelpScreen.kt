package com.rm.parrotmetric.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The manual: its contents, and a section at a time. The words come from
 * manual/ through tools/gen_manual.py, so none are written here. Back goes
 * from a section to the contents, then out.
 */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    val reading = Help.reading
    val scroll = rememberScrollState()
    LaunchedEffect(reading) { scroll.scrollTo(0) }
    val back = { if (!Help.back()) onBack() }
    Box(Modifier.fillMaxSize().background(Palette.ground).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.back, "Back", tint = Palette.text) }
                Text(reading?.title ?: "Help", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(scroll).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val section = reading
                if (section == null) {
                    for (s in Manual.sections) Surface(
                        onClick = { Help.reading = s },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = Palette.surface,
                        contentColor = Palette.text,
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(s.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            if (s.summary.isNotEmpty()) Text(s.summary, fontSize = 13.sp, color = Palette.muted)
                        }
                    }
                } else {
                    for (b in section.blocks) ManualLine(b)
                }
            }
        }
    }
}

/** The section being read, kept here so the Back key can return to the contents. */
object Help {
    var reading by mutableStateOf<ManualSection?>(null)

    /** Back from a section to the contents. False if already there. */
    fun back(): Boolean {
        if (reading == null) return false
        reading = null
        return true
    }
}

@Composable
private fun ManualLine(block: ManualBlock) {
    val text = inline(block.text)
    when (block.kind) {
        ManualKind.Heading -> Text(block.text, Modifier.padding(top = 10.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.mint)
        ManualKind.Para -> Text(text, fontSize = 15.sp, lineHeight = 22.sp, color = Palette.text)
        ManualKind.Bullet, ManualKind.Step -> Row(Modifier.fillMaxWidth()) {
            Text(if (block.kind == ManualKind.Bullet) "•" else "–", Modifier.width(18.dp), fontSize = 15.sp, color = Palette.muted)
            Text(text, fontSize = 15.sp, lineHeight = 22.sp, color = Palette.text)
        }
    }
}

/** Draws `**bold**` and `` `code` `` in place of their marks. */
private fun inline(source: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < source.length) {
        val bold = source.indexOf("**", i)
        val code = source.indexOf('`', i)
        val next = listOf(bold, code).filter { it >= 0 }.minOrNull()
        if (next == null) { append(source.substring(i)); break }
        append(source.substring(i, next))
        val mark = if (next == bold) "**" else "`"
        val end = source.indexOf(mark, next + mark.length)
        if (end < 0) { append(source.substring(next)); break }
        pushStyle(if (mark == "**") SpanStyle(fontWeight = FontWeight.SemiBold, color = Palette.mint) else SpanStyle(fontFamily = FontFamily.Monospace))
        append(source.substring(next + mark.length, end))
        pop()
        i = end + mark.length
    }
}
