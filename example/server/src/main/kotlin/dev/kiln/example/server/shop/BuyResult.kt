package dev.kiln.example.server.shop

sealed interface BuyResult<out I> {
    data class Success<I>(val offer: Offer<I>, val amount: Int, val total: Long, val remaining: Int) : BuyResult<I>
    data class Failure(val error: BuyError) : BuyResult<Nothing>
}
