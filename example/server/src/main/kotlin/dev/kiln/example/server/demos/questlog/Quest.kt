package dev.kiln.example.server.demos.questlog

data class Quest(
    val id: String,
    val title: String,
    val category: String,
    val description: String,
    val objectives: List<Objective>,
    val rewards: List<Reward>,
    val xp: Int,
)
