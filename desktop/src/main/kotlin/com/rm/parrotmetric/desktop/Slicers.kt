package com.rm.parrotmetric.desktop

import java.io.File

/** A slicer found on this computer, and the command that opens a file in it. */
class Slicer(val name: String, val command: List<String>)

/**
 * The slicers installed here: on the PATH, as Flatpaks, as AppImages in the
 * usual folders, or in Program Files on Windows. Each kind is listed once.
 */
object Slicers {
    private class Known(val name: String, val programs: List<String>, val flatpak: String, val windows: List<String>, val appImage: String)

    private val known = listOf(
        Known("Bambu Studio", listOf("bambu-studio", "BambuStudio"), "com.bambulab.BambuStudio", listOf("Bambu Studio/bambu-studio.exe"), "bambu"),
        Known("OrcaSlicer", listOf("orca-slicer", "OrcaSlicer"), "io.github.softfever.OrcaSlicer", listOf("OrcaSlicer/orca-slicer.exe"), "orca"),
        Known("PrusaSlicer", listOf("prusa-slicer", "PrusaSlicer"), "com.prusa3d.PrusaSlicer", listOf("Prusa3D/PrusaSlicer/prusa-slicer.exe"), "prusaslicer"),
        Known("Cura", listOf("cura", "UltiMaker-Cura"), "com.ultimaker.cura", emptyList(), "cura"),
    )

    fun find(windows: Boolean): List<Slicer> = known.mapNotNull { k -> if (windows) onWindows(k) else onLinux(k) }

    private fun onLinux(k: Known): Slicer? {
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }
        for (p in k.programs) path.map { File(it, p) }.firstOrNull { it.canExecute() }?.let { return Slicer(k.name, listOf(it.path)) }
        val flatpaks = listOf(File("/var/lib/flatpak/app", k.flatpak), File(System.getProperty("user.home"), ".local/share/flatpak/app/${k.flatpak}"))
        if (flatpaks.any { it.isDirectory }) return Slicer(k.name, listOf("flatpak", "run", k.flatpak))
        val home = System.getProperty("user.home")
        val image = listOf("Applications", "Downloads", ".local/bin", "bin").flatMap { File(home, it).listFiles()?.toList().orEmpty() }
            .filter { it.name.lowercase().let { n -> k.appImage in n && n.endsWith(".appimage") } && it.canExecute() }
            .maxByOrNull { it.lastModified() }
        return image?.let { Slicer(k.name, listOf(it.path)) }
    }

    private fun onWindows(k: Known): Slicer? {
        val roots = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA")?.let { "$it/Programs" })
        for (root in roots) {
            for (rel in k.windows) File(root, rel).takeIf { it.isFile }?.let { return Slicer(k.name, listOf(it.path)) }
            // Cura's folder is named with its version, such as "UltiMaker Cura 5.8.0".
            if (k.name == "Cura") File(root).listFiles()?.filter { it.name.startsWith("UltiMaker Cura") }?.maxByOrNull { it.name }
                ?.let { File(it, "UltiMaker-Cura.exe") }?.takeIf { it.isFile }?.let { return Slicer(k.name, listOf(it.path)) }
        }
        return null
    }
}
