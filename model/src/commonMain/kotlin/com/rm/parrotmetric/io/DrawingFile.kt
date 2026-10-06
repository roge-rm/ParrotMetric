package com.rm.parrotmetric.io

import com.rm.parrotmetric.drawing.DimensionKind
import com.rm.parrotmetric.drawing.Drawing
import com.rm.parrotmetric.drawing.DrawingDimension
import com.rm.parrotmetric.drawing.DrawingNote
import com.rm.parrotmetric.drawing.DrawingView
import com.rm.parrotmetric.drawing.Paper
import com.rm.parrotmetric.drawing.ViewSide

/** A design's drawing in the design file. Names it doesn't know are skipped, so older apps can still open newer files. */
internal object DrawingFile {
    fun write(d: Drawing): Map<String, Any?> = mapOf(
        "paper" to d.paper.name,
        "portrait" to d.portrait,
        "scale" to d.scale,
        "firstAngle" to d.firstAngle,
        "title" to d.title,
        "drawnBy" to d.drawnBy,
        "views" to d.views.map { v ->
            mapOf("id" to v.id, "side" to v.side.name, "x" to v.x, "y" to v.y, "hidden" to v.hidden, "scale" to v.scale)
        },
        "dimensions" to d.dimensions.map { m ->
            mapOf(
                "id" to m.id, "view" to m.view, "kind" to m.kind.name,
                "ax" to m.ax, "ay" to m.ay, "bx" to m.bx, "by" to m.by, "offset" to m.offset, "box" to m.box,
            )
        },
        "notes" to d.notes.map { n -> mapOf("id" to n.id, "x" to n.x, "y" to n.y, "text" to n.text, "height" to n.height) },
    )

    fun read(o: Json.Obj): Drawing = Drawing(
        paper = Paper.entries.firstOrNull { it.name == (o["paper"] as? Json.Str)?.value } ?: Paper.A4,
        portrait = o.bool("portrait"),
        scale = o.numOr("scale", 1.0),
        firstAngle = o.bool("firstAngle"),
        title = (o["title"] as? Json.Str)?.value ?: "",
        drawnBy = (o["drawnBy"] as? Json.Str)?.value ?: "",
        views = o.arr("views").mapNotNull { v ->
            v as Json.Obj
            val side = ViewSide.entries.firstOrNull { it.name == (v["side"] as? Json.Str)?.value } ?: return@mapNotNull null
            DrawingView(v.int("id"), side, v.num("x"), v.num("y"), v.bool("hidden"), (v["scale"] as? Json.Num)?.value)
        },
        dimensions = o.arr("dimensions").mapNotNull { m ->
            m as Json.Obj
            val kind = DimensionKind.entries.firstOrNull { it.name == (m["kind"] as? Json.Str)?.value } ?: return@mapNotNull null
            DrawingDimension(
                m.int("id"), m.int("view"), kind, m.num("ax"), m.num("ay"), m.num("bx"), m.num("by"), m.numOr("offset", 10.0),
                m.arr("box").mapNotNull { (it as? Json.Num)?.value }.takeIf { it.size == 4 } ?: emptyList(),
            )
        },
        notes = o.arr("notes").map { n ->
            n as Json.Obj
            DrawingNote(n.int("id"), n.num("x"), n.num("y"), n.str("text"), n.numOr("height", 3.5))
        },
    )
}
