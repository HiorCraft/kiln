package dev.kiln.client.entity.model

data class GeoCube(
    val origin: Vec3d,
    val size: Vec3d,
    val inflate: Double,
    val uv: GeoUv,
    /** Own rotation of the cube around [pivot] (Bedrock degrees). */
    val pivot: Vec3d?,
    val rotation: Vec3d?,
)
