package dev.kiln.server.runtime.extension

import com.google.gson.JsonParseException
import dev.kiln.protocol.ExtensionMessage
import dev.kiln.protocol.ProtocolLimits
import dev.kiln.protocol.ResourceId
import dev.kiln.protocol.codec.MessageCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ExtensionJsonTest {
    data class Hello(val text: String, val count: Int = 0, val tags: List<String> = emptyList())

    @Test
    fun `typed roundtrip through the wire format`() {
        val json = ExtensionJson.encode(Hello("Hallo <zurück> & 😀", 3, listOf("a")))
        assertEquals("""{"text":"Hallo <zurück> & 😀","count":3,"tags":["a"]}""", json)
        val wire = MessageCodec.encodeServerbound(ExtensionMessage(ResourceId.of("kiln_example", "hello"), json))
        val decoded = MessageCodec.decodeServerbound(wire) as ExtensionMessage
        assertEquals(Hello("Hallo <zurück> & 😀", 3, listOf("a")), ExtensionJson.decode<Hello>(decoded.json, Hello::class.java))
    }

    @Test
    fun `lua style messages bind leniently and null is dropped`() {
        // Lua numbers arrive as doubles-in-text; missing fields stay null/default as Gson does
        val msg = ExtensionJson.decode<Hello>("""{"text":"x","count":2.0}""", Hello::class.java)!!
        assertEquals(2, msg.count)
        assertNull(ExtensionJson.decode<Hello>("null", Hello::class.java))
    }

    @Test
    fun `invalid json is rejected`() {
        val nested = "[".repeat(ExtensionJson.MAX_DEPTH + 1) + "]".repeat(ExtensionJson.MAX_DEPTH + 1)
        val okNested = "[".repeat(ExtensionJson.MAX_DEPTH) + "]".repeat(ExtensionJson.MAX_DEPTH)
        ExtensionJson.parse(okNested)
        for (bad in listOf("", "{", "{} {}", "{a:1}", "{'a':1}", "[1,]", nested, "NaN", "\"x\" trailing")) {
            assertThrows<JsonParseException>(bad) { ExtensionJson.parse(bad) }
        }
        assertThrows<JsonParseException> { ExtensionJson.decode<Hello>("""{"text":[1]}""", Hello::class.java) }
        assertThrows<JsonParseException> { ExtensionJson.parse("\"" + "x".repeat(ProtocolLimits.MAX_EXTENSION_JSON_LENGTH) + "\"") }
        assertThrows<IllegalArgumentException> { ExtensionJson.encode(Hello("x".repeat(ProtocolLimits.MAX_CLIENTBOUND_EXTENSION_JSON_LENGTH))) }
        ExtensionJson.encode(Hello("x".repeat(ProtocolLimits.MAX_EXTENSION_JSON_LENGTH * 4)))
    }
}
