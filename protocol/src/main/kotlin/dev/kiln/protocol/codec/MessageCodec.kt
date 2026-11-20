package dev.kiln.protocol.codec

import dev.kiln.protocol.message.ClientboundMessage
import dev.kiln.protocol.message.CloseScreen
import dev.kiln.protocol.message.CreativeTabs
import dev.kiln.protocol.message.DeclareKeybinds
import dev.kiln.protocol.message.EntityAppearance
import dev.kiln.protocol.message.EntityTraits
import dev.kiln.protocol.message.EntityTraitsRemove
import dev.kiln.protocol.message.ExtensionChunk
import dev.kiln.protocol.message.ExtensionManifest
import dev.kiln.protocol.message.ExtensionMessage
import dev.kiln.protocol.message.ExtensionRequest
import dev.kiln.protocol.message.ExtensionsReady
import dev.kiln.protocol.message.Hello
import dev.kiln.protocol.message.KeyPressed
import dev.kiln.protocol.message.KeyReleased
import dev.kiln.protocol.message.ModalDismiss
import dev.kiln.protocol.message.OpenScreen
import dev.kiln.protocol.message.PlayAnimation
import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ProtocolLimits.MAX_CLIENTBOUND_BYTES
import dev.kiln.protocol.ProtocolLimits.MAX_SERVERBOUND_BYTES
import dev.kiln.protocol.message.ScreenClick
import dev.kiln.protocol.message.ScreenClosed
import dev.kiln.protocol.message.ScreenInput
import dev.kiln.protocol.message.ServerboundMessage
import dev.kiln.protocol.message.SetPostEffect
import dev.kiln.protocol.message.ShowToast
import dev.kiln.protocol.message.StopAnimation
import dev.kiln.protocol.message.UpdateScreen
import dev.kiln.protocol.message.Viewport
import dev.kiln.protocol.message.Welcome
import java.io.IOException

/**
 * Encodes/decodes complete channel payloads: `varint typeId` followed by the message's own codec. Type ids are per
 * direction; the codec of each message is its companion object. Every failure (malformed, truncated, oversized,
 * trailing bytes, invalid values) is reported as [ProtocolException]; nothing else escapes.
 */
public object MessageCodec {
    private val serverbound = CodecRegistry<ServerboundMessage, Codec<ServerboundMessage>>("serverbound message")
        .apply {
            // Hello keeps id 0 and its layout forever, so a version mismatch can always be detected
            add(0, Hello)
            add(1, ScreenClick)
            add(2, ScreenInput)
            add(3, ScreenClosed)
            add(4, KeyPressed)
            add(5, KeyReleased)
            add(6, ExtensionMessage.SERVERBOUND_CODEC)
            add(7, Viewport)
            add(8, ModalDismiss)
            add(9, ExtensionRequest)
            add(10, ExtensionsReady)
        }

    private val clientbound = CodecRegistry<ClientboundMessage, Codec<ClientboundMessage>>("clientbound message")
        .apply {
            add(0, Welcome)
            add(1, DeclareKeybinds)
            add(2, OpenScreen)
            add(3, UpdateScreen)
            add(4, CloseScreen)
            add(5, ExtensionMessage.CLIENTBOUND_CODEC)
            add(6, ShowToast)
            add(7, ExtensionManifest)
            add(8, ExtensionChunk)
            add(9, SetPostEffect)
            add(10, CreativeTabs)
            add(11, EntityTraits)
            add(12, EntityTraitsRemove)
            add(13, EntityAppearance)
            add(14, PlayAnimation)
            add(15, StopAnimation)
        }

    public fun encodeServerbound(message: ServerboundMessage): ByteArray =
        encode(MAX_SERVERBOUND_BYTES) { writeTyped(it, serverbound, message) }

    public fun decodeServerbound(bytes: ByteArray): ServerboundMessage =
        decode(bytes, MAX_SERVERBOUND_BYTES) { readTyped(it, serverbound) }

    public fun encodeClientbound(message: ClientboundMessage): ByteArray =
        encode(MAX_CLIENTBOUND_BYTES) { writeTyped(it, clientbound, message) }

    public fun decodeClientbound(bytes: ByteArray): ClientboundMessage =
        decode(bytes, MAX_CLIENTBOUND_BYTES) { readTyped(it, clientbound) }

    private fun <T : Any> writeTyped(writer: ProtocolWriter, registry: CodecRegistry<T, Codec<T>>, message: T) {
        val id = registry.idOf(message)
        writer.varInt(id)
        registry.codecFor(id).encode(writer, message)
    }

    private fun <T : Any> readTyped(reader: ProtocolReader, registry: CodecRegistry<T, Codec<T>>): T =
        registry.codecFor(reader.varInt()).decode(reader)

    private inline fun encode(maxBytes: Int, body: (ProtocolWriter) -> Unit): ByteArray {
        val writer = ProtocolWriter()
        try {
            body(writer)
        } catch (e: IOException) {
            throw ProtocolException("Encoding failed", e)
        }
        if (writer.size > maxBytes) throw ProtocolException("Message too large (${writer.size} > $maxBytes bytes)")
        return writer.toByteArray()
    }

    private inline fun <T> decode(bytes: ByteArray, maxBytes: Int, body: (ProtocolReader) -> T): T {
        if (bytes.size > maxBytes) throw ProtocolException("Message too large (${bytes.size} > $maxBytes bytes)")
        if (bytes.isEmpty()) throw ProtocolException("Empty message")
        val reader = ProtocolReader(bytes)
        val message = try {
            body(reader)
        } catch (e: ProtocolException) {
            throw e
        } catch (e: IOException) {
            throw ProtocolException("Malformed message", e)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("Invalid message: ${e.message}", e)
        } catch (e: IndexOutOfBoundsException) {
            throw ProtocolException("Malformed message", e)
        }
        reader.expectEnd()
        return message
    }
}
