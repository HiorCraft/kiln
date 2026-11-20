package dev.kiln.example.server.shop

/** Validated listing form: either all [errors] per field (`amount`, `price`, `item`, `category`) or the parsed values. */
data class ListingValidation(val errors: Map<String, String>, val amount: Int?, val price: Long?) {
    val ok: Boolean get() = errors.isEmpty()
}
