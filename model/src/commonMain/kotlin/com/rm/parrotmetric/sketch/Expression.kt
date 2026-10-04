package com.rm.parrotmetric.sketch

/**
 * Reads a number typed into a dimension: plain arithmetic with + - * / and
 * brackets, and a unit after any number (mm, cm, m, in, ", deg, °). Lengths
 * come out in mm and angles in degrees. Null if it isn't something it can read.
 */
object Expression {
    fun evaluate(text: String): Double? = try {
        Parser(text.trim()).run {
            val v = sum()
            skipSpace()
            if (pos == s.length) v else null
        }
    } catch (e: IllegalArgumentException) {
        null
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipSpace() {
            while (pos < s.length && s[pos] == ' ') pos++
        }

        fun sum(): Double {
            var v = product()
            while (true) {
                skipSpace()
                v = when (s.getOrNull(pos)) {
                    '+' -> { pos++; v + product() }
                    '-' -> { pos++; v - product() }
                    else -> return v
                }
            }
        }

        fun product(): Double {
            var v = unary()
            while (true) {
                skipSpace()
                v = when (s.getOrNull(pos)) {
                    '*', '×' -> { pos++; v * unary() }
                    '/', '÷' -> { pos++; val d = unary(); require(d != 0.0); v / d }
                    else -> return v
                }
            }
        }

        fun unary(): Double {
            skipSpace()
            return if (s.getOrNull(pos) == '-') { pos++; -unary() } else atom()
        }

        fun atom(): Double {
            skipSpace()
            if (s.getOrNull(pos) == '(') {
                pos++
                val v = sum()
                skipSpace()
                require(s.getOrNull(pos) == ')')
                pos++
                return v * unit()
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == ',')) pos++
            require(pos > start)
            val number = s.substring(start, pos).replace(',', '.').toDoubleOrNull()
            requireNotNull(number)
            return number * unit()
        }

        /** An optional unit after a number, as a factor to mm (or degrees). */
        fun unit(): Double {
            skipSpace()
            for ((name, factor) in units) {
                if (s.startsWith(name, pos, ignoreCase = true)) {
                    val end = pos + name.length
                    // "m" mustn't swallow the start of "mm".
                    if (name.last().isLetter() && end < s.length && s[end].isLetter()) continue
                    pos = end
                    return factor
                }
            }
            return 1.0
        }

        val units = listOf("mm" to 1.0, "cm" to 10.0, "m" to 1000.0, "in" to 25.4, "\"" to 25.4, "deg" to 1.0, "°" to 1.0)
    }
}
