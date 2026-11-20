package dev.kiln.server.runtime.furniture

import dev.kiln.server.api.furniture.Furniture
import dev.kiln.server.api.furniture.FurnitureBuilder
import dev.kiln.server.api.furniture.FurnitureType
import dev.kiln.server.runtime.item.CustomItemCatalog
import net.kyori.adventure.key.Key
import org.bukkit.Location
import org.bukkit.plugin.Plugin

class FurnitureTypeImpl(
    private val manager: FurnitureManager,
    override val plugin: Plugin,
    override val key: Key,
    val definition: FurnitureBuilder,
) : FurnitureType {
    val model: Key = CustomItemCatalog.modelOf(key, definition.model)

    override fun place(location: Location, yaw: Float): Furniture = manager.place(this, location, yaw)

    override fun unregister() = manager.unregister(this)

    override fun toString(): String = "FurnitureType(${key.asString()})"
}
