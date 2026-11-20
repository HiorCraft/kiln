package dev.kiln.example.host

import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap

/**
 * Nothing to do, but Paper resolves `bootstrap` dependencies on "Kiln" (custom enchantments via KilnBootstrap) only
 * among plugins with a bootstrapper, so a host that provides Kiln needs one (Surf-API has its own).
 */
@Suppress("UnstableApiUsage")
class HostBootstrap : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {}
}
