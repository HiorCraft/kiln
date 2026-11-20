package dev.kiln.example.server.demos.profil

data class Settings(
    /** Shown instead of the player name; empty = player name. */
    val nickname: String = "",
    val nickColor: String = "white",
    val status: String = "",
    val chatFilter: String = "MILD",
    val volume: Int = 80,
    val notifications: Boolean = true,
    val privateMessages: Boolean = true,
    val compactChat: Boolean = false,
)
