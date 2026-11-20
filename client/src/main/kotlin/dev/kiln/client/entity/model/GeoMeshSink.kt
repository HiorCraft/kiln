package dev.kiln.client.entity.model

/** Receives the vertices [GeoMesh] emits. */
fun interface GeoMeshSink {
    fun vertex(x: Float, y: Float, z: Float, u: Float, v: Float, nx: Float, ny: Float, nz: Float)
}
