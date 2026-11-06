package im.crisp.sdk.internal.bridge.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** A message as returned by `getMessages()` — may be pending, failed, or not yet acknowledged. */
@Serializable(with = LocalMessageSerializer::class)
internal data class LocalMessage(
    val generic: GenericMessage,
    val fingerprint: LocalMessageFingerprint? = null,
    val error: Boolean? = null,
    val sending: Boolean? = null,
    val metas: Metas? = null,
) {
    val content get() = generic.content
    val origin get() = generic.origin
    val timestamp get() = generic.timestamp
    val from get() = generic.from
    val user get() = generic.user
    val read get() = generic.read

    @Serializable
    data class Metas(
        @SerialName("is_new") val isNew: Boolean? = null,
        @SerialName("is_pending") val isPending: Boolean? = null,
        val error: String? = null,
        val field: Field? = null,
    ) {
        @Serializable
        data class Field(val value: String? = null)
    }
}

internal object LocalMessageSerializer : JsonObjectSerializer<LocalMessage>() {
    override fun fromJson(obj: JsonObject) = LocalMessage(
        generic = GenericMessageCodec.decode(obj),
        // Numeric => server fingerprint, anything else => local string id (same order as Swift).
        fingerprint = obj["fingerprint"].nonNull()?.asPrimitive()?.let { p ->
            p.longOrNull?.takeIf { !p.isString }?.let { LocalMessageFingerprint.Remote(MessageFingerprint(it)) }
                ?: LocalMessageFingerprint.Local(p.content)
        },
        error = obj["error"].nonNull()?.asPrimitive()?.booleanOrNull,
        sending = obj["sending"].nonNull()?.asPrimitive()?.booleanOrNull,
        metas = obj["metas"].nonNull()?.let {
            bridgeJson.decodeFromJsonElement(LocalMessage.Metas.serializer(), it)
        },
    )

    override fun toJson(value: LocalMessage): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        GenericMessageCodec.encode(value.generic, out)
        when (val f = value.fingerprint) {
            is LocalMessageFingerprint.Remote -> out["fingerprint"] = JsonPrimitive(f.fingerprint.rawValue)
            is LocalMessageFingerprint.Local -> out["fingerprint"] = JsonPrimitive(f.value)
            null -> Unit
        }
        value.error?.let { out["error"] = JsonPrimitive(it) }
        value.sending?.let { out["sending"] = JsonPrimitive(it) }
        value.metas?.let {
            out["metas"] = bridgeJson.encodeToJsonElement(LocalMessage.Metas.serializer(), it)
        }
        return JsonObject(out)
    }
}
