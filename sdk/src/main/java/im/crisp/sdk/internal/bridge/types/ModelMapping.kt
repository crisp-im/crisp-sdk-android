package im.crisp.sdk.internal.bridge.types

import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.net.MalformedURLException
import java.net.URL
import java.util.Date
import im.crisp.sdk.model.AttachmentPreview
import im.crisp.sdk.model.AudioFile
import im.crisp.sdk.model.Carousel
import im.crisp.sdk.model.EmbeddedPreview
import im.crisp.sdk.model.Field
import im.crisp.sdk.model.AnimationFile
import im.crisp.sdk.model.MessageContent
import im.crisp.sdk.model.Picker
import im.crisp.sdk.model.WebsiteAttachment
import im.crisp.sdk.model.Company as ModelCompany
import im.crisp.sdk.model.File as ModelFile
import im.crisp.sdk.model.Message as ModelMessage
import im.crisp.sdk.model.SessionEventColor as ModelSessionEventColor

/*
 * Conversions between the bridge types (wire format of the Web client) and the public `model`
 * types handed to integrators.
 */

// region Message (Web client → integrator)

/** `null` when the message cannot be represented by the public model (events, notes, invalid URLs). */
internal fun Message.toModel(): ModelMessage? {
    val content = try {
        content.toModel()
    } catch (e: MalformedURLException) {
        null
    } ?: return null
    return ModelMessage(
        isMe = from == MessageSender.USER,
        content = content,
        origin = when (origin) {
            Origin.LOCAL -> ModelMessage.Origin.LOCAL
            Origin.UPDATE -> ModelMessage.Origin.UPDATE
            // Chat, email, triggers, etc. all come from the network
            else -> ModelMessage.Origin.NETWORK
        },
        timestamp = Date(timestamp),
        fingerprint = fingerprint.rawValue,
        from = when (from) {
            MessageSender.USER -> ModelMessage.Sender.USER
            MessageSender.OPERATOR -> ModelMessage.Sender.OPERATOR
        },
        user = user?.let {
            ModelMessage.User(
                nickname = it.nickname,
                userId = it.userId,
                avatar = it.avatar?.let { avatar -> runCatching { URL(avatar) }.getOrNull() }
            )
        }
    )
}

// endregion

// region Message content

@Throws(MalformedURLException::class)
internal fun ConversationMessageContent.toModel(): MessageContent? = when (this) {
    is ConversationMessageContent.TextContent -> MessageContent.TextContent(text)

    is ConversationMessageContent.TextContentWithAttachment -> MessageContent.TextContentWithAttachment(
        text = text,
        attachment = attachment.toModel(),
        preview = preview?.let { AttachmentPreview(it.excerpt, URL(it.image)) }
    )

    is ConversationMessageContent.TextContentWithVideoAttachment -> MessageContent.TextContentWithVideoAttachment(
        text = text,
        attachment = attachment.toModel(),
        preview = EmbeddedPreview(preview.excerpt, URL(preview.image), URL(preview.embed))
    )

    is ConversationMessageContent.FileContent -> MessageContent.FileContent(ModelFile(name, mimeType, URL(url)))

    is ConversationMessageContent.AnimationContent -> MessageContent.AnimationContent(
        AnimationFile(
            mimeType,
            URL(url)
        )
    )

    is ConversationMessageContent.AudioContent -> MessageContent.AudioContent(
        AudioFile(
            mimeType,
            URL(url),
            duration
        )
    )

    is ConversationMessageContent.PickerContent -> MessageContent.PickerContent(
        Picker(
            id = id.rawValue,
            text = text,
            choices = choices.map { choice ->
                Picker.Choice(
                    label = choice.label,
                    icon = choice.icon,
                    selected = choice.selected,
                    value = choice.value,
                    action = choice.action?.let {
                        Picker.Choice.Action(
                            type = when (it.type) {
                                ConversationMessageContent.PickerContent.Choice.Action.Type.LINK ->
                                    Picker.Choice.Action.Type.LINK

                                ConversationMessageContent.PickerContent.Choice.Action.Type.FRAME ->
                                    Picker.Choice.Action.Type.FRAME
                            },
                            target = URL(it.target)
                        )
                    }
                )
            }
        )
    )

    is ConversationMessageContent.FieldContent -> MessageContent.FieldContent(
        Field(id = id.rawValue, text = text, explain = explain, value = value)
    )

    is ConversationMessageContent.CarouselContent -> MessageContent.CarouselContent(
        Carousel(
            text = text,
            targets = targets.map { target ->
                Carousel.Target(
                    title = target.title,
                    description = target.description,
                    image = target.image?.let(::URL),
                    actions = target.actions.map {
                        Carousel.Target.Action(
                            it.label,
                            URL(it.url)
                        )
                    }
                )
            }
        )
    )

    is ConversationMessageContent.EventContent, is ConversationMessageContent.NoteContent -> null
}

private fun ConversationMessageContent.WebsiteAttachment.toModel() =
    WebsiteAttachment(title = title, website = website, url = URL(url))

// endregion

// region MessageContent (integrator → Web client)

internal fun MessageContent.toBridge(): ConversationMessageContent = when (this) {
    is MessageContent.TextContent -> ConversationMessageContent.TextContent(text)
    is MessageContent.TextContentWithAttachment -> ConversationMessageContent.TextContentWithAttachment(
        text = text,
        attachment = attachment.toBridge(),
        preview = preview?.let {
            ConversationMessageContent.AttachmentPreview(it.excerpt, it.image.toString())
        }
    )

    is MessageContent.TextContentWithVideoAttachment -> ConversationMessageContent.TextContentWithVideoAttachment(
        text = text,
        attachment = attachment.toBridge(),
        preview = ConversationMessageContent.EmbeddedPreview(
            preview.excerpt,
            preview.image.toString(),
            preview.embed.toString()
        )
    )

    is MessageContent.FileContent ->
        ConversationMessageContent.FileContent(file.name, file.mimeType, file.url.toString())

    is MessageContent.AnimationContent ->
        ConversationMessageContent.AnimationContent(animationFile.mimeType, animationFile.url.toString())

    is MessageContent.AudioContent ->
        ConversationMessageContent.AudioContent(audioFile.mimeType, audioFile.url.toString(), audioFile.duration)

    is MessageContent.PickerContent -> ConversationMessageContent.PickerContent(
        id = PickerId(picker.id),
        text = picker.text,
        choices = picker.choices.map { choice ->
            ConversationMessageContent.PickerContent.Choice(
                label = choice.label,
                icon = choice.icon,
                selected = choice.selected,
                value = choice.value,
                action = choice.action?.let {
                    ConversationMessageContent.PickerContent.Choice.Action(
                        type = when (it.type) {
                            Picker.Choice.Action.Type.LINK ->
                                ConversationMessageContent.PickerContent.Choice.Action.Type.LINK

                            Picker.Choice.Action.Type.FRAME ->
                                ConversationMessageContent.PickerContent.Choice.Action.Type.FRAME
                        },
                        target = it.target.toString()
                    )
                }
            )
        }
    )

    is MessageContent.FieldContent -> ConversationMessageContent.FieldContent(
        id = FieldId(field.id),
        text = field.text,
        explain = field.explain,
        value = field.value
    )

    is MessageContent.CarouselContent -> ConversationMessageContent.CarouselContent(
        text = carousel.text,
        targets = carousel.targets.map { target ->
            ConversationMessageContent.CarouselContent.Target(
                title = target.title,
                description = target.description,
                image = target.image?.toString(),
                actions = target.actions.map {
                    ConversationMessageContent.CarouselContent.Target.Action(
                        it.label,
                        it.url.toString()
                    )
                }
            )
        }
    )
}

private fun WebsiteAttachment.toBridge() =
    ConversationMessageContent.WebsiteAttachment(
        title = title,
        website = website,
        url = url.toString()
    )

// endregion

// region Company

internal fun ModelCompany.toBridge() = Company(
    url = url,
    description = description,
    employment = employment?.let { Employment(role = it.role, title = it.title) },
    geolocation = geolocation?.let { Geolocation(city = it.city, country = it.country) }
)

/** `null` when the Web client has no company name, which the public model requires. */
internal fun Company.toModel(): ModelCompany? = name?.let {
    ModelCompany(
        name = it,
        url = url,
        description = description,
        employment = employment?.let { e ->
            ModelCompany.Employment(
                role = e.role,
                title = e.title
            )
        },
        geolocation = geolocation?.let { g ->
            ModelCompany.Geolocation(
                city = g.city,
                country = g.country
            )
        }
    )
}

// endregion

// region Session

internal fun ModelSessionEventColor.toBridge(): SessionEventColor = SessionEventColor.valueOf(name)

/**
 * Session data and event values: the Web client only accepts strings, booleans and numbers.
 * `null` for any other value.
 */
internal fun sessionValueToJson(value: Any?): JsonPrimitive? = when (value) {
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    else -> null
}

/**
 * Session and event data values converted by [sessionValueToJson],
 * logging and skipping the others.
 */
internal fun sessionValuesToJson(
    values: Map<String, Any?>,
    logTag: String
): Map<String, JsonPrimitive> =
    values.mapNotNull { (key, value) ->
        val json = sessionValueToJson(value)
        if (json == null) {
            log(Level.WARN, logTag, "Ignoring session value of '$key': $value is not a String, Boolean or Number")
        }
        json?.let { key to it }
    }.toMap()

/** A [String], [Boolean], [Long] or [Double], or `null`. Objects and arrays are returned as Json text. */
internal fun sessionValueFromJson(json: JsonElement): Any? = when {
    json is JsonNull -> null
    json is JsonPrimitive && json.isString -> json.content
    json is JsonPrimitive -> json.booleanOrNull ?: json.longOrNull ?: json.doubleOrNull
    ?: json.content

    else -> json.toString()
}

// endregion
