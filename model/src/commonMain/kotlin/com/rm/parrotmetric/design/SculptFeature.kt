package com.rm.parrotmetric.design

/**
 * A sculpted mesh. [mesh] is the finished mesh, packed by the core. It takes
 * the place of the body labelled [body], or with none is a new body. Steps
 * before it don't change it: to change it, it's sculpted again.
 */
class SculptFeature(override val id: Int, override val name: String, val body: String?, val mesh: ByteArray) : Feature() {
    override fun key(): Any = listOf(id, name, body, mesh)
}
