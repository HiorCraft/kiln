package dev.kiln.example.server.demos.bestenliste

data class BoardState(
    val categories: List<LabelDto>,
    val periods: List<LabelDto>,
    val category: String,
    val period: String,
    /** Top [Leaderboard.TOP] rows, plus the player's own row (also when it is not in the top). */
    val rows: List<RowDto>,
    val players: Int,
)
