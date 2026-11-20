package dev.kiln.server.api.enchantment

import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.data.EnchantmentRegistryEntry
import io.papermc.paper.registry.event.RegistryComposeEvent
import io.papermc.paper.registry.event.RegistryEvents
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.enchantments.Enchantment
import java.util.Collections

/**
 * Registry content that must exist before the server loads its data (custom enchantments are real registry
 * entries, synchronised to every client). Call it from your plugin's `PluginBootstrap`:
 *
 * ```kotlin
 * override fun bootstrap(context: BootstrapContext) {
 *     KilnBootstrap.enchantments(context) {
 *         register(Key.key("my_ext", "lifesteal")) {
 *             description = Component.translatable("my_ext.enchantment.lifesteal")
 *             supportedItems = ItemTypeTagKeys.ENCHANTABLE_WEAPON
 *             maxLevel = 3
 *         }
 *     }
 * }
 * ```
 * The namespace should be the id of an extension of your plugin (texts in its `assets/<ns>/lang/`); Kiln warns
 * at startup otherwise. Logic goes into [CustomEnchantments] hooks (`Kiln.enchantments`) in `onEnable`.
 */
public object KilnBootstrap {
    private val keys: MutableSet<Key> = Collections.synchronizedSet(LinkedHashSet())

    /** Every enchantment registered through [enchantments] so far. */
    public val registered: Set<Key> get() = synchronized(keys) { keys.toSet() }

    public fun enchantments(context: BootstrapContext, block: EnchantmentDefinitions.() -> Unit) {
        val definitions = EnchantmentDefinitions().apply(block).definitions
        keys += definitions.keys
        context.lifecycleManager.registerEventHandler(RegistryEvents.ENCHANTMENT.compose().newHandler { event ->
            for ((key, definition) in definitions) {
                event.registry().register(TypedKey.create(RegistryKey.ENCHANTMENT, key)) { builder ->
                    configure(builder, event, key, definition)
                }
            }
        })
    }

    private fun configure(
        builder: EnchantmentRegistryEntry.Builder,
        event: RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder>,
        key: Key,
        definition: EnchantmentDefinition,
    ) {
        val supportedItems = definition.supportedItems ?: error("enchantment ${key.asString()}: supportedItems missing")
        builder.description(definition.description ?: Component.translatable("${key.namespace()}.enchantment.${key.value()}"))
            .supportedItems(event.getOrCreateTag(supportedItems))
            .weight(definition.weight)
            .maxLevel(definition.maxLevel)
            .minimumCost(cost(definition.minCost))
            .maximumCost(cost(definition.maxCost))
            .anvilCost(definition.anvilCost)
            .activeSlots(definition.slots)
        definition.primaryItems?.let { builder.primaryItems(event.getOrCreateTag(it)) }
    }

    /** `(base, per level)` as Paper's cost type. */
    private fun cost(baseAndPerLevel: Pair<Int, Int>): EnchantmentRegistryEntry.EnchantmentCost =
        EnchantmentRegistryEntry.EnchantmentCost.of(baseAndPerLevel.first, baseAndPerLevel.second)
}
