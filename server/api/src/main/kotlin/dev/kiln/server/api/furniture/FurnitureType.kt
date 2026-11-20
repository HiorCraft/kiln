package dev.kiln.server.api.furniture

import dev.kiln.server.api.Registration
import net.kyori.adventure.key.Key
import org.bukkit.Location
import org.bukkit.plugin.Plugin

public interface FurnitureType : Registration {
    public val key: Key
    public val plugin: Plugin

    /**
     * Places this furniture with its origin (bottom centre) at [location]; [yaw] is snapped to 90° when the type has
     * hit boxes. Does not check for blocks in the way.
     */
    public fun place(location: Location, yaw: Float = location.yaw): Furniture
}
