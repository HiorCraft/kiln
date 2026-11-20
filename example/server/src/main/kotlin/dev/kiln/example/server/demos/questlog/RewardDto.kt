package dev.kiln.example.server.demos.questlog

data class RewardDto(
    /** `ExtensionItems.encode` of the reward item; `null` if it could not be encoded (client shows the id). */
    val item: String?,
    val key: String,
    val count: Int,
)
