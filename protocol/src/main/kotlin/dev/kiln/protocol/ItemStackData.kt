package dev.kiln.protocol

import dev.kiln.protocol.ProtocolLimits.MAX_ITEM_COUNT
import dev.kiln.protocol.ProtocolLimits.MAX_ITEM_NBT_BYTES
import dev.kiln.protocol.codec.ByteArrayCodec
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.RangedVarIntCodec

/**
 * An item as shown in [ItemDisplay], [ItemSlot] and [ShowToast].
 *
 * - [itemId]: item type; used by the client when [nbt] is absent or cannot be decoded (fallback rendering).
 * - [count]: display count, 1..[ProtocolLimits.MAX_ITEM_COUNT]. Independent of the stack inside [nbt] (which the
 *   server serializes with amount 1, because the vanilla codec only allows 1..99); counts > 1 are drawn as the
 *   slot count text, counts > 99 abbreviated by the client.
 * - [nbt]: the full stack (all data components: name, lore, enchantment glint, item model, custom model data, ...)
 *   exactly as produced by Paper's `ItemStack.serializeAsBytes()`: gzip-compressed NBT of `ItemStack.CODEC` with a
 *   root `DataVersion` int. The client decodes it with `NbtIo.readCompressed` (quota
 *   [ProtocolLimits.MAX_ITEM_NBT_UNCOMPRESSED]), runs the DataFixer from `DataVersion` if it differs and parses with
 *   `ItemStack.CODEC` using the connection's registry access. Must start with the gzip magic `1f 8b`.
 */
public class ItemStackData(
    public val itemId: ResourceId,
    public val count: Int = 1,
    public val nbt: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ItemStackData && itemId == other.itemId && count == other.count &&
            nbt.contentEquals(other.nbt))

    override fun hashCode(): Int = (31 * itemId.hashCode() + count) * 31 + nbt.contentHashCode()

    override fun toString(): String = "ItemStackData($itemId x$count, nbt=${nbt?.size ?: 0} bytes)"

    public fun withCount(count: Int): ItemStackData = ItemStackData(itemId, count, nbt)

    /** Wire codec: item id, display count and the optional gzip NBT of the full stack. */
    public companion object : Codec<ItemStackData> {
        /** First two bytes of every gzip stream. */
        private const val GZIP_MAGIC_0 = 0x1F.toByte()
        private const val GZIP_MAGIC_1 = 0x8B.toByte()

        private val itemId = ResourceId.codec("item id")
        private val count = RangedVarIntCodec(1, MAX_ITEM_COUNT, "item count")
        private val nbt = ByteArrayCodec(MAX_ITEM_NBT_BYTES, "item nbt").validated(::checkGzip).nullable()

        override fun encode(writer: ProtocolWriter, value: ItemStackData) {
            itemId.encode(writer, value.itemId)
            count.encode(writer, value.count)
            nbt.encode(writer, value.nbt)
        }

        override fun decode(reader: ProtocolReader): ItemStackData =
            ItemStackData(itemId.decode(reader), count.decode(reader), nbt.decode(reader))

        private fun checkGzip(nbt: ByteArray) {
            if (nbt.size < 2 || nbt[0] != GZIP_MAGIC_0 || nbt[1] != GZIP_MAGIC_1) {
                throw ProtocolException("Item nbt is not gzip data (expected Paper ItemStack.serializeAsBytes())")
            }
        }
    }
}
