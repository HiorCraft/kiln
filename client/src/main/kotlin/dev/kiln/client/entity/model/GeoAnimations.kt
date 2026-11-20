package dev.kiln.client.entity.model

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** The animations of one model (`*.animation.json`), keyed by short name (`animation.golem.walk` -> `walk`). */
class GeoAnimations(val animations: Map<String, GeoAnimation>) {
    companion object {
        const val MAX_ANIMATIONS = 128
        val EMPTY = GeoAnimations(emptyMap())

        fun parse(json: String): GeoAnimations {
            val root = try {
                JsonParser.parseString(json).asJsonObject
            } catch (e: Exception) {
                throw GeoException("animation file is not a JSON object")
            }
            val all = root.getAsJsonObject("animations") ?: return EMPTY
            if (all.size() > MAX_ANIMATIONS) throw GeoException("more than $MAX_ANIMATIONS animations")
            val result = LinkedHashMap<String, GeoAnimation>()
            for ((fullName, element) in all.entrySet()) {
                val o = element.asJsonObject
                val loop = when (val l = o.get("loop")) {
                    null -> GeoLoop.ONCE
                    else -> if (l.isJsonPrimitive && l.asJsonPrimitive.isString && l.asString == "hold_on_last_frame") GeoLoop.HOLD
                    else if (l.isJsonPrimitive && l.asJsonPrimitive.isBoolean && l.asBoolean) GeoLoop.LOOP else GeoLoop.ONCE
                }
                val bones = (o.getAsJsonObject("bones") ?: JsonObject()).entrySet().associate { (bone, channels) ->
                    val c = channels.asJsonObject
                    bone to BoneAnimation(channel(c.get("rotation")), channel(c.get("position")), channel(c.get("scale")))
                }
                val lastKey = bones.values.flatMap { listOfNotNull(it.rotation, it.position, it.scale) }.maxOfOrNull { it.keyframes.last().time } ?: 0.0
                val length = o.get("animation_length")?.let { GeoModel.number(it) }?.takeIf { it.isFinite() && it >= 0 } ?: lastKey
                result[fullName.substringAfterLast('.')] = GeoAnimation(loop, length, bones)
            }
            return GeoAnimations(result)
        }

        private fun channel(element: JsonElement?): Channel? {
            if (element == null) return null
            if (element.isJsonArray) return Channel(listOf(Keyframe(0.0, GeoModel.vec(element, Vec3d.ZERO), Lerp.LINEAR)))
            if (element.isJsonPrimitive) {
                val v = GeoModel.number(element)
                return Channel(listOf(Keyframe(0.0, Vec3d(v, v, v), Lerp.LINEAR)))
            }
            val frames = element.asJsonObject.entrySet().mapNotNull { (time, value) ->
                val t = time.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
                when {
                    value.isJsonArray -> Keyframe(t, GeoModel.vec(value, Vec3d.ZERO), Lerp.LINEAR)
                    value.isJsonObject -> {
                        val v = value.asJsonObject
                        val lerp = when (v.get("lerp_mode")?.asString) {
                            "catmullrom" -> Lerp.CATMULLROM
                            "step" -> Lerp.STEP
                            else -> Lerp.LINEAR
                        }
                        Keyframe(t, GeoModel.vec(v.get("post") ?: v.get("pre"), Vec3d.ZERO), lerp)
                    }
                    else -> null
                }
            }.sortedBy { it.time }
            return if (frames.isEmpty()) null else Channel(frames)
        }
    }
}
