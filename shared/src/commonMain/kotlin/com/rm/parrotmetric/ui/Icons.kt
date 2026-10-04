package com.rm.parrotmetric.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Line icons on a 24 unit grid, drawn for the app. Tinted where they're used. */
object Icons {
    private fun stroke(name: String, vararg paths: String, width: Float = 1.8f) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    val sketch = stroke("sketch", "M4 20h4L18 10l-4-4L4 16z", "M13 7l4 4")
    val create = stroke("create", "M12 3l8 4.5v9L12 21l-8-4.5v-9z", "M12 12l8-4.5M12 12v9M12 12L4 7.5")
    val modify = stroke("modify", "M5 20v-8a7 7 0 0 1 7-7h7")
    val construct = stroke("construct", "M2.5 16l6-8h13l-6 8z", "M12 2v2M12 6v2M12 18v2M12 21v1")
    val inspect = stroke("inspect", "M4.3 8h15.4a1.8 1.8 0 0 1 1.8 1.8v4.4a1.8 1.8 0 0 1-1.8 1.8H4.3a1.8 1.8 0 0 1-1.8-1.8V9.8A1.8 1.8 0 0 1 4.3 8z", "M7 8v3M11 8v4M15 8v3M19 8v2")

    val undo = stroke("undo", "M9 14L4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11")
    val redo = stroke("redo", "M15 14l5-5-5-5", "M20 9H9.5a5.5 5.5 0 0 0 0 11H13")
    val parts = stroke("parts", "M12 3l9 5-9 5-9-5z", "M3 13l9 5 9-5", "M3 17.5l9 5 9-5")
    val fit = stroke("fit", "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5")
    val close = stroke("close", "M6 6l12 12M18 6L6 18", width = 2.4f)
    val more = stroke("more", "M6 9l6 6 6-6", width = 2f)
    val cylinder = stroke("cylinder", "M6 6.5v11a6 2.5 0 0 0 12 0v-11", "M6 6.5a6 2.5 0 1 0 12 0a6 2.5 0 1 0-12 0")
    val sphere = stroke("sphere", circle(12f, 12f, 8.5f), "M3.5 12a8.5 3 0 0 0 17 0")
    val torus = stroke("torus", "M2 12a10 6 0 1 0 20 0a10 6 0 1 0-20 0", "M8 11.5a4 1.8 0 0 0 8 0", "M9 11a3 1.3 0 0 1 6 0")
    val cone = stroke("cone", "M12 3.5L5 17.5M12 3.5l7 14", "M5 17.5a7 2.5 0 1 0 14 0a7 2.5 0 1 0-14 0")
    /** The Shift key, for key badges. */
    val shiftKey = stroke("shiftKey", "M12 4l8 9h-4.5v7h-7v-7H4z", width = 2.4f)

    val box = stroke("box", "M12 3l8 4.5v9L12 21l-8-4.5v-9z", "M12 12l8-4.5M12 12v9M12 12L4 7.5")
    val extrude = stroke("extrude", "M5 15l7 4 7-4", "M12 19V9", "M8.5 12.5L12 9l3.5 3.5", "M5 15V7l7-4 7 4v8")
    val revolve = stroke("revolve", "M12 3v18", "M7 6a9 3 0 1 0 10 0", "M15 4.5l2 1.5-2 1.5")
    val open = stroke("open", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M12 10v6M9 13l3 3 3-3")
    val fillet = stroke("fillet", "M4 20v-8a8 8 0 0 1 8-8h8")
    val chamfer = stroke("chamfer", "M4 20v-9l7-7h9")
    val draft = stroke("draft", "M7 20L9 4h6l2 16", "M4 20h16")
    val shell = stroke("shell", "M3 7h18v13H3z", "M7 7v9h10V7")
    val hole = stroke("hole", "M6 6a6 2.5 0 1 0 12 0a6 2.5 0 1 0-12 0", "M6 6v12c0 1.4 2.7 2.5 6 2.5s6-1.1 6-2.5V6")
    val mirror = stroke("mirror", "M12 3v2M12 8v2M12 13v2M12 18v2", "M9 6L3 18h6zM15 6l6 12h-6z")
    val pattern = stroke("pattern", "M4.5 3h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 3 8.5v-4A1.5 1.5 0 0 1 4.5 3zM15.5 3h4A1.5 1.5 0 0 1 21 4.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 14 8.5v-4A1.5 1.5 0 0 1 15.5 3zM4.5 14h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 3 19.5v-4A1.5 1.5 0 0 1 4.5 14zM15.5 14h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4a1.5 1.5 0 0 1-1.5-1.5v-4a1.5 1.5 0 0 1 1.5-1.5z")
    val combine = stroke("combine", circle(9f, 12f, 6f), circle(15f, 12f, 6f))
    val move = stroke("move", "M12 3v18M3 12h18", "M8 7l4-4 4 4M8 17l4 4 4-4")
    val cut = stroke("cut", "M3 13h18", "M6 9l6-6 6 6", "M6 17h12v4H6z")
    val plane = stroke("plane", "M2.5 16l6-8h13l-6 8z")
    val axis = stroke("axis", "M4 20L20 4", "M8 20h-4v-4")
    val point = stroke("point", circle(12f, 12f, 3f), "M12 3v3M12 18v3M3 12h3M18 12h3")
    val measure = stroke("measure", "M4 20L20 4", "M4 16v4h4M16 4h4v4")
    val section = stroke("section", "M3 12h18", "M12 3a9 9 0 0 1 9 9H3a9 9 0 0 1 9-9z")
    // Sketch tools.
    val select = stroke("select", "M5 3l13 8-6 1.5L9 19z")
    val line = stroke("line", "M5 19L19 5", circle(5f, 19f, 1.6f), circle(19f, 5f, 1.6f))
    val rectangle = stroke("rectangle", "M4.5 6h15a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z")
    val circleTool = stroke("circle", circle(12f, 12f, 8f), circle(12f, 12f, 0.6f))
    val arc = stroke("arc", "M4 18a9 9 0 0 1 16 0")
    val pointTool = stroke("point", circle(12f, 12f, 2.5f), "M12 4v3M12 17v3M4 12h3M17 12h3")
    val spline = stroke("spline", "M3 17c3-8 6-8 9-2s6 6 9-2", circle(3f, 17f, 1.3f), circle(21f, 13f, 1.3f))
    val polygon = stroke("polygon", "M12 3l8 5.5-3 9.5H7L4 8.5z")
    val slot = stroke("slot", "M8 7h8a5 5 0 0 1 0 10H8A5 5 0 0 1 8 7z")
    val project = stroke("project", "M4 15l8 5 8-5", "M12 3v12", "M8.5 11.5L12 15l3.5-3.5")
    val dimension = stroke("dimension", "M4 12h16M4 8v8M20 8v8", "M7 10l-3 2 3 2M17 10l3 2-3 2")
    val constrain = stroke("constrain", "M5 19L19 5", "M5 19h9")
    val construction = stroke("construction", "M4 20l2-2M8 16l2-2M12 12l2-2M16 8l2-2M20 4l0 0")
    val delete = stroke("delete", "M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13")
    val trim = stroke("trim", circle(6f, 18f, 2.5f), circle(18f, 18f, 2.5f), "M7.5 16L17 4M16.5 16L7 4")
    val extend = stroke("extend", "M3 12h11", "M14 12h3", "M20 5v14", "M15 9l3 3-3 3")
    val offset = stroke("offset", "M4 18h10a6 6 0 0 0 6-6V4", "M4 13h8a3 3 0 0 0 3-3V4")
    val check = stroke("check", "M5 12l5 5 9-10", width = 2.2f)
    val newFile = stroke("new", "M6 3h8l4 4v14H6z", "M14 3v4h4", "M12 11v6M9 14h6")
    val save = stroke("save", "M5 4h11l3 3v13H5z", "M8 4v5h7V4", "M8 20v-6h8v6")
    val shown = stroke("shown", "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z", circle(12f, 12f, 3f))
    val hidden = stroke("hidden", "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z", "M4 4l16 16")
    val parameters = stroke("parameters", "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h0", circle(16f, 6f, 2f), circle(10f, 12f, 2f), circle(18f, 18f, 2f))
    val convert = stroke("convert", "M4 8l4-4 4 4-4 4z", "M12 16l4-4 4 4-4 4z", "M10 14l-2 2 2 2")
    val export = stroke("export", "M12 15V3M8 7l4-4 4 4", "M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6")
}
