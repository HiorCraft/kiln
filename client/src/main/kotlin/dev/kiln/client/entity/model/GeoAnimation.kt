package dev.kiln.client.entity.model

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class GeoLoop { ONCE, LOOP, HOLD }

enum class Lerp { LINEAR, CATMULLROM, STEP }

data class Keyframe(val time: Double, val value: Vec3d, val lerp: Lerp)

/** One channel (rotation, position or scale) of a bone: keyframes sorted by time. */
class Channel(val keyframes: List<Keyframe>) {
    fun sample(time: Double): Vec3d {
        if (keyframes.size == 1 || time.isNaN() || time <= keyframes.first().time) return keyframes.first().value
        if (time >= keyframes.last().time) return keyframes.last().value
        val i = keyframes.indexOfLast { it.time <= time }
        val a = keyframes[i]
        val b = keyframes[i + 1]
        val t = (time - a.time) / (b.time - a.time)
        return when (b.lerp) {
            Lerp.STEP -> a.value
            Lerp.LINEAR -> lerp(a.value, b.value, if (a.lerp == Lerp.STEP) 0.0 else t)
            Lerp.CATMULLROM -> {
                val p0 = keyframes.getOrElse(i - 1) { a }.value
                val p3 = keyframes.getOrElse(i + 2) { b }.value
                Vec3d(catmull(p0.x, a.value.x, b.value.x, p3.x, t), catmull(p0.y, a.value.y, b.value.y, p3.y, t), catmull(p0.z, a.value.z, b.value.z, p3.z, t))
            }
        }.let { if (a.lerp == Lerp.STEP) a.value else it }
    }

    private fun lerp(a: Vec3d, b: Vec3d, t: Double) = Vec3d(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)

    private fun catmull(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
    }
}

class BoneAnimation(val rotation: Channel?, val position: Channel?, val scale: Channel?)

class GeoAnimation(val loop: GeoLoop, val length: Double, val bones: Map<String, BoneAnimation>)

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
