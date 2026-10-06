package com.rm.parrotmetric.app

import kotlin.io.encoding.Base64

/** A server's answer: its status code and body. */
class HttpReply(val code: Int, val body: ByteArray)

/** Plain HTTP requests, which each platform makes its own way. Calls are made off the main thread. */
interface Http {
    /** Sends a request; null if the server couldn't be reached. */
    suspend fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpReply?
}

/** A WebDAV server's address and login, as kept in the settings. */
data class DavLogin(val url: String, val user: String, val password: String) {
    fun token() = listOf("dav", url, user, password).joinToString("\t")

    companion object {
        fun from(token: String): DavLogin? {
            val parts = token.split('\t')
            if (parts.size != 4 || parts[0] != "dav") return null
            return DavLogin(parts[1], parts[2], parts[3])
        }
    }
}

/**
 * A projects folder on a WebDAV server, such as a Nextcloud or ownCloud
 * folder, or any server that speaks WebDAV. Designs are .pmet files directly
 * in it.
 */
class WebDavFolder(private val login: DavLogin, private val http: Http) : ProjectFolder {
    private val base = login.url.trim().trimEnd('/') + "/"
    private val auth = if (login.user.isEmpty()) emptyMap() else
        mapOf("Authorization" to "Basic " + Base64.encode("${login.user}:${login.password}".encodeToByteArray()))

    override val name: String = base.substringAfter("://").trimEnd('/')

    private fun fileUrl(name: String) = base + encodePath(name)

    private suspend fun propfind(url: String, depth: Int): HttpReply? = http.send(
        "PROPFIND", url,
        auth + mapOf("Depth" to depth.toString(), "Content-Type" to "application/xml; charset=utf-8"),
        PROPS.encodeToByteArray(),
    )

    /** Why the folder can't be used, or null if it can. */
    suspend fun problem(): String? {
        val r = propfind(base, 0) ?: return "The server can't be reached"
        return when (r.code) {
            207, 200 -> null
            401, 403 -> "The server refused that user name or password"
            404 -> "There's no folder at that address"
            405 -> "That address isn't a WebDAV folder"
            else -> "The server answered ${r.code}"
        }
    }

    override suspend fun list(): List<ProjectFile> {
        val r = propfind(base, 1) ?: return emptyList()
        if (r.code != 207) return emptyList()
        return davEntries(r.body.decodeToString())
            .mapNotNull { (href, modified) ->
                val name = decodePath(href.trimEnd('/').substringAfterLast('/'))
                if (name.endsWith(".pmet") && modified != null) ProjectFile(name, modified) else null
            }
            .sortedByDescending { it.modified }
    }

    override suspend fun read(name: String): ByteArray? {
        val r = http.send("GET", fileUrl(name), auth, null) ?: return null
        return if (r.code == 200) r.body else null
    }

    override suspend fun modified(name: String): Long? {
        val r = propfind(fileUrl(name), 0) ?: return null
        if (r.code != 207) return null
        return davEntries(r.body.decodeToString()).firstOrNull()?.second
    }

    override suspend fun write(name: String, bytes: ByteArray): Long? {
        val r = http.send("PUT", fileUrl(name), auth + mapOf("Content-Type" to "application/octet-stream"), bytes) ?: return null
        if (r.code !in 200..299) return null
        return modified(name)
    }

    override suspend fun rename(from: String, to: String): Boolean {
        val r = http.send("MOVE", fileUrl(from), auth + mapOf("Destination" to fileUrl(to), "Overwrite" to "F"), null) ?: return false
        return r.code in 200..299
    }

    private companion object {
        const val PROPS = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:getlastmodified/></d:prop></d:propfind>"""
    }
}

/** Each response in a PROPFIND answer: its href and last modified time in ms, whatever prefix the server gives the DAV namespace. */
internal fun davEntries(xml: String): List<Pair<String, Long?>> {
    val response = Regex("<(?:[A-Za-z0-9]+:)?response[\\s>].*?</(?:[A-Za-z0-9]+:)?response>", RegexOption.DOT_MATCHES_ALL)
    val href = Regex("<(?:[A-Za-z0-9]+:)?href[^>]*>(.*?)</", RegexOption.DOT_MATCHES_ALL)
    val modified = Regex("<(?:[A-Za-z0-9]+:)?getlastmodified[^>]*>(.*?)</", RegexOption.DOT_MATCHES_ALL)
    return response.findAll(xml).mapNotNull { m ->
        val h = href.find(m.value)?.groupValues?.get(1)?.trim()?.let(::xmlText) ?: return@mapNotNull null
        h to modified.find(m.value)?.groupValues?.get(1)?.trim()?.let(::httpDate)
    }.toList()
}

private fun xmlText(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

/** An HTTP date such as "Sat, 04 Oct 2026 14:28:00 GMT", in ms since 1970. */
internal fun httpDate(s: String): Long? {
    val parts = s.substringAfter(", ").split(' ', ':')
    if (parts.size < 6) return null
    val day = parts[0].toIntOrNull() ?: return null
    val month = MONTHS.indexOf(parts[1].take(3).lowercase()).takeIf { it >= 0 } ?: return null
    val year = parts[2].toIntOrNull() ?: return null
    val h = parts[3].toIntOrNull() ?: return null
    val min = parts[4].toIntOrNull() ?: return null
    val sec = parts[5].toIntOrNull() ?: return null
    return ((daysFrom1970(year, month + 1, day) * 24 + h) * 60 + min) * 60_000L + sec * 1000L
}

private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

/** Days from 1970-01-01 to a date on the Gregorian calendar. */
private fun daysFrom1970(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097L + doe - 719468
}

/** Percent-encodes a file name for a URL path. */
internal fun encodePath(name: String): String = buildString {
    for (b in name.encodeToByteArray()) {
        val c = b.toInt() and 255
        if (c.toChar().isLetterOrDigit() && c < 128 || c.toChar() in "-._~") append(c.toChar())
        else append('%').append(HEX[c shr 4]).append(HEX[c and 15])
    }
}

/** Undoes percent-encoding. */
internal fun decodePath(s: String): String {
    val out = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length) {
            val v = s.substring(i + 1, i + 3).toIntOrNull(16)
            if (v != null) { out.add(v.toByte()); i += 3; continue }
        }
        out.addAll(c.toString().encodeToByteArray().toList())
        i++
    }
    return out.toByteArray().decodeToString()
}

private const val HEX = "0123456789ABCDEF"
