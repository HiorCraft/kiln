package dev.kiln.client.entity.model

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
