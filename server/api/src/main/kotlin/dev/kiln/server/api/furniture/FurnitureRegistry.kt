package dev.kiln.server.api.furniture

import net.kyori.adventure.key.Key
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin

/**
 * Furniture: a model (item display) plus hit boxes (interaction entities), placed in the world and stored with its
 * chunk. Solid hit boxes collide like boats for players and entities (mob path finding does not avoid them).
 * Without Kiln nothing of it remains visible; the data stays in the chunks.
 */
public interface FurnitureRegistry {
    /**
     * Registers furniture [key] (namespace = an extension of [plugin]; the model needs an item definition
     * `assets/<ns>/items/<path>.json` in it). Placed furniture of this type appears when it is registered and
     * disappears (but stays stored) when [plugin] is disabled.
     *
     * @throws IllegalArgumentException wrong namespace, missing model or invalid hit box.
     * @throws IllegalStateException [key] is already registered.
     */
    public fun register(plugin: Plugin, key: Key, init: FurnitureBuilder.() -> Unit): FurnitureType

    public fun get(key: Key): FurnitureType?

    /** The furniture [entity] (its model or one of its hit boxes) belongs to. */
    public fun at(entity: Entity): Furniture?
}
