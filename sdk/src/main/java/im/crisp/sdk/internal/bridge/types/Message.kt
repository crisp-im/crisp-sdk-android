package im.crisp.sdk.internal.bridge.types

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A message as emitted by `onMessageSent` / `onMessageReceived` and accepted by `injectMessage`. */
@Serializable(with = MessageSerializer::class)
internal data class Message(
    val generic: GenericMessage,
    val fingerprint: MessageFingerprint,
    val sessionId: SessionId? = null,
) {
    // Swift forwards GenericMessage fields with @dynamicMemberLookup; Kotlin has no equivalent,
    // so the most used ones are exposed explicitly (the rest are reachable through `generic`).
    val content get() = generic.content
    val origin get() = generic.origin
    val timestamp get() = generic.timestamp
    val from get() = generic.from
    val user get() = generic.user
    val read get() = generic.read
}

internal object MessageSerializer : JsonObjectSerializer<Message>() {
    override fun fromJson(obj: JsonObject) = Message(
        generic = GenericMessageCodec.decode(obj),
        fingerprint = MessageFingerprint(
            obj.require("fingerprint").asPrimitive().longOrNull
                ?: throw kotlinx.serialization.SerializationException("`fingerprint` must be an integer"),
        ),
        sessionId = obj["session_id"].nonNull()?.asPrimitive()?.content?.let(::SessionId),
    )

    override fun toJson(value: Message): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        GenericMessageCodec.encode(value.generic, out)
        out["fingerprint"] = JsonPrimitive(value.fingerprint.rawValue)
        value.sessionId?.let { out["session_id"] = JsonPrimitive(it.rawValue) }
        return JsonObject(out)
    }
}
