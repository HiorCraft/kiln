package dev.kiln.client.entity.model

data class Vec3d(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3d) = Vec3d(x + o.x, y + o.y, z + o.z)

    companion object {
        val ZERO = Vec3d(0.0, 0.0, 0.0)
        val ONE = Vec3d(1.0, 1.0, 1.0)
    }
}
