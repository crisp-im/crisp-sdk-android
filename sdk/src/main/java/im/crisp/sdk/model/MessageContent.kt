package im.crisp.sdk.model

import java.net.URL

/**
 * A message content to show locally.
 */
sealed class MessageContent {
    data class TextContent(val text: String) : MessageContent()

    data class TextContentWithAttachment(
        val text: String,
        val attachment: WebsiteAttachment,
        val preview: AttachmentPreview? = null
    ) : MessageContent()

    data class TextContentWithVideoAttachment(
        val text: String,
        val attachment: WebsiteAttachment,
        val preview: EmbeddedPreview
    ) : MessageContent()

    data class FileContent(val file: File) : MessageContent()

    data class AnimationContent(val animationFile: AnimationFile) : MessageContent()

    data class AudioContent(val audioFile: AudioFile) : MessageContent()

    data class FieldContent(val field: Field) : MessageContent()

    data class PickerContent(val picker: Picker) : MessageContent()

    data class CarouselContent(val carousel: Carousel) : MessageContent()
}

data class WebsiteAttachment(
    val title: String,
    val website: String,
    val url: URL
)

data class AttachmentPreview(
    val excerpt: String? = null,
    val image: URL
)

data class EmbeddedPreview(
    val excerpt: String? = null,
    val image: URL,
    val embed: URL
)

data class File(
    val name: String,
    val mimeType: String,
    val url: URL
)

data class AnimationFile(
    val mimeType: String,
    val url: URL
)

data class AudioFile(
    val mimeType: String,
    val url: URL,
    val duration: Int
)

data class Field(
    val id: String,
    val text: String,
    val explain: String,
    val value: String? = null
)

data class Picker(
    val id: String,
    val text: String,
    val choices: List<Choice>
) {
    data class Choice(
        val label: String,
        val icon: String? = null,
        val selected: Boolean,
        val value: String,
        val action: Action? = null
    ) {
        data class Action(
            val type: Type,
            val target: URL
        ) {
            enum class Type {
                LINK,
                FRAME
            }
        }
    }
}

data class Carousel(
    val text: String,
    val targets: List<Target>
) {
    data class Target(
        val title: String,
        val description: String,
        val image: URL? = null,
        val actions: List<Action>
    ) {
        data class Action(
            val label: String,
            val url: URL
        )
    }
}
