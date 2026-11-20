package dev.kiln.server.api.enchantment

import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.data.EnchantmentRegistryEntry
import io.papermc.paper.registry.event.RegistryEvents
import io.papermc.paper.registry.tag.TagKey
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.ItemType
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
                    builder.description(definition.description ?: Component.translatable("${key.namespace()}.enchantment.${key.value()}"))
                        .supportedItems(event.getOrCreateTag(definition.supportedItems ?: error("enchantment ${key.asString()}: supportedItems missing")))
                        .weight(definition.weight)
                        .maxLevel(definition.maxLevel)
                        .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(definition.minCost.first, definition.minCost.second))
                        .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(definition.maxCost.first, definition.maxCost.second))
                        .anvilCost(definition.anvilCost)
                        .activeSlots(definition.slots)
                    definition.primaryItems?.let { builder.primaryItems(event.getOrCreateTag(it)) }
                }
            }
        })
    }
}

/** Receiver of [KilnBootstrap.enchantments]. */
public class EnchantmentDefinitions {
    internal val definitions = LinkedHashMap<Key, EnchantmentDefinition>()

    public fun register(key: Key, init: EnchantmentDefinition.() -> Unit) {
        check(key !in definitions) { "enchantment ${key.asString()} defined twice" }
        definitions[key] = EnchantmentDefinition().apply(init)
    }
}

/** One custom enchantment (mirrors Paper's `EnchantmentRegistryEntry.Builder`). */
public class EnchantmentDefinition {
    /** Name shown on items; default `translatable("<ns>.enchantment.<path>")`. */
    public var description: Component? = null

    /** Items it can be applied to (anvil, commands); required. */
    public var supportedItems: TagKey<ItemType>? = null

    /** Items it is offered for in the enchanting table; `null` = [supportedItems]. */
    public var primaryItems: TagKey<ItemType>? = null

    public var maxLevel: Int = 1
    public var weight: Int = 1
    public var anvilCost: Int = 1

    /** Enchanting-table cost at level 1 and per further level (base, per level). */
    public var minCost: Pair<Int, Int> = 1 to 10
    public var maxCost: Pair<Int, Int> = 21 to 10

    internal var slots: List<EquipmentSlotGroup> = listOf(EquipmentSlotGroup.ANY)

    public fun slots(vararg groups: EquipmentSlotGroup) {
        slots = groups.toList()
    }
}
