package dev.kiln.client.entity.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * A Bedrock entity geometry (`*.geo.json`, format 1.12+, as exported by Blockbench). Bones are ordered so that every
 * parent comes before its children. Coordinates stay in Bedrock space (pixels, y up); [GeoMesh] converts.
 */
data class GeoModel(val textureWidth: Int, val textureHeight: Int, val bones: List<GeoBone>) {
    companion object {
        const val MAX_BONES = 256
        const val MAX_CUBES = 4096

        fun parse(json: String): GeoModel {
            val root = try {
                JsonParser.parseString(json).asJsonObject
            } catch (e: Exception) {
                throw GeoException("geometry is not a JSON object")
            }
            val geometry = root.getAsJsonArray("minecraft:geometry")?.firstOrNull()?.asJsonObject
                ?: throw GeoException("no minecraft:geometry entry")
            val description = geometry.getAsJsonObject("description") ?: JsonObject()
            val tw = description.get("texture_width")?.asInt ?: 64
            val th = description.get("texture_height")?.asInt ?: 64
            if (tw !in 1..4096 || th !in 1..4096) throw GeoException("texture size ${tw}x$th outside 1..4096")
            val raw = geometry.getAsJsonArray("bones") ?: JsonArray()
            if (raw.size() > MAX_BONES) throw GeoException("more than $MAX_BONES bones")
            var cubeCount = 0
            val bones = raw.map { element ->
                val o = element.asJsonObject
                val name = o.get("name")?.asString ?: throw GeoException("bone without name")
                val cubes = (o.getAsJsonArray("cubes") ?: JsonArray()).map { cube(it.asJsonObject) }
                cubeCount += cubes.size
                if (cubeCount > MAX_CUBES) throw GeoException("more than $MAX_CUBES cubes")
                GeoBone(name, o.get("parent")?.asString, vec(o.get("pivot"), Vec3d.ZERO), vec(o.get("rotation"), Vec3d.ZERO), cubes)
            }
            val names = bones.map { it.name }.toSet()
            if (names.size != bones.size) throw GeoException("duplicate bone names")
            bones.firstOrNull { it.parent != null && it.parent !in names }?.let { throw GeoException("bone ${it.name}: unknown parent ${it.parent}") }
            return GeoModel(tw, th, order(bones))
        }

        /** Parents before children; a cycle is an error. */
        private fun order(bones: List<GeoBone>): List<GeoBone> {
            val byName = bones.associateBy { it.name }
            val result = LinkedHashMap<String, GeoBone>()
            fun visit(bone: GeoBone, path: Set<String>) {
                if (bone.name in result) return
                if (bone.name in path) throw GeoException("bone parent cycle at ${bone.name}")
                bone.parent?.let { visit(byName.getValue(it), path + bone.name) }
                result[bone.name] = bone
            }
            bones.forEach { visit(it, emptySet()) }
            return result.values.toList()
        }

        private fun cube(o: JsonObject): GeoCube {
            val size = vec(o.get("size"), Vec3d.ZERO)
            val uvElement = o.get("uv")
            val uv = when {
                uvElement == null -> GeoUv.Box(0.0, 0.0, false)
                uvElement.isJsonArray -> GeoUv.Box(uvElement.asJsonArray[0].asDouble, uvElement.asJsonArray[1].asDouble, o.get("mirror")?.asBoolean ?: false)
                else -> GeoUv.Faces(uvElement.asJsonObject.entrySet().associate { (face, value) ->
                    val f = value.asJsonObject
                    val uvPos = f.getAsJsonArray("uv")
                    val uvSize = f.getAsJsonArray("uv_size")
                    face to doubleArrayOf(uvPos[0].asDouble, uvPos[1].asDouble, uvSize?.get(0)?.asDouble ?: 0.0, uvSize?.get(1)?.asDouble ?: 0.0)
                })
            }
            return GeoCube(vec(o.get("origin"), Vec3d.ZERO), size, o.get("inflate")?.asDouble ?: 0.0, uv,
                o.get("pivot")?.let { vec(it, Vec3d.ZERO) }, o.get("rotation")?.let { vec(it, Vec3d.ZERO) })
        }

        internal fun vec(element: JsonElement?, default: Vec3d): Vec3d {
            if (element == null || !element.isJsonArray) return default
            val a = element.asJsonArray
            if (a.size() != 3) throw GeoException("vector needs 3 numbers")
            return Vec3d(number(a[0]), number(a[1]), number(a[2]))
        }

        /** Numbers only: Molang expressions are not evaluated (0). */
        internal fun number(element: JsonElement): Double =
            if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) element.asDouble.takeIf { it.isFinite() } ?: 0.0 else 0.0
    }
}
