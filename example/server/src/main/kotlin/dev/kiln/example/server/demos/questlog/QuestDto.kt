package dev.kiln.example.server.demos.questlog

data class QuestDto(
    val id: String,
    val title: String,
    val category: String,
    val description: String,
    val status: String,
    val objectives: List<ObjectiveDto>,
    val rewards: List<RewardDto>,
    val xp: Int,
)
