package dev.kiln.client.keybind

/** Owner of an extension keybind; receives its press/release edges. */
fun interface KeybindHandler {
    fun onKey(id: String, pressed: Boolean)
}
