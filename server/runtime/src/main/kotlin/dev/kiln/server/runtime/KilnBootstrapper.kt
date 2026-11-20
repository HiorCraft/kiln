package dev.kiln.server.runtime

import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap

/**
 * Kiln has nothing to register at bootstrap itself, but Paper resolves a plugin's `bootstrap` dependencies only
 * among plugins with a bootstrapper. Plugins that call `KilnBootstrap` (custom enchantments) depend on Kiln there.
 */
@Suppress("UnstableApiUsage")
class KilnBootstrapper : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {}
}
