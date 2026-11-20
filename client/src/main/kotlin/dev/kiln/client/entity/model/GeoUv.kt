package dev.kiln.client.entity.model

/** Texture coordinates of a cube: Bedrock box UV (one corner, the usual cube layout) or explicit per face. */
sealed interface GeoUv {
    data class Box(val u: Double, val v: Double, val mirror: Boolean) : GeoUv

    /** Face name (`north`, `south`, `east`, `west`, `up`, `down`) -> (u, v, width, height); missing faces are not drawn. */
    data class Faces(val faces: Map<String, DoubleArray>) : GeoUv
}
