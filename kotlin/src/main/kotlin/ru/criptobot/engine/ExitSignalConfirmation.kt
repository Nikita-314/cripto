package ru.criptobot.engine

/**
 * Requires the same strategy exit on two different completed bars.
 * Risk exits (stop-loss and take-profit) do not pass through this gate.
 */
class ExitSignalConfirmation {
    private data class Candidate(val action: String, val barKey: String)

    private val candidates = mutableMapOf<String, Candidate>()

    @Synchronized
    fun confirm(symbol: String, action: String, barKey: String): Boolean {
        val previous = candidates[symbol]
        if (previous?.action == action && previous.barKey != barKey) {
            candidates.remove(symbol)
            return true
        }
        candidates[symbol] = Candidate(action, barKey)
        return false
    }

    @Synchronized
    fun clear(symbol: String) {
        candidates.remove(symbol)
    }
}
