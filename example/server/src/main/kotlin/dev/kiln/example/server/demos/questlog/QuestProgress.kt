package dev.kiln.example.server.demos.questlog

/** A player's progress in one quest: its status and the count per objective (while active). */
internal class QuestProgress(var status: QuestStatus = QuestStatus.AVAILABLE, var counts: IntArray = IntArray(0))
