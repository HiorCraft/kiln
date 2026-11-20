package dev.kiln.client.entity.model

data class GeoBone(
    val name: String,
    val parent: String?,
    val pivot: Vec3d,
    /** Rest rotation (Bedrock degrees, applied Z, Y, X). */
    val rotation: Vec3d,
    val cubes: List<GeoCube>,
)
