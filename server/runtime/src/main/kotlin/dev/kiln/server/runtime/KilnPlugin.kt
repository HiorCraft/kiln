package dev.kiln.server.runtime

import dev.kiln.server.api.Kiln
import org.bukkit.plugin.java.JavaPlugin

/**
 * The standalone Kiln plugin (`kiln-server`): a thin shell around [KilnRuntime] with its own `config.yml` and
 * `plugins/Kiln/extensions`. Plugins that shade Kiln in use [KilnEmbedded] instead.
 */
class KilnPlugin : JavaPlugin() {
    private var runtime: KilnRuntime? = null

    override fun onLoad() {
        val other = server.servicesManager.getRegistration(Kiln::class.java)
        if (other != null && other.plugin !== this) {
            logger.warning("${other.plugin.name} already provides Kiln (embedded); this plugin stays off")
            return
        }
        runtime = KilnRuntime(this, logger)
    }

    override fun onEnable() {
        val runtime = runtime ?: return
        val problems = RuntimeChecks.problems(BuildInfo.load(), server.minecraftVersion, KotlinVersion.CURRENT)
        if (problems.isNotEmpty()) {
            problems.forEach(logger::severe)
            server.pluginManager.disablePlugin(this)
            return
        }
        saveDefaultConfig()
        runtime.enable(KilnSettings.from(config), dataFolder.toPath().resolve("extensions"))
    }

    override fun onDisable() {
        runtime?.disable()
        runtime = null
    }
}
