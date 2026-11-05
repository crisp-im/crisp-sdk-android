package im.crisp.sdk.internal.bridge.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Preview attached next to a [text][im.crisp.sdk.internal.bridge.types.ConversationMessageType.TEXT] message (`"preview": [...]` sibling of `content`). */
@Serializable
internal data class TextMessagePreview(
    val title: String,
    val website: String,
    val url: String,
    val preview: Preview? = null,
    val stamped: Boolean? = null,
) {
    @Serializable
    data class Preview(
        val excerpt: String? = null,
        val image: String? = null,
        val embed: String? = null,
    ) {
        val videoPreview: ConversationMessageContent.EmbeddedPreview?
            get() {
                val image = image ?: return null
                val embed = embed ?: return null
                val host = runCatching { java.net.URI(embed).host }.getOrNull()?.lowercase()
                return if (host in KNOWN_VIDEO_HOSTS) {
                    ConversationMessageContent.EmbeddedPreview(excerpt, image, embed)
                } else {
                    null
                }
            }

        val attachmentPreview: ConversationMessageContent.AttachmentPreview?
            get() = image?.let { ConversationMessageContent.AttachmentPreview(excerpt, it) }
    }
}

private val KNOWN_VIDEO_HOSTS = setOf("youtube.com", "www.youtube.com", "vimeo.com", "www.vimeo.com")

/**
 * The `content` of a message. Its JSON shape depends on the sibling `type` field, which is why it
 * is *not* `@Serializable` on its own — see `GenericMessage.kt` for the type-driven decoding.
 */
internal sealed interface ConversationMessageContent {
    @Serializable
    data class WebsiteAttachment(val title: String, val website: String, val url: String)

    @Serializable
    data class AttachmentPreview(val excerpt: String? = null, val image: String)

    @Serializable
    data class EmbeddedPreview(val excerpt: String? = null, val image: String, val embed: String)

    @Serializable
    data class FieldContent(
        val id: FieldId,
        val text: String,
        val explain: String,
        val required: Boolean? = null,
        val value: String? = null,
    ) : ConversationMessageContent

    @Serializable
    data class FileContent(
        val name: String,
        @SerialName("type") val mimeType: String,
        val url: String,
    ) : ConversationMessageContent

    @Serializable
    data class AudioContent(
        @SerialName("type") val mimeType: String,
        val url: String,
        val duration: Int,
    ) : ConversationMessageContent

    @Serializable
    data class AnimationContent(
        @SerialName("type") val mimeType: String,
        val url: String,
    ) : ConversationMessageContent

    @Serializable
    data class PickerContent(
        val id: PickerId,
        val text: String,
        val choices: List<Choice>,
        val required: Boolean? = null,
    ) : ConversationMessageContent {
        @Serializable
        data class Choice(
            val label: String,
            val icon: String? = null,
            val selected: Boolean,
            val value: String,
            val action: Action? = null,
        ) {
            @Serializable
            data class Action(val type: Type, val target: String) {
                @Serializable
                enum class Type {
                    @SerialName("link") LINK,
                    @SerialName("frame") FRAME,
                }
            }
        }
    }

    @Serializable
    data class CarouselContent(
        val text: String,
        val targets: List<Target>,
    ) : ConversationMessageContent {
        @Serializable
        data class Target(
            val title: String,
            val description: String,
            val image: String? = null,
            val actions: List<Action>,
        ) {
            @Serializable
            data class Action(val label: String, val url: String)
        }
    }

    data class EventContent(val type: EventType, val text: String?) : ConversationMessageContent {
        sealed class EventType(val rawValue: String) {
            data object StateResolved : EventType("state:resolved")
            data object UserBlocked : EventType("user:blocked")
            data object ReminderScheduled : EventType("reminder:scheduled")
            data object ThreadStarted : EventType("thread:started")
            data object ThreadEnded : EventType("thread:ended")
            data object ParticipantAdded : EventType("participant:added")
            data object ParticipantRemoved : EventType("participant:removed")
            data object CallStarted : EventType("call:started")
            data object CallEnded : EventType("call:ended")
            data class Unknown(val namespace: String) : EventType(namespace)

            companion object {
                private val known = listOf(
                    StateResolved, UserBlocked, ReminderScheduled, ThreadStarted, ThreadEnded,
                    ParticipantAdded, ParticipantRemoved, CallStarted, CallEnded,
                )

                fun fromRawValue(raw: String): EventType =
                    known.firstOrNull { it.rawValue == raw } ?: Unknown(raw)
            }
        }
    }

    data class NoteContent(val text: String) : ConversationMessageContent

    data class TextContent(val text: String) : ConversationMessageContent

    data class TextContentWithAttachment(
        val text: String,
        val attachment: WebsiteAttachment,
        val preview: AttachmentPreview?,
    ) : ConversationMessageContent

    data class TextContentWithVideoAttachment(
        val text: String,
        val attachment: WebsiteAttachment,
        val preview: EmbeddedPreview,
    ) : ConversationMessageContent

    /** The wire `type` this content is serialized under (not `type`, `Event` has its own). */
    val messageType: ConversationMessageType
        get() = when (this) {
            is TextContent, is TextContentWithAttachment, is TextContentWithVideoAttachment -> ConversationMessageType.TEXT
            is FileContent -> ConversationMessageType.FILE
            is AnimationContent -> ConversationMessageType.ANIMATION
            is AudioContent -> ConversationMessageType.AUDIO
            is PickerContent -> ConversationMessageType.PICKER
            is FieldContent -> ConversationMessageType.FIELD
            is EventContent -> ConversationMessageType.EVENT
            is NoteContent -> ConversationMessageType.NOTE
            is CarouselContent -> ConversationMessageType.CAROUSEL
        }

    /** Value of the sibling `preview` array that must be emitted alongside `content`. */
    val textMessagePreviews: List<TextMessagePreview>?
        get() = when (this) {
            is TextContentWithAttachment -> listOf(
                TextMessagePreview(
                    title = attachment.title,
                    website = attachment.website,
                    url = attachment.url,
                    preview = preview?.let { TextMessagePreview.Preview(it.excerpt, it.image, null) },
                ),
            )
            is TextContentWithVideoAttachment -> listOf(
                TextMessagePreview(
                    title = attachment.title,
                    website = attachment.website,
                    url = attachment.url,
                    preview = TextMessagePreview.Preview(preview.excerpt, preview.image, preview.embed),
                ),
            )
            else -> null
        }
}

/** The text a user can copy from this content, or `null` if it is not copyable. */
internal fun ConversationMessageContent.copy(): String? = when (this) {
    is ConversationMessageContent.TextContent -> text
    is ConversationMessageContent.TextContentWithAttachment -> text
    is ConversationMessageContent.TextContentWithVideoAttachment -> text
    is ConversationMessageContent.NoteContent -> text
    else -> null
}
