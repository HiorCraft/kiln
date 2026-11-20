package dev.kiln.example.host

import dev.kiln.server.runtime.KilnEmbedded
import dev.kiln.server.runtime.KilnSettings
import org.bukkit.plugin.java.JavaPlugin

/** What a host such as Surf-API does to run Kiln inside itself. */
class HostPlugin : JavaPlugin() {
    override fun onLoad() {
        KilnEmbedded.load(this)
    }

    override fun onEnable() {
        // the host's own settings; here simply Kiln's defaults
        if (!KilnEmbedded.enable(this, KilnSettings())) logger.warning("Kiln is not running")
    }

    override fun onDisable() {
        KilnEmbedded.disable()
    }
}
