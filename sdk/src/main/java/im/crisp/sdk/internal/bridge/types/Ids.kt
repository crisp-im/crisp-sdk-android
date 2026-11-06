package im.crisp.sdk.internal.bridge.types

import kotlinx.serialization.Serializable

@Serializable @JvmInline internal value class WebsiteId(val rawValue: String)

@Serializable @JvmInline internal value class SessionId(val rawValue: String)

@Serializable @JvmInline internal value class TokenId(val rawValue: String)

@Serializable @JvmInline internal value class LocaleId(val identifier: String)

@Serializable @JvmInline internal value class LanguageCode(val rawValue: String)

@Serializable @JvmInline internal value class FieldId(val rawValue: String)

@Serializable @JvmInline internal value class PickerId(val rawValue: String)

@Serializable @JvmInline internal value class BotScenarioId(val rawValue: String)

/** Server-assigned numeric identifier of a message. */
@Serializable @JvmInline internal value class MessageFingerprint(val rawValue: Long)

/**
 * Fingerprint of a message returned by `getMessages()`. Messages that were not acknowledged by
 * the server yet carry a local string fingerprint instead of a numeric one.
 */
internal sealed interface LocalMessageFingerprint {
    data class Remote(val fingerprint: MessageFingerprint) : LocalMessageFingerprint

    data class Local(val value: String) : LocalMessageFingerprint
}
