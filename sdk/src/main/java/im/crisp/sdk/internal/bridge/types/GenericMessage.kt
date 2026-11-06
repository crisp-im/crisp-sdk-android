package im.crisp.sdk.internal.bridge.types

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Where a message comes from. Open-ended on purpose (`urn:…` values), hence not an enum. */
@Serializable
@JvmInline
internal value class Origin(val rawValue: String) {
    companion object {
        fun of(raw: String) = Origin(raw.lowercase())

        val CHAT = Origin("chat")
        val EMAIL = Origin("email")
        val LOCAL = Origin("local")
        val NETWORK = Origin("network")
        val UPDATE = Origin("update")
        val TRIGGER = Origin("urn:crisp.im:triggers:0")
    }
}

@Serializable
internal enum class MessageSender {
    @SerialName("user") USER,
    @SerialName("operator") OPERATOR,
}

@Serializable
internal data class Participant(
    val nickname: String? = null,
    /** A session id (user), an operator id (operator) or null (bot). */
    val userId: String? = null,
    val avatar: String? = null,
)

@Serializable
internal data class MessageIgnored(
    val type: String? = null,
    val reason: String? = null
)

@Serializable
internal data class MessageReference(
    val type: String? = null,
    val name: String? = null,
    val target: String? = null,
)

@Serializable
internal enum class ConversationMessageType {
    @SerialName("animation") ANIMATION,
    @SerialName("audio") AUDIO,
    @SerialName("carousel") CAROUSEL,
    @SerialName("event") EVENT,
    @SerialName("field") FIELD,
    @SerialName("file") FILE,
    @SerialName("note") NOTE,
    @SerialName("picker") PICKER,
    @SerialName("text") TEXT,
}

/** Fields shared by [Message] and [LocalMessage]. Mirrors the Swift `GenericMessage`. */
internal data class GenericMessage(
    val content: ConversationMessageContent,
    val origin: Origin,
    /** Epoch milliseconds. */
    val timestamp: Long,
    val from: MessageSender,
    val user: Participant? = null,
    val read: Boolean = false,
    val automated: Boolean = false,
    val edited: Boolean = false,
    val translated: Boolean = false,
    val ignored: Map<String, MessageIgnored>? = null,
    val references: List<MessageReference>? = null,
    val properties: Map<String, JsonElement>? = null,
)

/**
 * JSON <-> [GenericMessage].
 *
 * The Swift implementation needs a mutable `ConversationMessageDecodingContext` smuggled through
 * `decoder.userInfo`, because `Decodable` can't look at a sibling key while decoding `content`.
 * Here we decode from a [JsonObject] tree instead, so we can simply read `type` and `preview`
 * first and pass them to [decodeContent]. No shared state, nothing to reset between messages.
 */
internal object GenericMessageCodec {
    fun decode(obj: JsonObject): GenericMessage {
        val type = bridgeJson.decodeFromJsonElement(
            ConversationMessageType.serializer(),
            obj.require("type"),
        )

        // A malformed preview is ignored rather than failing the whole message (Swift: `try?`).
        val previews = obj["preview"]?.let {
            runCatching {
                bridgeJson.decodeFromJsonElement(ListSerializer(TextMessagePreview.serializer()), it)
            }.getOrNull()
        }

        return GenericMessage(
            content = decodeContent(type, obj.require("content"), previews),
            origin = obj["origin"].nonNull()?.let { Origin.of(it.asPrimitive().content) } ?: Origin.CHAT,
            timestamp = obj.require("timestamp").asPrimitive().longOrNull
                ?: throw SerializationException("`timestamp` must be an integer (epoch ms)"),
            from = bridgeJson.decodeFromJsonElement(MessageSender.serializer(), obj.require("from")),
            user = obj["user"].nonNull()?.let {
                bridgeJson.decodeFromJsonElement(Participant.serializer(), it)
            },
            read = obj["read"].isTruthy(),
            automated = obj["automated"].nonNull()?.asPrimitive()?.booleanOrNull ?: false,
            edited = obj["edited"].nonNull()?.asPrimitive()?.booleanOrNull ?: false,
            translated = obj["translated"].nonNull()?.asPrimitive()?.booleanOrNull ?: false,
            ignored = obj["ignored"].nonNull()?.let {
                bridgeJson.decodeFromJsonElement(
                    kotlinx.serialization.builtins.MapSerializer(
                        String.serializer(),
                        MessageIgnored.serializer(),
                    ),
                    it,
                )
            },
            references = obj["references"].nonNull()?.let {
                bridgeJson.decodeFromJsonElement(ListSerializer(MessageReference.serializer()), it)
            },
            properties = obj["properties"].nonNull()?.let {
                (it as? JsonObject)?.toMap() ?: throw SerializationException("`properties` must be an object")
            },
        )
    }

    /** Writes the generic fields into [out]. */
    fun encode(message: GenericMessage, out: MutableMap<String, JsonElement>) {
        out["origin"] = JsonPrimitive(message.origin.rawValue)
        out["timestamp"] = JsonPrimitive(message.timestamp)
        message.user?.let { out["user"] = bridgeJson.encodeToJsonElement(Participant.serializer(), it) }
        out["from"] = bridgeJson.encodeToJsonElement(MessageSender.serializer(), message.from)
        out["read"] = JsonPrimitive(message.read)
        out["automated"] = JsonPrimitive(message.automated)
        out["edited"] = JsonPrimitive(message.edited)
        out["translated"] = JsonPrimitive(message.translated)
        message.ignored?.let {
            out["ignored"] = JsonObject(
                it.mapValues { (_, v) -> bridgeJson.encodeToJsonElement(MessageIgnored.serializer(), v) },
            )
        }
        message.references?.let {
            out["references"] = bridgeJson.encodeToJsonElement(
                ListSerializer(MessageReference.serializer()),
                it,
            )
        }
        message.properties?.let { out["properties"] = JsonObject(it) }

        out["type"] = bridgeJson.encodeToJsonElement(
            ConversationMessageType.serializer(),
            message.content.messageType,
        )
        message.content.textMessagePreviews?.let {
            out["preview"] = bridgeJson.encodeToJsonElement(
                ListSerializer(TextMessagePreview.serializer()),
                it,
            )
        }
        out["content"] = encodeContent(message.content)
    }

    // region content

    /**
     * The Kotlin counterpart of `ConversationMessageContent.init(from:)`: the `type` selects the
     * shape of `content`; for `text`, a `preview` upgrades the result to an attachment variant.
     */
    fun decodeContent(
        type: ConversationMessageType,
        content: JsonElement,
        previews: List<TextMessagePreview>?,
    ): ConversationMessageContent {
        fun <T> decode(s: DeserializationStrategy<T>) = bridgeJson.decodeFromJsonElement(s, content)

        return when (type) {
            ConversationMessageType.TEXT -> {
                val text = content.asPrimitive().contentOrNull
                    ?: throw SerializationException("`text` content must be a string")
                val preview = previews?.firstOrNull()
                if (preview == null) {
                    ConversationMessageContent.TextContent(text)
                } else {
                    val attachment = ConversationMessageContent.WebsiteAttachment(
                        preview.title,
                        preview.website,
                        preview.url,
                    )
                    val video = preview.preview?.videoPreview
                    if (video != null) {
                        ConversationMessageContent.TextContentWithVideoAttachment(text, attachment, video)
                    } else {
                        ConversationMessageContent.TextContentWithAttachment(
                            text,
                            attachment,
                            preview.preview?.attachmentPreview,
                        )
                    }
                }
            }
            ConversationMessageType.NOTE -> ConversationMessageContent.NoteContent(
                content.asPrimitive().contentOrNull
                    ?: throw SerializationException("`note` content must be a string"),
            )
            ConversationMessageType.FILE -> decode(ConversationMessageContent.FileContent.serializer())
            ConversationMessageType.ANIMATION -> decode(ConversationMessageContent.AnimationContent.serializer())
            ConversationMessageType.AUDIO -> decode(ConversationMessageContent.AudioContent.serializer())
            ConversationMessageType.PICKER -> decode(ConversationMessageContent.PickerContent.serializer())
            ConversationMessageType.FIELD -> decode(ConversationMessageContent.FieldContent.serializer())
            ConversationMessageType.CAROUSEL ->
                decode(ConversationMessageContent.CarouselContent.serializer())
            ConversationMessageType.EVENT -> {
                val obj = content as? JsonObject
                    ?: throw SerializationException("`event` content must be an object")
                ConversationMessageContent.EventContent(
                    type = ConversationMessageContent.EventContent.EventType.fromRawValue(
                        obj.require("namespace").asPrimitive().content,
                    ),
                    text = obj["text"].nonNull()?.asPrimitive()?.contentOrNull,
                )
            }
        }
    }

    fun encodeContent(content: ConversationMessageContent): JsonElement = when (content) {
        is ConversationMessageContent.TextContent -> JsonPrimitive(content.text)
        is ConversationMessageContent.TextContentWithAttachment -> JsonPrimitive(content.text)
        is ConversationMessageContent.TextContentWithVideoAttachment -> JsonPrimitive(content.text)
        is ConversationMessageContent.NoteContent -> JsonPrimitive(content.text)
        is ConversationMessageContent.FileContent ->
            bridgeJson.encodeToJsonElement(ConversationMessageContent.FileContent.serializer(), content)
        is ConversationMessageContent.AnimationContent ->
            bridgeJson.encodeToJsonElement(ConversationMessageContent.AnimationContent.serializer(), content)
        is ConversationMessageContent.AudioContent ->
            bridgeJson.encodeToJsonElement(ConversationMessageContent.AudioContent.serializer(), content)
        is ConversationMessageContent.PickerContent ->
            bridgeJson.encodeToJsonElement(ConversationMessageContent.PickerContent.serializer(), content)
        is ConversationMessageContent.FieldContent ->
            bridgeJson.encodeToJsonElement(ConversationMessageContent.FieldContent.serializer(), content)
        is ConversationMessageContent.CarouselContent ->
            bridgeJson.encodeToJsonElement(ConversationMessageContent.CarouselContent.serializer(), content)
        is ConversationMessageContent.EventContent -> buildJsonObject {
            put("namespace", content.type.rawValue)
            content.text?.let { put("text", it) }
        }
    }

    // endregion

    // region helpers

    /**
     * Swift: "if `read` exists, is not null and is not `false`, the message is read". The server may
     * send `"chat"` or `0` there, so anything that isn't absent/null/false counts as read.
     */
    private fun JsonElement?.isTruthy(): Boolean {
        if (this == null || this is JsonNull) return false
        return (this as? JsonPrimitive)?.booleanOrNull != false
    }
}
