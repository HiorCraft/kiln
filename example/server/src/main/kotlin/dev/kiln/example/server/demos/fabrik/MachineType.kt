package dev.kiln.example.server.demos.fabrik

enum class MachineType(val label: String, val icon: String, val recipes: List<Recipe>) {
    SMELTER(
        "Hochofen", "minecraft:blast_furnace",
        listOf(
            Recipe("iron", "Eisenbarren", "minecraft:raw_iron", "minecraft:iron_ingot", 4, 30),
            Recipe("gold", "Goldbarren", "minecraft:raw_gold", "minecraft:gold_ingot", 6, 40),
            Recipe("glass", "Glas", "minecraft:sand", "minecraft:glass", 2, 20),
        ),
    ),
    CRUSHER(
        "Zerkleinerer", "minecraft:grindstone",
        listOf(
            Recipe("gravel", "Kies", "minecraft:cobblestone", "minecraft:gravel", 3, 25),
            Recipe("sand", "Sand", "minecraft:gravel", "minecraft:sand", 3, 25),
        ),
    ),
    ASSEMBLER(
        "Montageband", "minecraft:crafter",
        listOf(
            Recipe("rail", "Schienen", "minecraft:iron_ingot", "minecraft:rail", 5, 35),
            Recipe("piston", "Kolben", "minecraft:iron_ingot", "minecraft:piston", 8, 45),
            Recipe("hopper", "Trichter", "minecraft:iron_ingot", "minecraft:hopper", 10, 50),
        ),
    ),
    SAWMILL(
        "Sägewerk", "minecraft:stonecutter",
        listOf(
            Recipe("planks", "Bretter", "minecraft:oak_log", "minecraft:oak_planks", 1, 10),
            Recipe("sticks", "Stöcke", "minecraft:oak_planks", "minecraft:stick", 1, 8),
        ),
    ),
    ;

    fun recipe(id: String): Recipe? = recipes.find { it.id == id }
}
