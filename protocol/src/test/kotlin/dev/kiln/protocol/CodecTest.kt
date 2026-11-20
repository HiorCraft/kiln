package dev.kiln.protocol

import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.IdCodec
import dev.kiln.protocol.codec.MessageCodec
import dev.kiln.protocol.codec.NamespacedIdCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.RangedVarIntCodec
import dev.kiln.protocol.codec.SignedVarIntCodec
import dev.kiln.protocol.codec.StringCodec
import dev.kiln.protocol.codec.VarIntCodec
import dev.kiln.protocol.element.DropdownOption
import dev.kiln.protocol.element.NumericRange
import dev.kiln.protocol.message.CreativeTabData
import dev.kiln.protocol.message.ExtensionInfo
import dev.kiln.protocol.message.KeybindDeclaration
import dev.kiln.protocol.message.StopAnimation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Round trips and limits of the individual codecs and combinators. */
class CodecTest {
    private fun <T> roundTrip(codec: Codec<T>, value: T): T {
        val writer = ProtocolWriter().also { codec.encode(it, value) }
        val reader = ProtocolReader(writer.toByteArray())
        return codec.decode(reader).also { reader.expectEnd() }
    }

    private fun <T> assertRoundTrip(codec: Codec<T>, vararg values: T) {
        for (value in values) assertEquals(value, roundTrip(codec, value))
    }

    private fun <T> assertRejected(codec: Codec<T>, value: T) {
        assertThrows<ProtocolException>(value.toString()) { codec.encode(ProtocolWriter(), value) }
    }

    @Test
    fun `primitive codecs round-trip their whole range`() {
        assertRoundTrip(VarIntCodec, 0, 1, 127, 128, Int.MAX_VALUE, -1, Int.MIN_VALUE)
        assertRoundTrip(SignedVarIntCodec(Int.MIN_VALUE, Int.MAX_VALUE, "n"), 0, -1, 1, Int.MIN_VALUE, Int.MAX_VALUE)
        assertRoundTrip(StringCodec(8, "s"), "", "Hällo 😀")
        assertRoundTrip(Color, Color.WHITE, Color.TRANSPARENT, Color.argb(1, 2, 3, 4))
        assertRoundTrip(TextAlign, *TextAlign.entries.toTypedArray())
    }

    @Test
    fun `ranges and lengths are enforced`() {
        val ranged = RangedVarIntCodec(1, 10, "n")
        assertRoundTrip(ranged, 1, 10)
        assertRejected(ranged, 0)
        assertRejected(ranged, 11)
        assertRejected(StringCodec(3, "s"), "abcd")
        assertRejected(IdCodec.ELEMENT, "bad id!")
        assertRejected(IdCodec.LUA_EXTENSION, "a.b")
        assertRejected(NamespacedIdCodec("id"), "no_namespace")
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(StopAnimation(1, "Smash!")) }
    }

    @Test
    fun `nullable and list combinators`() {
        assertRoundTrip(StringCodec(8, "s").nullable(), null, "x")
        val list = VarIntCodec.list(3, "numbers")
        assertRoundTrip(list, emptyList(), listOf(1, 2, 3))
        assertRejected(list, listOf(1, 2, 3, 4))
        // a size above the limit is rejected on decode too
        val oversized = ProtocolWriter().apply { varInt(4); repeat(4) { varInt(it) } }.toByteArray()
        assertThrows<ProtocolException> { list.decode(ProtocolReader(oversized)) }
    }

    @Test
    fun `validated codecs check on encode and decode`() {
        val even = VarIntCodec.validated { if (it % 2 != 0) throw ProtocolException("odd $it") }
        assertRoundTrip(even, 0, 2)
        assertRejected(even, 3)
        val odd = ProtocolWriter().apply { varInt(3) }.toByteArray()
        assertThrows<ProtocolException> { even.decode(ProtocolReader(odd)) }
    }

    @Test
    fun `value type codecs round-trip`() {
        assertRoundTrip(ResourceId.codec("id"), ResourceId.parse("stone"), ResourceId.of("kiln_example", "textures/a.png"))
        val extreme = Bounds(-ProtocolLimits.MAX_COORDINATE, ProtocolLimits.MAX_COORDINATE, 1, ProtocolLimits.MAX_SIZE)
        assertRoundTrip(Bounds, Bounds(0, 0, 0, 0), extreme)
        assertRejected(Bounds, Bounds(0, 0, ProtocolLimits.MAX_SIZE + 1, 0))
        assertRoundTrip(ItemStackData, MessageCodecTest.diamond, MessageCodecTest.fancySword)
        assertRoundTrip(NumericRange, NumericRange(-1.0, 1.0), NumericRange(0.0, 0.5, integer = false))
        assertRejected(NumericRange, NumericRange(1.0, 0.0))
        assertRoundTrip(DropdownOption, DropdownOption("v", "\"Label\""))
        assertRoundTrip(KeybindDeclaration, KeybindDeclaration("open", "Open", "Cat", "key.keyboard.g"))
        assertRoundTrip(
            CreativeTabData,
            CreativeTabData("a:b", "{}", null, emptyList(), null),
            CreativeTabData("a:b", "{}", MessageCodecTest.diamond, listOf(MessageCodecTest.fancySword), "minecraft:combat"),
        )
        val sha256 = ByteArray(ProtocolLimits.SHA256_BYTES)
        val info = ExtensionInfo("ext", "1.0", "Ext", sha256, 10, listOf("hud"), byteArrayOf(1), listOf("dep"))
        assertRoundTrip(ExtensionInfo, info)
        assertRejected(ExtensionInfo, ExtensionInfo("ext", "1", "n", ByteArray(31), 10, emptyList()))
    }
}
