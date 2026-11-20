package dev.kiln.client.ext.consent

/** The player's answer to a [ConsentScreen]: exactly one callback runs, once; later answers are ignored. */
class ConsentDecision(private val onAccept: () -> Unit, private val onDeny: () -> Unit) {
    var answered: Boolean = false
        private set

    fun answer(accepted: Boolean) {
        if (answered) return
        answered = true
        if (accepted) onAccept() else onDeny()
    }
}
