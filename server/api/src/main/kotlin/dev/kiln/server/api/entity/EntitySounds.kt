package dev.kiln.server.api.entity

import net.kyori.adventure.key.Key

/** Sound events a custom entity plays by itself; `null` = silent. */
public class EntitySounds {
    /** Now and then while alive (about every 8 seconds on average). */
    public var ambient: Key? = null
    public var hurt: Key? = null
    public var death: Key? = null

    /** While walking, every [stepInterval] ticks. */
    public var step: Key? = null
    public var stepInterval: Int = 10
    public var volume: Float = 1f
    public var pitch: Float = 1f
}
