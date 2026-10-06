package com.rm.parrotmetric.app

/** A design in the projects folder: its file name and when it last changed, in ms since 1970. */
data class ProjectFile(val name: String, val modified: Long)

/**
 * A folder the app keeps designs in, such as one a sync app (Nextcloud,
 * Syncthing, Drive and the like) looks after, so they're the same on every
 * device. Calls are made off the main thread.
 */
interface ProjectFolder {
    /** What to call it in Settings. */
    val name: String

    /** The designs in it, newest first. */
    suspend fun list(): List<ProjectFile>

    suspend fun read(name: String): ByteArray?

    /** When a file last changed, or null if it isn't there. */
    suspend fun modified(name: String): Long?

    /** Writes a whole file, making it if need be. Its new modified time, or null if it couldn't. */
    suspend fun write(name: String, bytes: ByteArray): Long?

    /** Gives a file a new name. False if it couldn't, leaving it as it was. */
    suspend fun rename(from: String, to: String): Boolean = false
}

/** A file name for a design called [title], without characters file systems refuse. */
fun projectFileName(title: String): String {
    val clean = title.map { if (it in "/\\:*?\"<>|" || it.code < 32) '-' else it }.joinToString("").trim().trim('.')
    return (clean.ifEmpty { "Untitled" }) + ".pmet"
}

/** [name], or with " 2", " 3" and so on before .pmet, whichever isn't in [taken]. */
fun freeName(name: String, taken: Set<String>): String {
    if (name !in taken) return name
    val base = name.removeSuffix(".pmet")
    var n = 2
    while ("$base $n.pmet" in taken) n++
    return "$base $n.pmet"
}
