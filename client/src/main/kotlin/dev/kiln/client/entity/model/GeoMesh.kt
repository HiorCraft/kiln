package dev.kiln.client.entity.model

import org.joml.Matrix4f
import org.joml.Vector3f

/** Animated offsets of one bone (Bedrock units: degrees, pixels, factors), added to the rest pose. */
data class BonePose(val rotation: Vec3d = Vec3d.ZERO, val position: Vec3d = Vec3d.ZERO, val scale: Vec3d = Vec3d.ONE)

/** One emitted vertex in model space (pixels, Minecraft axes: Bedrock x mirrored) with texture coordinates 0..1. */
data class GeoVertex(val x: Double, val y: Double, val z: Double, val u: Float, val v: Float, val nx: Float, val ny: Float, val nz: Float)

/**
 * Turns a [GeoModel] in a pose into quads. Bedrock to Minecraft (as Blockbench / GeckoLib do): x is mirrored,
 * rotations around x and y change sign, rotations apply Z, then Y, then X around the bone pivot.
 */
object GeoMesh {
    fun interface Sink {
        fun vertex(x: Float, y: Float, z: Float, u: Float, v: Float, nx: Float, ny: Float, nz: Float)
    }

    /** Emits 4 vertices per visible cube face (counter-clockwise seen from outside) into [sink]. */
    fun emit(model: GeoModel, poses: Map<String, BonePose>, sink: Sink) {
        val matrices = HashMap<String, Matrix4f>()
        for (bone in model.bones) {
            val m = Matrix4f(bone.parent?.let { matrices[it] } ?: Matrix4f())
            val pose = poses[bone.name]
            val pivot = Vector3f(-bone.pivot.x.toFloat(), bone.pivot.y.toFloat(), bone.pivot.z.toFloat())
            if (pose != null) m.translate(-pose.position.x.toFloat(), pose.position.y.toFloat(), pose.position.z.toFloat())
            m.translate(pivot)
            val rx = bone.rotation.x + (pose?.rotation?.x ?: 0.0)
            val ry = bone.rotation.y + (pose?.rotation?.y ?: 0.0)
            val rz = bone.rotation.z + (pose?.rotation?.z ?: 0.0)
            m.rotateZ(Math.toRadians(rz).toFloat()).rotateY(Math.toRadians(-ry).toFloat()).rotateX(Math.toRadians(-rx).toFloat())
            if (pose != null) m.scale(pose.scale.x.toFloat(), pose.scale.y.toFloat(), pose.scale.z.toFloat())
            m.translate(-pivot.x, -pivot.y, -pivot.z)
            matrices[bone.name] = m
            for (cube in bone.cubes) emitCube(model, cube, m, sink)
        }
    }

    /** All vertices of [model] in a pose (tests, bounds). */
    fun vertices(model: GeoModel, poses: Map<String, BonePose>): List<GeoVertex> {
        val result = ArrayList<GeoVertex>()
        emit(model, poses) { x, y, z, u, v, nx, ny, nz -> result += GeoVertex(x.toDouble(), y.toDouble(), z.toDouble(), u, v, nx, ny, nz) }
        return result
    }

    private fun emitCube(model: GeoModel, cube: GeoCube, bone: Matrix4f, sink: Sink) {
        val m = Matrix4f(bone)
        if (cube.rotation != null) {
            val p = cube.pivot ?: Vec3d.ZERO
            m.translate(-p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
            m.rotateZ(Math.toRadians(cube.rotation.z).toFloat()).rotateY(Math.toRadians(-cube.rotation.y).toFloat()).rotateX(Math.toRadians(-cube.rotation.x).toFloat())
            m.translate(p.x.toFloat(), -p.y.toFloat(), -p.z.toFloat())
        }
        val i = cube.inflate
        // Minecraft space: Bedrock x range [ox, ox+sx] becomes [-(ox+sx), -ox]
        val x0 = (-(cube.origin.x + cube.size.x) - i).toFloat()
        val x1 = (-cube.origin.x + i).toFloat()
        val y0 = (cube.origin.y - i).toFloat()
        val y1 = (cube.origin.y + cube.size.y + i).toFloat()
        val z0 = (cube.origin.z - i).toFloat()
        val z1 = (cube.origin.z + cube.size.z + i).toFloat()
        val w = cube.size.x
        val h = cube.size.y
        val d = cube.size.z
        val faces: Map<String, DoubleArray> = when (val uv = cube.uv) {
            is GeoUv.Faces -> uv.faces
            is GeoUv.Box -> {
                val (u, v) = uv.u to uv.v
                mapOf(
                    "east" to doubleArrayOf(u, v + d, d, h),
                    "north" to doubleArrayOf(u + d, v + d, w, h),
                    "west" to doubleArrayOf(u + d + w, v + d, d, h),
                    "south" to doubleArrayOf(u + d + w + d, v + d, w, h),
                    "up" to doubleArrayOf(u + d, v, w, d),
                    "down" to doubleArrayOf(u + d + w, v, w, d),
                )
            }
        }
        val tw = model.textureWidth.toFloat()
        val th = model.textureHeight.toFloat()
        // corners per face (outside view, counter-clockwise), bedrock east (+x) is Minecraft -x after mirroring
        val quads = mapOf(
            "north" to (floatArrayOf(x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0) to floatArrayOf(0f, 0f, -1f)),
            "south" to (floatArrayOf(x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1) to floatArrayOf(0f, 0f, 1f)),
            "east" to (floatArrayOf(x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0) to floatArrayOf(-1f, 0f, 0f)),
            "west" to (floatArrayOf(x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1) to floatArrayOf(1f, 0f, 0f)),
            "up" to (floatArrayOf(x1, y1, z1, x0, y1, z1, x0, y1, z0, x1, y1, z0) to floatArrayOf(0f, 1f, 0f)),
            "down" to (floatArrayOf(x1, y0, z0, x0, y0, z0, x0, y0, z1, x1, y0, z1) to floatArrayOf(0f, -1f, 0f)),
        )
        val pos = Vector3f()
        val normal = Vector3f()
        for ((face, quad) in quads) {
            val uv = faces[face] ?: continue
            val (corners, n) = quad
            val u0 = (uv[0] / tw).toFloat()
            val v0 = (uv[1] / th).toFloat()
            val u1 = ((uv[0] + uv[2]) / tw).toFloat()
            val v1 = ((uv[1] + uv[3]) / th).toFloat()
            val us = floatArrayOf(u0, u1, u1, u0)
            val vs = floatArrayOf(v0, v0, v1, v1)
            m.transformDirection(normal.set(n[0], n[1], n[2])).normalize()
            for (k in 0 until 4) {
                m.transformPosition(pos.set(corners[k * 3], corners[k * 3 + 1], corners[k * 3 + 2]))
                sink.vertex(pos.x, pos.y, pos.z, us[k], vs[k], normal.x, normal.y, normal.z)
            }
        }
    }
}
