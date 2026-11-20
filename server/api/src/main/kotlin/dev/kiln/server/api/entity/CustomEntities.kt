package dev.kiln.server.api.entity

import net.kyori.adventure.key.Key
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin

/**
 * Custom entities: a mob with its own bone model (Bedrock geometry + animations from an extension's resources,
 * drawn by the Kiln runtime), its own hit box and a behaviour written with [BehaviorBuilder] (goals, states,
 * timed sequences). On the server it is a mob without any vanilla behaviour; it is stored with its chunk and
 * comes back when the chunk loads (while its type is registered).
 */
public interface CustomEntities {
    /**
     * Registers entity type [key] (namespace = an extension of [plugin] that has
     * `assets/<ns>/kiln/entity/<path>.geo.json`). Removed when [plugin] is disabled.
     *
     * @throws IllegalArgumentException wrong namespace or missing model; @throws IllegalStateException duplicate key.
     */
    public fun register(plugin: Plugin, key: Key, init: CustomEntityBuilder.() -> Unit): CustomEntityType

    public fun get(key: Key): CustomEntityType?

    /** The custom entity [entity] is, if any. */
    public fun of(entity: Entity?): CustomEntity?
}
