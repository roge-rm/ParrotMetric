package com.rm.parrotmetric.io

import com.rm.parrotmetric.design.Parameter
import com.rm.parrotmetric.design.Parametrics
import com.rm.parrotmetric.sketch.Expression

/** The parameters table as CSV: a row each of name, expression and value. */
object ParameterCsv {
    /** What reading a CSV did: the parameters after it, and how many were changed, added and skipped. */
    data class Read(val parameters: List<Parameter>, val changed: Int, val added: Int, val skipped: Int)

    fun write(parameters: List<Parameter>): String {
        val values = Parametrics.values(parameters)
        val out = StringBuilder("name,expression,value\n")
        for (p in parameters) {
            val v = values[p.name]?.let { number(it) } ?: ""
            out.append(listOf(p.name, p.expression, v).joinToString(",") { field(it) }).append('\n')
        }
        return out.toString()
    }

    /**
     * [parameters] with those named in [text] set to its expressions, and new
     * names added at the end. A row with no expression takes its value. A
     * first row starting "name" is a heading. Rows whose name isn't a
     * parameter name are skipped.
     */
    fun read(text: String, parameters: List<Parameter>): Read {
        val rows = rows(text.removePrefix("﻿"))
        val out = parameters.toMutableList()
        var changed = 0; var added = 0; var skipped = 0
        for ((i, row) in rows.withIndex()) {
            val name = row.getOrElse(0) { "" }.trim()
            if (i == 0 && name.equals("name", ignoreCase = true)) continue
            if (row.all { it.isBlank() }) continue
            val expression = row.getOrElse(1) { "" }.trim().ifEmpty { row.getOrElse(2) { "" }.trim() }
            if (!Expression.isName(name) || expression.isEmpty()) { skipped++; continue }
            val at = out.indexOfFirst { it.name == name }
            if (at < 0) { out += Parameter(name, expression); added++ }
            else if (out[at].expression != expression) { out[at] = Parameter(name, expression); changed++ }
        }
        return Read(out, changed, added, skipped)
    }

    /** The cells of each line, split on commas, or on semicolons when the first line has those and no commas. */
    private fun rows(text: String): List<List<String>> {
        val first = text.substringBefore('\n')
        val sep = if (';' in first && ',' !in first) ';' else ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                quoted -> cell.append(c)
                c == sep -> { row += cell.toString(); cell.clear() }
                c == '\n' || c == '\r' -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.clear()
                    rows += row; row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }

    /** A cell, quoted if it holds a comma, a quote or a line break. */
    private fun field(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' || it == ';' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun number(v: Double): String {
        val r = kotlin.math.round(v * 1e6) / 1e6
        return if (r == kotlin.math.floor(r) && kotlin.math.abs(r) < 1e15) r.toLong().toString() else r.toString()
    }
}
