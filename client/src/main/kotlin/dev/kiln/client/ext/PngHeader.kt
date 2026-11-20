package dev.kiln.client.ext

/** Width/height from a PNG's IHDR chunk, checked before decoding (no decompression bombs). */
object PngHeader {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    /** Signature, IHDR length and type, width, height. */
    private const val HEADER_BYTES = 24
    private const val CHUNK_TYPE_OFFSET = 12
    private const val WIDTH_OFFSET = 16
    private const val HEIGHT_OFFSET = 20

    fun size(bytes: ByteArray): IntArray? {
        if (bytes.size < HEADER_BYTES) return null
        for (i in SIGNATURE.indices) if (bytes[i] != SIGNATURE[i]) return null
        if (String(bytes, CHUNK_TYPE_OFFSET, 4, Charsets.US_ASCII) != "IHDR") return null
        return intArrayOf(bytes.intAt(WIDTH_OFFSET), bytes.intAt(HEIGHT_OFFSET))
    }

    /** Big-endian 32-bit integer at [offset]. */
    private fun ByteArray.intAt(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or (this[offset + 3].toInt() and 0xFF)
}
