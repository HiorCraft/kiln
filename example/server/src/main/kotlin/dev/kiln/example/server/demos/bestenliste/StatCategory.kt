package dev.kiln.example.server.demos.bestenliste

enum class StatCategory(val label: String, val unit: String) {
    BLOCKS("Abgebaute Blöcke", "Blöcke"),
    KILLS("Mob-Kills", "Kills"),
    PLAYTIME("Spielzeit", "Min."),
    DISTANCE("Laufstrecke", "m"),
    DEATHS("Tode", "Tode"),
}
