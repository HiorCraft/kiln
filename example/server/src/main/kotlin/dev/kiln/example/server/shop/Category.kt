package dev.kiln.example.server.shop

enum class Category(val label: String) {
    ALL("Alle"),
    BLOCKS("Blöcke"),
    TOOLS("Werkzeuge"),
    ORES("Erze"),
    FOOD("Essen"),
    OTHER("Sonstiges"),
    ;

    companion object {
        /** Categories an offer can be listed in (everything but [ALL]). */
        val listable: List<Category> = entries.filter { it != ALL }

        private val TOOL_SUFFIXES = listOf(
            "_pickaxe", "_axe", "_shovel", "_hoe", "_sword", "_spear", "_mace",
        )
        private val TOOL_ITEMS = setOf(
            "shears", "flint_and_steel", "fishing_rod", "bow", "crossbow", "trident", "mace", "brush", "shield",
            "carrot_on_a_stick", "warped_fungus_on_a_stick", "compass", "recovery_compass", "clock", "spyglass",
            "lead", "bucket",
        )
        private val ORE_ITEMS = setOf(
            "coal", "charcoal", "raw_iron", "raw_gold", "raw_copper", "iron_ingot", "gold_ingot", "copper_ingot",
            "netherite_ingot", "netherite_scrap", "diamond", "emerald", "lapis_lazuli", "redstone", "quartz",
            "amethyst_shard", "ancient_debris", "iron_nugget", "gold_nugget",
        )

        /** Suggested category for an item, from its key path (`diamond_pickaxe`) and material properties. */
        fun suggest(keyPath: String, isBlock: Boolean, isEdible: Boolean): Category = when {
            isEdible -> FOOD
            keyPath.endsWith("_ore") || keyPath in ORE_ITEMS || keyPath.startsWith("raw_") && keyPath.endsWith("_block") -> ORES
            keyPath in TOOL_ITEMS || TOOL_SUFFIXES.any { keyPath.endsWith(it) } -> TOOLS
            isBlock -> BLOCKS
            else -> OTHER
        }
    }
}
