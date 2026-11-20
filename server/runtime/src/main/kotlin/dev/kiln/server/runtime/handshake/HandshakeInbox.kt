package dev.kiln.server.runtime.handshake

import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.codec.MessageCodec
import dev.kiln.protocol.message.Message
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Messages of one configuring client, filled by the plugin-message listener, drained by the handshake. */
class HandshakeInbox(private val capacity: Int = 16) {
    private val queue = LinkedBlockingQueue<HandshakeIncoming>(capacity + 1)

    val createdAt: Long = System.currentTimeMillis()

    fun offerBytes(bytes: ByteArray) {
        val incoming = try {
            HandshakeIncoming.Message(MessageCodec.decodeServerbound(bytes))
        } catch (e: ProtocolException) {
            HandshakeIncoming.Malformed(e.message ?: "malformed")
        }
        offer(incoming)
    }

    /** Queues [incoming]; a full queue is replaced by a single [HandshakeIncoming.Overflow]. */
    fun offer(incoming: HandshakeIncoming) {
        synchronized(queue) {
            if (queue.size >= capacity) {
                if (queue.peek() != HandshakeIncoming.Overflow) {
                    queue.clear()
                    queue.offer(HandshakeIncoming.Overflow)
                }
                return
            }
            queue.offer(incoming)
        }
    }

    fun poll(timeoutMillis: Long): HandshakeIncoming? = queue.poll(timeoutMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
}
