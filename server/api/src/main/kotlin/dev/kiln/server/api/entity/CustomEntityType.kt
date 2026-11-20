package dev.kiln.server.api.entity

import dev.kiln.server.api.Registration
import net.kyori.adventure.key.Key
import org.bukkit.Location
import org.bukkit.plugin.Plugin

public interface CustomEntityType : Registration {
    public val key: Key
    public val plugin: Plugin

    public fun spawn(location: Location): CustomEntity
}
