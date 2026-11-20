package dev.kiln.example.server.shop

import java.util.Locale

object ShopFormat {
    /** `1234567` -> `1.234.567 Taler`. */
    fun money(amount: Long): String = "${number(amount)} Taler"

    fun number(amount: Long): String = String.format(Locale.GERMANY, "%,d", amount)

    /** Remaining time like `6 Std. 12 Min.`, `3 T. 4 Std.`, `abgelaufen`, `unbegrenzt`. */
    fun remaining(expiresAt: Long?, now: Long): String {
        if (expiresAt == null) return "unbegrenzt"
        val left = expiresAt - now
        if (left <= 0) return "abgelaufen"
        val minutes = (left + 59_999) / 60_000
        val days = minutes / (24 * 60)
        val hours = minutes / 60 % 24
        val mins = minutes % 60
        return when {
            days > 0 -> "$days T. $hours Std."
            hours > 0 -> "$hours Std. $mins Min."
            else -> "$mins Min."
        }
    }

    /** Approximate width of [text] in the vanilla font (GUI pixels, including the 1px spacing). */
    fun width(text: String): Int = text.sumOf { charWidth(it) }

    /** Cuts [text] to fit into [maxWidth] GUI pixels, appending `...` when shortened. */
    fun ellipsize(text: String, maxWidth: Int): String {
        if (width(text) <= maxWidth) return text
        val dots = "..."
        val budget = maxWidth - width(dots)
        if (budget <= 0) return ""
        val sb = StringBuilder()
        var used = 0
        for (c in text) {
            val w = charWidth(c)
            if (used + w > budget) break
            sb.append(c)
            used += w
        }
        return sb.toString().trimEnd() + dots
    }

    private fun charWidth(c: Char): Int = when (c) {
        'i', '!', '.', ',', ':', ';', '|', '\'' -> 2
        'l', '`' -> 3
        't', 'I', '[', ']', ' ', '"', '(', ')', '*', '{', '}' -> 4
        'f', 'k', '<', '>' -> 5
        '@', '~' -> 7
        else -> 6
    }
}
