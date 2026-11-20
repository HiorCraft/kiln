package dev.kiln.client.screen

import dev.kiln.protocol.element.NumericRange

/** Input rules of a numeric [dev.kiln.protocol.element.TextField]. */
object NumericInput {
    private val INTEGER = Regex("-?\\d*")
    private val DECIMAL = Regex("-?\\d*(\\.\\d*)?")

    /**
     * `true` if [text] may be the content of the field while typing: the empty string, a lone `-` (only if negative
     * numbers are allowed) or a prefix of a valid number. Values outside the range are allowed while typing but not
     * reported (see [reportable]).
     */
    fun isPartial(text: String, range: NumericRange): Boolean {
        if (text.isEmpty()) return true
        if (text.startsWith("-") && range.min >= 0.0) return false
        return (if (range.integer) INTEGER else DECIMAL).matches(text)
    }

    /** `true` if [text] may be sent to the server: empty or a number the range accepts. */
    fun reportable(text: String, range: NumericRange): Boolean = text.isEmpty() || range.accepts(text)
}
