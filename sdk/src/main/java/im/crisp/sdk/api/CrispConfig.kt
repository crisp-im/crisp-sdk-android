package im.crisp.sdk.api

import kotlinx.serialization.Serializable

/**
 * The chatbox to load: the website of [websiteId], with the user session of [tokenId] if any.
 */
// copy() is internal like the primary constructor, not to expose the internal properties
@ConsistentCopyVisibility
data class CrispConfig internal constructor(
    val websiteId: String,
    val tokenId: String?,
    // Restores a session, e.g. after an upgrade from a previous SDK version or from a notification
    internal val sessionId: String? = null,
    // Set by the component hosting the chatbox
    internal val presentation: Presentation
) {
    @JvmOverloads
    constructor(websiteId: String, tokenId: String? = null) : this(
        websiteId = websiteId,
        tokenId = tokenId,
        presentation = Presentation.default
    )

    /**
     * The session calls are made for: the [websiteId] and [tokenId] tuple.
     * Other properties (e.g. [presentation]) do not change the session.
     */
    internal val session: CrispSession get() = CrispSession(websiteId, tokenId)
}

/**
 * The session of a [CrispConfig].
 *
 * @see [CrispConfig.session].
 */
@Serializable
internal data class CrispSession(val websiteId: String, val tokenId: String?)

internal enum class Presentation {
    default,
    sheet
}
