package im.crisp.sdk.internal.chatbox

import im.crisp.sdk.internal.bridge.CrispClientBridge
import im.crisp.sdk.internal.bridge.types.Company
import im.crisp.sdk.internal.bridge.types.SessionEventColor
import im.crisp.sdk.internal.bridge.types.Tuple
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * A setter call made through [CrispSessionController][im.crisp.sdk.api.CrispSessionController],
 * waiting to be dispatched.
 */
@Serializable
internal sealed interface CrispSessionCall {
    /**
     * Whether making this call twice has the same effect as once,
     * so that it can be made again when interrupted before the page answers.
     * Otherwise, it is made at most once, e.g. not to push an event twice.
     */
    val isIdempotent: Boolean get() = true

    suspend fun run(bridge: CrispClientBridge)

    @Serializable
    @SerialName("user.setEmail")
    data class SetEmail(val email: String, val signature: String?) : CrispSessionCall {
        override suspend fun run(bridge: CrispClientBridge) = bridge.setUserEmail(email, signature)
    }

    @Serializable
    @SerialName("user.setPhone")
    data class SetPhone(val phone: String) : CrispSessionCall {
        override suspend fun run(bridge: CrispClientBridge) = bridge.setUserPhone(phone)
    }

    @Serializable
    @SerialName("user.setNickname")
    data class SetNickname(val nickname: String) : CrispSessionCall {
        override suspend fun run(bridge: CrispClientBridge) = bridge.setUserNickname(nickname)
    }

    @Serializable
    @SerialName("user.setAvatar")
    data class SetAvatar(val url: String) : CrispSessionCall {
        override suspend fun run(bridge: CrispClientBridge) = bridge.setUserAvatar(url)
    }

    @Serializable
    @SerialName("user.setCompany")
    data class SetCompany(val name: String, val company: Company) : CrispSessionCall {
        override suspend fun run(bridge: CrispClientBridge) = bridge.setUserCompany(name, company)
    }

    @Serializable
    @SerialName("session.setData")
    data class SetData(val data: Map<String, JsonPrimitive>) : CrispSessionCall {
        override suspend fun run(bridge: CrispClientBridge) =
            bridge.setSessionData(data.map { (key, value) -> Tuple(key, value) })
    }

    @Serializable
    @SerialName("session.setSegments")
    data class SetSegments(val segments: List<String>, val overwrite: Boolean) : CrispSessionCall {
        override suspend fun run(bridge: CrispClientBridge) =
            bridge.setSessionSegments(segments, overwrite)
    }

    @Serializable
    @SerialName("session.pushEvent")
    data class PushEvent(
        val name: String,
        val data: Map<String, JsonPrimitive>?,
        val color: SessionEventColor?
    ) : CrispSessionCall {
        override val isIdempotent: Boolean get() = false

        override suspend fun run(bridge: CrispClientBridge) =
            bridge.setSessionEvent(name, data, color)
    }
}

/**
 * The calls waiting to be dispatched to a session, deduplicated as they are added, in the order they were made.
 *
 * [reset] is requested before [calls], which are made for the new session.
 */
@Serializable
internal data class CrispSessionCalls(
    val reset: Boolean = false,
    val calls: List<CrispSessionCall> = emptyList()
) {
    val isEmpty: Boolean get() = !reset && calls.isEmpty()

    /**
     * Adds [call], replacing or merging the previous one of the same kind:
     * - user setters and overwriting segments replace it,
     * - data and added segments merge into it,
     * - events are all kept.
     *
     * The resulting call is moved last.
     */
    operator fun plus(call: CrispSessionCall): CrispSessionCalls {
        val merged = when (call) {
            is CrispSessionCall.PushEvent -> return copy(calls = calls + call)
            is CrispSessionCall.SetData -> {
                val previous = calls.firstNotNullOfOrNull { it as? CrispSessionCall.SetData }
                CrispSessionCall.SetData(previous?.data.orEmpty() + call.data)
            }

            is CrispSessionCall.SetSegments -> {
                val previous = calls.firstNotNullOfOrNull { it as? CrispSessionCall.SetSegments }
                if (call.overwrite || previous == null) {
                    call
                } else {
                    // Adding to overwritten segments overwrites them with both
                    CrispSessionCall.SetSegments(
                        (previous.segments + call.segments).distinct(),
                        previous.overwrite
                    )
                }
            }

            else -> call
        }
        return copy(calls = calls.filterNot { it::class == call::class } + merged)
    }

    /**
     * Takes the next call to dispatch out, unless a reset is requested first.
     *
     * Taken out before being dispatched, so that the calls made meanwhile are queued after it,
     * deduplicated with each other but not with it.
     */
    fun takeNext(): CrispSessionCalls = if (reset) this else copy(calls = calls.drop(1))

    /**
     * Puts back [call], taken out but interrupted before the page answered (e.g. the page was unloaded),
     * before the calls made meanwhile, which replace or merge into it.
     * Dropped if a reset was requested meanwhile.
     *
     * Only for [idempotent][CrispSessionCall.isIdempotent] calls, as the page may have made it.
     */
    fun putBack(call: CrispSessionCall): CrispSessionCalls =
        if (reset) this else calls.fold(CrispSessionCalls(calls = listOf(call))) { pending, next -> pending + next }
}
