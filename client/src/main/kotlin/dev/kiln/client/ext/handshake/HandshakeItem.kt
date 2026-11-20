package dev.kiln.client.ext.handshake

import dev.kiln.client.ext.ExtensionPackage
import dev.kiln.protocol.message.ExtensionInfo

/** One announced extension of an [ExtensionHandshake]: its progress for the loading screen and its data in flight. */
class HandshakeItem(val info: ExtensionInfo) {
    var status: HandshakeItemStatus = HandshakeItemStatus.QUEUED
    var received: Int = 0
    var error: String? = null
    internal var zip: ByteArray? = null
    internal var pkg: ExtensionPackage? = null
    internal var assembler: ChunkAssembler? = null

    /** `true` while its bytes are still to come (queued or downloading). */
    internal val isDownloadPending: Boolean
        get() = status == HandshakeItemStatus.QUEUED || status == HandshakeItemStatus.DOWNLOADING

    internal fun fail(reason: String?) {
        status = HandshakeItemStatus.FAILED
        error = reason
    }
}
