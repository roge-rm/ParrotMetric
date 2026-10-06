package com.rm.parrotmetric.design

/**
 * A sculpted mesh. [mesh] is what the core saved of the session: the mesh
 * with its mask, the strokes that made it and what it was made from. It
 * takes the place of the body labelled [body], or with none is a new body.
 * When the steps before change that body, the strokes are made again on it.
 */
class SculptFeature(override val id: Int, override val name: String, val body: String?, val mesh: ByteArray) : Feature() {
    override fun key(): Any = listOf(id, name, body, mesh)
}
