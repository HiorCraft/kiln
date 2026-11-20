package dev.kiln.client.entity.model

/** Bone poses of one frame, and the ONCE animations that have ended. */
class MixResult(val poses: Map<String, BonePose>, val finished: List<ActiveAnimation>)
