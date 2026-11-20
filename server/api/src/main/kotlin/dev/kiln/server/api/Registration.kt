package dev.kiln.server.api

/** Handle of a registration (listener, keybind, ...) that can be undone. */
public fun interface Registration {
    public fun unregister()
}
