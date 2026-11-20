package dev.kiln.protocol.codec

import dev.kiln.protocol.ProtocolException

/** Throws a [ProtocolException] described by [problem] for the first value that occurs twice in [values]. */
internal inline fun <T> requireDistinct(values: Iterable<T>, problem: (T) -> String) {
    val seen = HashSet<T>()
    for (value in values) if (!seen.add(value)) throw ProtocolException(problem(value))
}
