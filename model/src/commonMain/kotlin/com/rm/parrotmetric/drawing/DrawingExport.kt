package com.rm.parrotmetric.drawing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Writes a drawing's marks as PDF, DXF or SVG, at its size on the sheet, mm. */
object DrawingExport {
    /** Line widths, mm. */
    fun width(p: Pen) = when (p) {
        Pen.Visible -> 0.5
        Pen.Hidden -> 0.35
        Pen.Thin -> 0.25
    }

    /** Dashes for hidden lines: on, off, mm. */
    val DASH = doubleArrayOf(3.0, 1.5)

    /** Arrowheads: length and half width, mm. */
    const val HEAD_LENGTH = 3.0
    const val HEAD_HALF = 0.6

    /** An arrowhead's three corners: tip, then the two at its back. */
    fun headCorners(h: Mark.Head): List<Pair<Double, Double>> {
        val bx = h.x - h.dx * HEAD_LENGTH; val by = h.y - h.dy * HEAD_LENGTH
        return listOf(h.x to h.y, (bx - h.dy * HEAD_HALF) to (by + h.dx * HEAD_HALF), (bx + h.dy * HEAD_HALF) to (by - h.dx * HEAD_HALF))
    }

    /** The cap height of text drawn at a font size of 1. */
    const val CAP = 0.718

    /** Helvetica's widths for ASCII 32 to 126, thousandths of the font size. */
    private val widths = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
    )

    /** How wide [text] is in Helvetica with capitals [height] mm tall. */
    fun textWidth(text: String, height: Double): Double {
        val size = height / CAP
        return text.sumOf { c -> (if (c.code in 32..126) widths[c.code - 32] else 667).toDouble() } / 1000 * size
    }

    // PDF.

    /** A one-page vector PDF of the sheet. */
    fun pdf(d: Drawing, marks: List<Mark>): ByteArray {
        val pt = 72 / 25.4
        val w = d.width * pt; val h = d.height * pt
        val c = StringBuilder()
        fun n(v: Double) = fmt(v * pt)
        c.append("1 J 1 j\n")
        for (pen in Pen.entries) {
            val lines = marks.filter { (it is Mark.Line && it.pen == pen) || (it is Mark.Arc && it.pen == pen) || (it is Mark.Poly && it.pen == pen) }
            if (lines.isEmpty()) continue
            c.append(n(width(pen))).append(" w ")
            c.append(if (pen == Pen.Hidden) "[${n(DASH[0])} ${n(DASH[1])}] 0 d\n" else "[] 0 d\n")
            for (m in lines) when (m) {
                is Mark.Line -> c.append("${n(m.x1)} ${n(m.y1)} m ${n(m.x2)} ${n(m.y2)} l S\n")
                is Mark.Poly -> {
                    c.append("${n(m.points[0])} ${n(m.points[1])} m")
                    for (k in 1 until m.points.size / 2) c.append(" ${n(m.points[2 * k])} ${n(m.points[2 * k + 1])} l")
                    c.append(" S\n")
                }
                is Mark.Arc -> {
                    val pieces = arcBeziers(m)
                    c.append("${n(pieces[0][0])} ${n(pieces[0][1])} m\n")
                    for (p in pieces) c.append("${n(p[2])} ${n(p[3])} ${n(p[4])} ${n(p[5])} ${n(p[6])} ${n(p[7])} c\n")
                    c.append("S\n")
                }
                else -> {}
            }
        }
        for (m in marks.filterIsInstance<Mark.Head>()) {
            val k = headCorners(m)
            c.append("${n(k[0].first)} ${n(k[0].second)} m ${n(k[1].first)} ${n(k[1].second)} l ${n(k[2].first)} ${n(k[2].second)} l h f\n")
        }
        for (m in marks.filterIsInstance<Mark.Text>()) {
            if (m.text.isEmpty()) continue
            val size = m.height / CAP
            val shift = when (m.anchor) { Anchor.Start -> 0.0; Anchor.Middle -> textWidth(m.text, m.height) / 2; Anchor.End -> textWidth(m.text, m.height) }
            val a = m.angle * PI / 180
            val x = m.x - cos(a) * shift; val y = m.y - sin(a) * shift
            c.append("BT /F1 ${n(size)} Tf ${fmt(cos(a))} ${fmt(sin(a))} ${fmt(-sin(a))} ${fmt(cos(a))} ${n(x)} ${n(y)} Tm (")
            c.append(pdfString(m.text)).append(") Tj ET\n")
        }
        val content = c.toString().encodeToByteArray()
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(w)} ${fmt(h)}] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
            null,
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
            "<< /Producer (ParrotMetric) >>",
        )
        val out = ByteBuilder()
        out.add("%PDF-1.4\n%âãÏÓ\n".encodeToByteArray())
        val offsets = mutableListOf<Int>()
        for ((i, o) in objects.withIndex()) {
            offsets += out.size
            if (o == null) {
                out.add("${i + 1} 0 obj\n<< /Length ${content.size} >>\nstream\n".encodeToByteArray())
                out.add(content)
                out.add("\nendstream\nendobj\n".encodeToByteArray())
            } else out.add("${i + 1} 0 obj\n$o\nendobj\n".encodeToByteArray())
        }
        val xref = out.size
        val x = StringBuilder("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) x.append(o.toString().padStart(10, '0')).append(" 00000 n \n")
        x.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R /Info 6 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.add(x.toString().encodeToByteArray())
        return out.bytes()
    }

    /** Text for a PDF string in WinAnsi: Latin-1 as it is, anything else a question mark. */
    private fun pdfString(s: String): String = buildString {
        for (ch in s) when {
            ch == '(' || ch == ')' || ch == '\\' -> append('\\').append(ch)
            ch.code in 32..126 -> append(ch)
            ch.code in 160..255 -> append('\\').append(ch.code.toString(8).padStart(3, '0'))
            else -> append('?')
        }
    }

    /** An arc as cubic Bezier pieces of a quarter turn or less: start x, y, two controls, end x, y. */
    fun arcBeziers(m: Mark.Arc): List<DoubleArray> {
        var sweep = m.a1 - m.a0
        while (sweep <= 0) sweep += 2 * PI
        if (sweep > 2 * PI) sweep = 2 * PI
        val count = ceil(sweep / (PI / 2) - 1e-9).toInt().coerceAtLeast(1)
        val step = sweep / count
        val k = 4.0 / 3 * tan(step / 4)
        return List(count) { i ->
            val t0 = m.a0 + i * step; val t1 = t0 + step
            val x0 = m.cx + m.r * cos(t0); val y0 = m.cy + m.r * sin(t0)
            val x3 = m.cx + m.r * cos(t1); val y3 = m.cy + m.r * sin(t1)
            doubleArrayOf(
                x0, y0,
                x0 - k * m.r * sin(t0), y0 + k * m.r * cos(t0),
                x3 + k * m.r * sin(t1), y3 - k * m.r * cos(t1),
                x3, y3,
            )
        }
    }

    // DXF.

    /** An R12 DXF of the sheet: seen lines, hidden lines (dashed) and annotations on their own layers. */
    fun dxf(marks: List<Mark>): String {
        val s = StringBuilder()
        fun g(code: Int, v: Any) { s.append(code).append('\n').append(if (v is Double) fmt(v) else v.toString()).append('\n') }
        fun layer(p: Pen) = when (p) { Pen.Visible -> "VISIBLE"; Pen.Hidden -> "HIDDEN"; Pen.Thin -> "NOTES" }
        g(0, "SECTION"); g(2, "HEADER"); g(9, "\$ACADVER"); g(1, "AC1009"); g(0, "ENDSEC")
        g(0, "SECTION"); g(2, "TABLES")
        g(0, "TABLE"); g(2, "LTYPE"); g(70, 2)
        g(0, "LTYPE"); g(2, "CONTINUOUS"); g(70, 0); g(3, "Solid line"); g(72, 65); g(73, 0); g(40, 0.0)
        g(0, "LTYPE"); g(2, "DASHED"); g(70, 0); g(3, "Dashed"); g(72, 65); g(73, 2); g(40, DASH[0] + DASH[1]); g(49, DASH[0]); g(49, -DASH[1])
        g(0, "ENDTAB")
        g(0, "TABLE"); g(2, "LAYER"); g(70, 3)
        for ((name, colour, type) in listOf(Triple("VISIBLE", 7, "CONTINUOUS"), Triple("HIDDEN", 8, "DASHED"), Triple("NOTES", 5, "CONTINUOUS"))) {
            g(0, "LAYER"); g(2, name); g(70, 0); g(62, colour); g(6, type)
        }
        g(0, "ENDTAB"); g(0, "ENDSEC")
        g(0, "SECTION"); g(2, "ENTITIES")
        for (m in marks) when (m) {
            is Mark.Line -> { g(0, "LINE"); g(8, layer(m.pen)); g(10, m.x1); g(20, m.y1); g(30, 0.0); g(11, m.x2); g(21, m.y2); g(31, 0.0) }
            is Mark.Poly -> {
                g(0, "POLYLINE"); g(8, layer(m.pen)); g(66, 1); g(10, 0.0); g(20, 0.0); g(30, 0.0)
                for (k in 0 until m.points.size / 2) { g(0, "VERTEX"); g(8, layer(m.pen)); g(10, m.points[2 * k]); g(20, m.points[2 * k + 1]); g(30, 0.0) }
                g(0, "SEQEND"); g(8, layer(m.pen))
            }
            is Mark.Arc -> {
                if (abs(m.a1 - m.a0 - 2 * PI) < 1e-9) {
                    g(0, "CIRCLE"); g(8, layer(m.pen)); g(10, m.cx); g(20, m.cy); g(30, 0.0); g(40, m.r)
                } else {
                    g(0, "ARC"); g(8, layer(m.pen)); g(10, m.cx); g(20, m.cy); g(30, 0.0); g(40, m.r)
                    g(50, m.a0 * 180 / PI); g(51, m.a1 * 180 / PI)
                }
            }
            is Mark.Head -> {
                val k = headCorners(m)
                g(0, "SOLID"); g(8, "NOTES")
                g(10, k[0].first); g(20, k[0].second); g(30, 0.0)
                g(11, k[1].first); g(21, k[1].second); g(31, 0.0)
                g(12, k[2].first); g(22, k[2].second); g(32, 0.0)
                g(13, k[2].first); g(23, k[2].second); g(33, 0.0)
            }
            is Mark.Text -> {
                if (m.text.isEmpty()) continue
                g(0, "TEXT"); g(8, "NOTES"); g(10, m.x); g(20, m.y); g(30, 0.0); g(40, m.height); g(1, m.text.replace("Ø", "%%c"))
                if (m.angle != 0.0) g(50, m.angle)
                val just = when (m.anchor) { Anchor.Start -> 0; Anchor.Middle -> 1; Anchor.End -> 2 }
                if (just != 0) { g(72, just); g(11, m.x); g(21, m.y); g(31, 0.0) }
            }
        }
        g(0, "ENDSEC"); g(0, "EOF")
        return s.toString()
    }

    // SVG.

    /** An SVG of the sheet, sized in mm, on white. */
    fun svg(d: Drawing, marks: List<Mark>): String {
        val h = d.height
        fun y(v: Double) = fmt(h - v)
        val s = StringBuilder()
        s.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${fmt(d.width)}mm\" height=\"${fmt(h)}mm\" viewBox=\"0 0 ${fmt(d.width)} ${fmt(h)}\">\n")
        s.append("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n")
        s.append("<g fill=\"none\" stroke=\"black\" stroke-linecap=\"round\" stroke-linejoin=\"round\">\n")
        for (m in marks) {
            val pen = when (m) { is Mark.Line -> m.pen; is Mark.Arc -> m.pen; is Mark.Poly -> m.pen; else -> continue }
            val style = "stroke-width=\"${fmt(width(pen))}\"" + if (pen == Pen.Hidden) " stroke-dasharray=\"${fmt(DASH[0])} ${fmt(DASH[1])}\"" else ""
            when (m) {
                is Mark.Line -> s.append("<line x1=\"${fmt(m.x1)}\" y1=\"${y(m.y1)}\" x2=\"${fmt(m.x2)}\" y2=\"${y(m.y2)}\" $style/>\n")
                is Mark.Poly -> s.append("<polyline points=\"${(0 until m.points.size / 2).joinToString(" ") { k -> "${fmt(m.points[2 * k])},${y(m.points[2 * k + 1])}" }}\" $style/>\n")
                is Mark.Arc -> if (abs(m.a1 - m.a0 - 2 * PI) < 1e-9) {
                    s.append("<circle cx=\"${fmt(m.cx)}\" cy=\"${y(m.cy)}\" r=\"${fmt(m.r)}\" $style/>\n")
                } else {
                    var sweep = m.a1 - m.a0
                    while (sweep <= 0) sweep += 2 * PI
                    val x0 = m.cx + m.r * cos(m.a0); val y0 = m.cy + m.r * sin(m.a0)
                    val x1 = m.cx + m.r * cos(m.a1); val y1 = m.cy + m.r * sin(m.a1)
                    // Anticlockwise with y up is clockwise with y down: sweep flag 0.
                    s.append("<path d=\"M${fmt(x0)} ${y(y0)} A${fmt(m.r)} ${fmt(m.r)} 0 ${if (sweep > PI) 1 else 0} 0 ${fmt(x1)} ${y(y1)}\" $style/>\n")
                }
                else -> {}
            }
        }
        s.append("</g>\n<g fill=\"black\" stroke=\"none\">\n")
        for (m in marks.filterIsInstance<Mark.Head>()) {
            val k = headCorners(m)
            s.append("<polygon points=\"${k.joinToString(" ") { "${fmt(it.first)},${y(it.second)}" }}\"/>\n")
        }
        for (m in marks.filterIsInstance<Mark.Text>()) {
            if (m.text.isEmpty()) continue
            val anchor = when (m.anchor) { Anchor.Start -> "start"; Anchor.Middle -> "middle"; Anchor.End -> "end" }
            val rotate = if (m.angle != 0.0) " transform=\"rotate(${fmt(-m.angle)} ${fmt(m.x)} ${y(m.y)})\"" else ""
            s.append("<text x=\"${fmt(m.x)}\" y=\"${y(m.y)}\" font-family=\"Helvetica, Arial, sans-serif\" font-size=\"${fmt(m.height / CAP)}\" text-anchor=\"$anchor\"$rotate>")
            s.append(m.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append("</text>\n")
        }
        s.append("</g>\n</svg>\n")
        return s.toString()
    }

    /** A number with up to four decimals, no exponent. */
    fun fmt(v: Double): String {
        val r = kotlin.math.round(v * 10000) / 10000
        if (r == kotlin.math.round(r)) return r.toLong().toString()
        val neg = r < 0
        val a = abs(r)
        val whole = a.toLong()
        val frac = kotlin.math.round((a - whole) * 10000).toLong()
        val text = if (frac >= 10000) "${whole + 1}" else "$whole.${frac.toString().padStart(4, '0').trimEnd('0')}"
        return if (neg) "-$text" else text
    }

    private class ByteBuilder {
        private var buf = ByteArray(4096)
        var size = 0
            private set
        fun add(b: ByteArray) {
            if (size + b.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + b.size))
            b.copyInto(buf, size)
            size += b.size
        }
        fun bytes() = buf.copyOf(size)
    }
}
