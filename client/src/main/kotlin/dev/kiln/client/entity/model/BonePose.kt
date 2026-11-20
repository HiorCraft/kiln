package dev.kiln.client.entity.model

/** Animated offsets of one bone (Bedrock units: degrees, pixels, factors), added to the rest pose. */
data class BonePose(val rotation: Vec3d = Vec3d.ZERO, val position: Vec3d = Vec3d.ZERO, val scale: Vec3d = Vec3d.ONE)
