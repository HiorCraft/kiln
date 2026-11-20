package dev.kiln.server.runtime.furniture

/**
 * One hit box of a furniture type: an `Interaction` entity, square base [width], [height], its feet at [offset]
 * from the furniture origin in the "facing south" frame (+z = front). [solid] boxes collide like boats.
 */
data class Hitbox(val width: Double, val height: Double, val offset: Vec, val solid: Boolean)
