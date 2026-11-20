package dev.kiln.server.runtime.entity

/** Names that are stored or sent: behaviour states (chunk data) and animations (protocol). */
object EntityNames {
    private val STATE = Regex("[A-Za-z0-9_.-]{1,64}")
    private val ANIMATION = Regex("[a-z0-9_.]{1,64}")

    fun isState(name: String): Boolean = STATE.matches(name)

    fun state(name: String): String {
        require(isState(name)) { "State name '$name' must match [A-Za-z0-9_.-]{1,64}" }
        return name
    }

    fun animation(name: String): String {
        require(ANIMATION.matches(name)) { "Animation name '$name' must match [a-z0-9_.]{1,64}" }
        return name
    }

    fun speed(speed: Float): Float = if (speed.isNaN()) 0f else speed.coerceIn(0f, 8f)
}
