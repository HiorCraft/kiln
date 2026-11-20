package dev.kiln.server.runtime.entity

import dev.kiln.server.api.entity.CustomEntity
import dev.kiln.server.api.entity.CustomEntityBuilder
import dev.kiln.server.api.entity.CustomEntityType
import net.kyori.adventure.key.Key
import org.bukkit.Location
import org.bukkit.plugin.Plugin

class CustomEntityTypeImpl(
    private val manager: CustomEntityManager,
    override val plugin: Plugin,
    override val key: Key,
    val model: Key,
    val definition: CustomEntityBuilder,
) : CustomEntityType {
    override fun spawn(location: Location): CustomEntity = manager.spawn(this, location)

    override fun unregister() = manager.unregister(this)
}
