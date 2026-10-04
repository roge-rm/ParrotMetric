package com.rm.parrotmetric.web

// Calls into the core's WebAssembly (web/core/bridge.cpp), which the page
// loads as globalThis.pmCore. Arguments and results go through its memory as
// bytes, little-endian, in the order WebCore.kt and dispatch.cpp agree on.

private fun coreArgs(n: Int): Int = js("globalThis.pmCore._pm_args(n)")
private fun coreCall(fn: Int, n: Int): Int = js("globalThis.pmCore._pm_call(fn, n)")
private fun heapSet(p: Int, v: Int): Unit = js("globalThis.pmCore.HEAPU8[p] = v")
private fun heapGet(p: Int): Int = js("globalThis.pmCore.HEAPU8[p]")

/** A call's arguments, written in order. */
internal class Args {
    private var b = ByteArray(256)
    private var n = 0

    private fun room(k: Int) {
        if (n + k > b.size) b = b.copyOf(maxOf(b.size * 2, n + k))
    }

    fun int(v: Int) {
        room(4)
        for (i in 0 until 4) b[n++] = (v shr (8 * i)).toByte()
    }

    fun long(v: Long) {
        room(8)
        for (i in 0 until 8) b[n++] = (v shr (8 * i)).toByte()
    }

    fun float(v: Float) = int(v.toRawBits())
    fun double(v: Double) = long(v.toRawBits())

    fun boolean(v: Boolean) {
        room(1)
        b[n++] = if (v) 1 else 0
    }

    private fun raw(a: ByteArray) {
        room(a.size)
        a.copyInto(b, n)
        n += a.size
    }

    fun string(v: String) {
        val u = v.encodeToByteArray()
        int(u.size)
        raw(u)
    }

    fun ints(a: IntArray) {
        int(a.size)
        a.forEach(::int)
    }

    fun longs(a: LongArray) {
        int(a.size)
        a.forEach(::long)
    }

    fun floats(a: FloatArray) {
        int(a.size)
        a.forEach(::float)
    }

    fun doubles(a: DoubleArray) {
        int(a.size)
        a.forEach(::double)
    }

    fun bytes(a: ByteArray) {
        int(a.size)
        raw(a)
    }

    fun strings(a: Array<String>) {
        int(a.size)
        a.forEach(::string)
    }

    /** Copies the arguments into the core's memory; returns how many bytes. */
    fun send(): Int {
        val p = coreArgs(n)
        for (i in 0 until n) heapSet(p + i, b[i].toInt())
        return n
    }
}

/** What a call gave back, read in order. Strings and arrays can be null. */
internal class Result(private val b: ByteArray) {
    private var i = 1

    fun int(): Int {
        var v = 0
        for (k in 0 until 4) v = v or ((b[i + k].toInt() and 255) shl (8 * k))
        i += 4
        return v
    }

    fun long(): Long {
        var v = 0L
        for (k in 0 until 8) v = v or ((b[i + k].toLong() and 255L) shl (8 * k))
        i += 8
        return v
    }

    fun float() = Float.fromBits(int())
    fun double() = Double.fromBits(long())
    fun boolean() = b[i++].toInt() != 0
    private fun present() = b[i++].toInt() != 0

    private fun text(): String {
        val n = int()
        val s = b.decodeToString(i, i + n)
        i += n
        return s
    }

    fun string(): String? = if (present()) text() else null
    fun ints(): IntArray? = if (present()) IntArray(int()) { int() } else null
    fun longs(): LongArray? = if (present()) LongArray(int()) { long() } else null
    fun floats(): FloatArray? = if (present()) FloatArray(int()) { float() } else null
    fun doubles(): DoubleArray? = if (present()) DoubleArray(int()) { double() } else null

    fun bytes(): ByteArray? {
        if (!present()) return null
        val n = int()
        val out = b.copyOfRange(i, i + n)
        i += n
        return out
    }

    fun strings(): Array<String>? = if (present()) Array(int()) { text() } else null
}

/** Runs call number fn; throws RuntimeException with the core's reason if it fails. */
internal fun call(fn: Int, a: Args): Result {
    val p = coreCall(fn, a.send())
    var size = 0
    for (k in 0 until 4) size = size or (heapGet(p + k) shl (8 * k))
    val bytes = ByteArray(size) { heapGet(p + 4 + it).toByte() }
    if (bytes[0].toInt() == 1) throw RuntimeException(bytes.decodeToString(1, size))
    return Result(bytes)
}
