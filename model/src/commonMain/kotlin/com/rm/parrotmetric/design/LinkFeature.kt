package com.rm.parrotmetric.design

/**
 * Another design built into this one, its shown bodies going into
 * [component], moved by ([dx], [dy], [dz]) mm. [file] is the design's file
 * name, to bring [text], a copy of it, up to date from; the copy keeps it
 * working when the file can't be read.
 */
data class LinkFeature(
    override val id: Int,
    override val name: String,
    val file: String,
    val text: String,
    val component: String,
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val dz: Double = 0.0,
) : Feature() {
    override fun key() = this
}
