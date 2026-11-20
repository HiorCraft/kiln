package dev.kiln.example.server

import dev.kiln.example.server.demos.content.ContentDemo
import dev.kiln.server.api.enchantment.KilnBootstrap
import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap
import io.papermc.paper.registry.keys.tags.ItemTypeTagKeys
import net.kyori.adventure.text.Component
import org.bukkit.inventory.EquipmentSlotGroup

/** Registry content that must exist before the worlds load: the custom enchantment of the content demo. */
@Suppress("UnstableApiUsage")
class ExampleBootstrap : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {
        KilnBootstrap.enchantments(context) {
            register(ContentDemo.LIFESTEAL) {
                description = Component.translatable("kiln_content.enchantment.lifesteal")
                supportedItems = ItemTypeTagKeys.ENCHANTABLE_WEAPON
                maxLevel = 3
                weight = 2
                anvilCost = 4
                slots(EquipmentSlotGroup.MAINHAND)
            }
        }
    }
}
