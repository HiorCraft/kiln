package dev.kiln.server.runtime

import dev.kiln.server.api.Kiln
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.nio.file.Path

/**
 * Kiln inside another plugin (`kiln-server-embedded`, shaded by the host without relocation; the host provides the
 * Kotlin runtime). The host calls [load] from `onLoad`, [enable] from `onEnable` and [disable] from `onDisable`, and
 * declares `provides: [Kiln]` in its `paper-plugin.yml`, so plugins that depend on "Kiln" load against it unchanged.
 *
 * Kiln then runs under the host: [Kiln.get] works as usual, the plugin channel `kiln:main`, listeners and tasks belong
 * to the host, and log lines carry a `[Kiln]` prefix. If another plugin already provides Kiln (the standalone plugin),
 * the embedded copy stays off.
 */
object KilnEmbedded {
    private var runtime: KilnRuntime? = null

    /** `true` while the embedded Kiln is running. */
    @JvmStatic
    val isActive: Boolean get() = runtime != null

    /** Registers the Kiln service under [host] (call from the host's `onLoad`); `false` if another plugin provides it. */
    @JvmStatic
    fun load(host: Plugin): Boolean {
        runtime?.let { return it.host === host }
        val other = Bukkit.getServicesManager().getRegistration(Kiln::class.java)
        if (other != null && other.plugin !== host) {
            host.logger.warning("[Kiln] ${other.plugin.name} already provides Kiln; the embedded copy stays off")
            return false
        }
        runtime = KilnRuntime(host, PrefixedLogger(host.logger))
        return true
    }

    /**
     * Starts Kiln (call from the host's `onEnable`; calls [load] if the host did not). Lua extension folders are read
     * from [extensionsDir]. Returns `false` (and logs why) if Kiln cannot run here: another provider, a different
     * Minecraft version than Kiln's server internals were built for, or a too old Kotlin of the host.
     */
    @JvmStatic
    @JvmOverloads
    fun enable(
        host: Plugin,
        settings: KilnSettings = KilnSettings(),
        extensionsDir: Path = host.dataFolder.toPath().resolve("kiln").resolve("extensions"),
    ): Boolean {
        if (!load(host)) return false
        val runtime = runtime!!
        val problems = RuntimeChecks.problems(BuildInfo.load(), Bukkit.getMinecraftVersion(), KotlinVersion.CURRENT)
        if (problems.isNotEmpty()) {
            problems.forEach(runtime.logger::severe)
            disable()
            return false
        }
        runtime.enable(settings, extensionsDir)
        return true
    }

    /** Stops Kiln and removes everything it registered under the host (call from the host's `onDisable`). */
    @JvmStatic
    fun disable() {
        runtime?.disable()
        runtime = null
    }
}
