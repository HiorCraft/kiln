package dev.kiln.client.screen

import com.mojang.serialization.Dynamic
import dev.kiln.protocol.ItemStackData
import dev.kiln.protocol.ProtocolLimits
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.resources.Identifier
import net.minecraft.util.datafix.DataFixers
import net.minecraft.util.datafix.fixes.References
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream

/**
 * Decodes [ItemStackData] into vanilla stacks. Never throws: undecodable data yields a barrier (marked [DisplayItem.failed]).
 *
 * `nbt` is Paper's `ItemStack.serializeAsBytes()`: gzip NBT of `ItemStack.CODEC` with a root `DataVersion`. It is read
 * with an [NbtAccounter] quota, upgraded with the DataFixer when it comes from an older version and parsed with the
 * registry ops of the current connection.
 */
object ItemStacks {
    private val logger = LoggerFactory.getLogger("kiln")

    fun decode(data: ItemStackData, registries: HolderLookup.Provider? = currentRegistries()): DisplayItem {
        val count = data.count.coerceAtLeast(1)
        val nbt = data.nbt
        if (nbt != null) {
            val stack = if (registries == null) null else decodeNbt(nbt, registries)
            if (stack != null && !stack.isEmpty) return DisplayItem(stack.copyWithCount(1), count, false)
            if (registries != null) logger.debug("Could not decode item NBT for {} ({} bytes)", data.itemId, nbt.size)
        }
        val item = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(data.itemId.toString())).orElse(null)
        // an nbt that failed to decode means the server meant something we cannot show faithfully -> barrier
        if (item == null || item == Items.AIR || (nbt != null && registries != null)) {
            return DisplayItem(ItemStack(Items.BARRIER), count, true)
        }
        return DisplayItem(ItemStack(item), count, false)
    }

    /** Decodes Paper `serializeAsBytes()` data (Lua `kiln.items.decode`); `null` if invalid or empty. */
    fun decodeBytes(bytes: ByteArray, registries: HolderLookup.Provider? = currentRegistries()): ItemStack? {
        if (registries == null || bytes.size > ProtocolLimits.MAX_ITEM_NBT_BYTES) return null
        return decodeNbt(bytes, registries)?.takeIf { !it.isEmpty }?.copyWithCount(1)
    }

    private fun decodeNbt(bytes: ByteArray, registries: HolderLookup.Provider): ItemStack? {
        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return null
        return try {
            val tag = NbtIo.readCompressed(ByteArrayInputStream(bytes), NbtAccounter.create(ProtocolLimits.MAX_ITEM_NBT_UNCOMPRESSED.toLong()))
            val current = SharedConstants.getCurrentVersion().dataVersion().version()
            val version = NbtUtils.getDataVersion(tag, current)
            tag.remove(SharedConstants.DATA_VERSION_TAG)
            var dynamic: Dynamic<Tag> = Dynamic(NbtOps.INSTANCE, tag)
            if (version < current) {
                dynamic = DataFixers.getDataFixer().update(References.ITEM_STACK, dynamic, version, current)
            }
            ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), dynamic.value)
                .resultOrPartial { logger.debug("Invalid item stack from server: {}", it) }
                .orElse(null)
        } catch (e: Exception) {
            logger.debug("Failed to read item NBT from server", e)
            null
        } catch (e: StackOverflowError) {
            null
        }
    }

    private fun currentRegistries(): HolderLookup.Provider? = Minecraft.getInstance().connection?.registryAccess()
}
