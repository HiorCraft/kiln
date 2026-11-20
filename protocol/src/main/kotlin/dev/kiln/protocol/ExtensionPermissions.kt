package dev.kiln.protocol

/** Permissions an extension can request in `extension.json`; the client grants only these API parts. */
public object ExtensionPermissions {
    public const val WIDGETS: String = "widgets"
    public const val KEYBINDS: String = "keybinds"
    public const val HUD: String = "hud"
    public const val CHANNELS: String = "channels"
    public const val TOASTS: String = "toasts"

    /** Own Lua screens (`kiln.ui.screen` / `open` / `close`, `ui.sound`); since protocol v5. */
    public const val SCREENS: String = "screens"

    /** Read-only copy of the player's own inventory (`kiln.player().inventory()`); since protocol v5. */
    public const val INVENTORY: String = "inventory"

    /**
     * Resource-pack tree `assets/<id>/…` (models, textures, sounds, fonts, …; see [AssetRules]) and the Lua access to
     * own sounds (`kiln.sound.play`) and fonts (`g:text{font}`); since protocol v6. Implied by an `assets/` folder.
     */
    public const val RESOURCES: String = "resources"

    /** Every known permission, in canonical order. */
    public val ALL: List<String> = listOf(WIDGETS, KEYBINDS, HUD, CHANNELS, TOASTS, SCREENS, INVENTORY, RESOURCES)

    public fun isValid(permission: String): Boolean = permission in ALL
}
