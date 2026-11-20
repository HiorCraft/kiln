package dev.kiln.client.net

import dev.kiln.protocol.CHANNEL_NAMESPACE
import dev.kiln.protocol.CHANNEL_PATH
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * Raw `kiln:main` payload: the whole custom payload body is one protocol message (no length prefix), exactly
 * what Paper's plugin messaging sends and receives. Decoding into messages happens in the runtime, so malformed data
 * never fails inside the network pipeline.
 */
class KilnPayload(val data: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<KilnPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<KilnPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(CHANNEL_NAMESPACE, CHANNEL_PATH))

        @JvmField
        val CODEC: StreamCodec<ByteBuf, KilnPayload> = StreamCodec.of(
            { buf, payload -> buf.writeBytes(payload.data) },
            { buf ->
                val bytes = ByteArray(buf.readableBytes())
                buf.readBytes(bytes)
                KilnPayload(bytes)
            },
        )
    }
}
