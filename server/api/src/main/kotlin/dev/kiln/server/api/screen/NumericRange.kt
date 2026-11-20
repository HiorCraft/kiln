package dev.kiln.server.api.screen

/** Numeric constraint of a text field: only numbers in [min]..[max], whole numbers if [integer]. */
public data class NumericRange(val min: Double, val max: Double, val integer: Boolean = true) {
    init {
        require(min.isFinite() && max.isFinite() && min <= max) { "Invalid numeric range $min..$max" }
    }
}
