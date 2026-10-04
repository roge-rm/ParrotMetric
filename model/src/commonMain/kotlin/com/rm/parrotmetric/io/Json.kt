package com.rm.parrotmetric.io

/** A small JSON reader and writer for the design file: objects keep their keys in order. */
sealed class Json {
    data class Obj(val fields: Map<String, Json>) : Json() {
        operator fun get(key: String) = fields[key]
        fun str(key: String) = (fields[key] as? Str)?.value ?: throw IllegalArgumentException("Missing $key")
        fun num(key: String) = (fields[key] as? Num)?.value ?: throw IllegalArgumentException("Missing $key")
        fun numOr(key: String, default: Double) = (fields[key] as? Num)?.value ?: default
        fun int(key: String) = num(key).toInt()
        fun bool(key: String) = (fields[key] as? Bool)?.value ?: false
        fun arr(key: String) = (fields[key] as? Arr)?.items ?: emptyList()
        fun obj(key: String) = fields[key] as? Obj ?: throw IllegalArgumentException("Missing $key")
    }
    data class Arr(val items: List<Json>) : Json()
    data class Str(val value: String) : Json()
    data class Num(val value: Double) : Json()
    data class Bool(val value: Boolean) : Json()
    data object Null : Json()

    // Final, so the data classes below don't write their own.
    final override fun toString(): String = buildString { write(this@Json, this) }

    companion object {
        fun obj(vararg pairs: Pair<String, Any?>) = Obj(pairs.associate { (k, v) -> k to of(v) })

        /** Wraps a Kotlin value: numbers, strings, booleans, lists, maps or Json. */
        fun of(v: Any?): Json = when (v) {
            null -> Null
            is Json -> v
            is String -> Str(v)
            is Number -> Num(v.toDouble())
            is Boolean -> Bool(v)
            is List<*> -> Arr(v.map { of(it) })
            is Map<*, *> -> Obj(v.entries.associate { (k, x) -> k.toString() to of(x) })
            else -> throw IllegalArgumentException("Can't write ${v::class}")
        }

        fun parse(text: String): Json = Reader(text).run {
            val v = value()
            space()
            if (i != text.length) fail()
            v
        }

        private fun write(j: Json, out: StringBuilder) {
            when (j) {
                is Obj -> {
                    out.append('{')
                    var first = true
                    for ((k, v) in j.fields) {
                        if (!first) out.append(',')
                        first = false
                        writeString(k, out)
                        out.append(':')
                        write(v, out)
                    }
                    out.append('}')
                }
                is Arr -> {
                    out.append('[')
                    j.items.forEachIndexed { i, v ->
                        if (i > 0) out.append(',')
                        write(v, out)
                    }
                    out.append(']')
                }
                is Str -> writeString(j.value, out)
                is Num -> {
                    val d = j.value
                    if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) out.append(d.toLong()) else out.append(d.toString())
                }
                is Bool -> out.append(j.value)
                Null -> out.append("null")
            }
        }

        private fun writeString(s: String, out: StringBuilder) {
            out.append('"')
            for (c in s) when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u" + c.code.toString(16).padStart(4, '0')) else out.append(c)
            }
            out.append('"')
        }
    }

    private class Reader(val s: String) {
        var i = 0

        fun fail(): Nothing = throw IllegalArgumentException("Not a design file (at character $i)")

        fun space() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Json {
            space()
            if (i >= s.length) fail()
            return when (s[i]) {
                '{' -> {
                    i++
                    val m = LinkedHashMap<String, Json>()
                    space()
                    if (s.getOrNull(i) == '}') { i++; return Obj(m) }
                    while (true) {
                        space()
                        val k = string()
                        space()
                        if (s.getOrNull(i) != ':') fail()
                        i++
                        m[k] = value()
                        space()
                        when (s.getOrNull(i)) {
                            ',' -> i++
                            '}' -> { i++; return Obj(m) }
                            else -> fail()
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") fail()
                }
                '[' -> {
                    i++
                    val l = mutableListOf<Json>()
                    space()
                    if (s.getOrNull(i) == ']') { i++; return Arr(l) }
                    while (true) {
                        l += value()
                        space()
                        when (s.getOrNull(i)) {
                            ',' -> i++
                            ']' -> { i++; return Arr(l) }
                            else -> fail()
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") fail()
                }
                '"' -> Str(string())
                't' -> word("true", Bool(true))
                'f' -> word("false", Bool(false))
                'n' -> word("null", Null)
                else -> number()
            }
        }

        fun word(w: String, v: Json): Json {
            if (!s.startsWith(w, i)) fail()
            i += w.length
            return v
        }

        fun number(): Json {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return Num(s.substring(start, i).toDoubleOrNull() ?: fail())
        }

        fun string(): String {
            if (s.getOrNull(i) != '"') fail()
            i++
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) fail()
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        val e = s.getOrNull(i++) ?: fail()
                        when (e) {
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'u' -> {
                                val hex = s.substring(i, minOf(i + 4, s.length))
                                b.append(hex.toIntOrNull(16)?.toChar() ?: fail())
                                i += 4
                            }
                            else -> b.append(e)
                        }
                    }
                    else -> b.append(c)
                }
            }
        }
    }
}
