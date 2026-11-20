package dev.kiln.example.server.demos.questlog

/** A quest that was just completed by an event: the service gives [Quest.rewards] and shows a toast. */
data class Completion(val quest: Quest)
