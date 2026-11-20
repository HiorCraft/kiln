package dev.kiln.client.keybind

/** Default keys of server keybinds that are already in use. */
object KeyConflicts {
    private const val UNBOUND = "key.keyboard.unknown"

    /** `true` if [defaultKey] (a key name like `key.keyboard.w`) is bound to one of the [taken] keybinds already. */
    fun startsUnbound(defaultKey: String, taken: Collection<String>): Boolean =
        defaultKey != UNBOUND && defaultKey in taken
}
