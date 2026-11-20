package dev.kiln.server.api.entity

import org.bukkit.entity.Player

/** Target selection of a custom entity: the nearest visible player within [range] that passes [filter]. */
public class EntityTargeting(public val range: Double, public val filter: (Player) -> Boolean)
