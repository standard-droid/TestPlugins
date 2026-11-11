package com.a.anizm

import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// v53: JSON goes through kotlinx.serialization and CloudStream's AppUtils.parseJson/tryParseJson/toJson
// (org.json before). No network, Android or CloudStream classes beyond AppUtils, so
// tools/check-units.sh compiles this with a stand-in AppUtils that uses the app's own Json settings.

/**
 * A text field the sites send as a string one day and a number the next (ids, years, "kx" expiries,
 * booleans in strings): read from any JSON primitive, trimmed. An object or array reads as "".
 */
internal object LooseString : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("com.a.anizm.LooseString", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String {
        val e = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeString().trim()
        return (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim().orEmpty()
    }
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** A field read with [LooseString]. Blank means absent: read it with [nb]. */
internal typealias Str = @Serializable(with = LooseString::class) String

/** Trimmed, or null when blank. */
internal fun String?.nb(): String? = this?.trim()?.ifBlank { null }

// For the answers whose shape varies (a bare array or a wrapper object, keys that differ per video).
internal fun JsonElement?.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)?.takeIf { it !is JsonNull }
internal fun JsonElement?.obj(key: String): JsonObject? = field(key) as? JsonObject
internal fun JsonElement?.arr(key: String): JsonArray? = field(key) as? JsonArray
/** A primitive value as trimmed text (numbers included), null when absent or blank. */
internal fun JsonElement?.str(key: String): String? = (field(key) as? JsonPrimitive)?.content.nb()
/** This value as text: a primitive's content, otherwise its JSON. */
internal fun JsonElement.asText(): String = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: toString()

/** One part of a parsed answer, decoded into a typed class (null when it doesn't fit). */
internal inline fun <reified T : Any> JsonElement.decodeAs(): T? = tryParseJson<T>(toString())

/** [s] as a JSON (and JavaScript) string literal, quotes and escapes included. */
internal fun jsonQuote(s: String): String = JsonPrimitive(s).toString()
