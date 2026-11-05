package im.crisp.sdk.internal.bridge.types

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal val bridgeJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

internal fun JsonObject.require(key: String): JsonElement =
    this[key].nonNull() ?: throw SerializationException("Missing required key `$key`")

internal fun JsonElement?.nonNull(): JsonElement? = if (this == null || this is JsonNull) null else this

internal fun JsonElement.asPrimitive(): JsonPrimitive =
    this as? JsonPrimitive ?: throw SerializationException("Expected a primitive, got $this")

/** Base for serializers that need the raw [JsonObject] on both sides. */
internal abstract class JsonObjectSerializer<T> : KSerializer<T> {
    abstract fun fromJson(obj: JsonObject): T

    abstract fun toJson(value: T): JsonObject

    override val descriptor: SerialDescriptor =
        JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): T {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("This type can only be decoded from JSON")
        val element = jsonDecoder.decodeJsonElement()
        val obj = element as? JsonObject
            ?: throw SerializationException("Expected a JSON object, got $element")
        return fromJson(obj)
    }

    override fun serialize(encoder: Encoder, value: T) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("This type can only be encoded to JSON")
        jsonEncoder.encodeJsonElement(toJson(value))
    }
}
