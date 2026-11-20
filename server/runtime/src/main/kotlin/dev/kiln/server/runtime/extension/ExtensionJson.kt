package dev.kiln.server.runtime.extension

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.kiln.protocol.ProtocolLimits
import java.io.IOException
import java.io.StringReader
import java.lang.reflect.Type

/** JSON of [dev.kiln.protocol.message.ExtensionMessage]s: strict parsing with a nesting limit, Gson binding. */
object ExtensionJson {
    const val MAX_DEPTH = 16

    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    /** Parses exactly one strict JSON value with nesting depth <= [MAX_DEPTH]. @throws JsonParseException */
    fun parse(json: String): JsonElement {
        if (json.length > ProtocolLimits.MAX_EXTENSION_JSON_LENGTH) throw JsonParseException("JSON too long (${json.length} chars)")
        if (json.isBlank()) throw JsonParseException("Empty JSON")
        val reader = JsonReader(StringReader(json))
        reader.strictness = Strictness.STRICT
        reader.nestingLimit = MAX_DEPTH
        try {
            val element = JsonParser.parseReader(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) throw JsonParseException("Trailing data after JSON value")
            return element
        } catch (e: IOException) {
            throw JsonParseException(e.message, e)
        } catch (e: StackOverflowError) {
            throw JsonParseException("JSON nested too deeply")
        }
    }

    /** Parses [json] and binds it to [type]; `null` for a JSON `null`. @throws JsonParseException */
    fun <T> decode(json: String, type: Type): T? {
        val element = parse(json)
        if (element.isJsonNull) return null
        return try {
            gson.fromJson<T>(element, type)
        } catch (e: RuntimeException) {
            throw JsonParseException("Cannot bind JSON to ${type.typeName}: ${e.message}", e)
        }
    }

    /**
     * Serializes [value] for a server -> client message; @throws IllegalArgumentException if longer than
     * [ProtocolLimits.MAX_CLIENTBOUND_EXTENSION_JSON_LENGTH] (incoming client messages are limited to
     * [ProtocolLimits.MAX_EXTENSION_JSON_LENGTH]).
     */
    fun encode(value: Any): String {
        val json = gson.toJson(value)
        require(json.length <= ProtocolLimits.MAX_CLIENTBOUND_EXTENSION_JSON_LENGTH) {
            "Extension message too large (${json.length} > ${ProtocolLimits.MAX_CLIENTBOUND_EXTENSION_JSON_LENGTH} chars)"
        }
        return json
    }
}
