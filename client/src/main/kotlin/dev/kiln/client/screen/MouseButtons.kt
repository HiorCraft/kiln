package dev.kiln.client.screen

/**
 * Minecraft 26.3 numbers mouse buttons like SDL (1 = left, 2 = middle, 3 = right, 4.. = extra buttons), the
 * protocol and the client API use GLFW numbers (0 = left, 1 = right, 2 = middle, 3.. = extra buttons).
 */
object MouseButtons {
    const val MAX_PROTOCOL_BUTTON: Int = 7

    /** Converts a 26.3 (SDL) button number to the protocol number, or `-1` if it cannot be represented. */
    fun toProtocol(button: Int): Int = when (button) {
        1 -> 0
        2 -> 2
        3 -> 1
        in 4..(MAX_PROTOCOL_BUTTON + 1) -> button - 1
        else -> -1
    }
}
