package dev.kiln.server.api.extension

import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the files of a Lua extension package come from. The package layout is
 * `extension.json`, Lua files, PNG textures below `textures/`, `lang/<locale>.json` (see `docs/lua-extensions.md`).
 */
public sealed interface ExtensionSource {
    /** A directory containing `extension.json` directly. */
    public data class Directory(val path: Path) : ExtensionSource

    /** A zip with `extension.json` at its root or inside a single top-level `<id>/` folder. */
    public data class Zip(val path: Path) : ExtensionSource

    /**
     * A folder inside [plugin]'s jar, e.g. `extensions/my_ext` for `src/main/resources/extensions/my_ext/`.
     */
    public data class PluginResource(val plugin: Plugin, val path: String) : ExtensionSource

    public companion object {
        /** [Directory] for directories, otherwise [Zip]. */
        @JvmStatic
        public fun of(path: Path): ExtensionSource = if (Files.isDirectory(path)) Directory(path) else Zip(path)
    }
}
