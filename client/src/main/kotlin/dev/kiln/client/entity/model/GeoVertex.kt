package dev.kiln.client.entity.model

/** One emitted vertex in model space (pixels, Minecraft axes: Bedrock x mirrored) with texture coordinates 0..1. */
data class GeoVertex(val x: Double, val y: Double, val z: Double, val u: Float, val v: Float, val nx: Float, val ny: Float, val nz: Float)
