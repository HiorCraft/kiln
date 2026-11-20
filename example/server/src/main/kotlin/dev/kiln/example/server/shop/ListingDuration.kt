package dev.kiln.example.server.shop

enum class ListingDuration(val label: String, val millis: Long) {
    H1("1 Stunde", 3_600_000L),
    H12("12 Stunden", 12 * 3_600_000L),
    H24("24 Stunden", 24 * 3_600_000L),
    D7("7 Tage", 7 * 24 * 3_600_000L),
}
