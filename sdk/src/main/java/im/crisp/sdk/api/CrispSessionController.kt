package im.crisp.sdk.api

import android.content.Context
import im.crisp.sdk.internal.bridge.types.sessionValuesToJson
import im.crisp.sdk.internal.bridge.types.toBridge
import im.crisp.sdk.internal.chatbox.CrispSessionCall
import im.crisp.sdk.internal.chatbox.CrispControllerDispatcher
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.log
import im.crisp.sdk.model.Company
import im.crisp.sdk.model.SessionEventColor

/**
 * Sets up the session of [config][CrispConfig] (its `websiteId` and `tokenId`) without any chatbox shown,
 * e.g. right after the user logs in, grouped like the Web client methods: [session][Session] and [user][User].
 *
 * Calls are dispatched right away to a chatbox ready with the same `websiteId` and `tokenId`.
 * Otherwise, they are saved, surviving the chatbox being closed and the app being killed,
 * and dispatched to the next chatbox loading it, before it becomes ready.
 * [CrispChatboxController] calls made afterward on that chatbox are made once they are dispatched.
 *
 * Calls waiting to be dispatched are deduplicated:
 * - [user][User] setters and [session.setSegments][Session.setSegments] overwriting segments replace the previous call,
 * - [session.setData][Session.setData] and [session.setSegments][Session.setSegments] adding segments
 *   merge into the previous call,
 * - [session.pushEvent][Session.pushEvent] events are all pushed,
 * - [session.reset][Session.reset] drops all of them.
 *
 * Calls return once saved, from any thread. Calls failing once dispatched are logged and dropped.
 * Interrupted before the chatbox answers (e.g. reloaded), they are dispatched again once reloaded,
 * except [session.pushEvent][Session.pushEvent], made at most once not to push an event twice.
 * The call being dispatched when the app is killed is lost.
 *
 * Only setters are available: getters, UI methods and [events][CrispEventCallbacks] need a running chatbox,
 * see [CrispChatboxController].
 *
 * Cheap to create: all instances share the calls of their session.
 */
class CrispSessionController internal constructor(
    private val dispatcher: CrispControllerDispatcher,
    private val config: CrispConfig
) {
    constructor(
        context: Context,
        config: CrispConfig
    ) : this(CrispControllerDispatcher.get(context), config)

    val session: Session = Session()
    val user: User = User()

    private fun add(name: String, call: CrispSessionCall) {
        if (!warnIfWebsiteMissing(name)) {
            dispatcher.add(config.session, call)
        }
    }

    private fun warnIfWebsiteMissing(name: String): Boolean =
        config.websiteId.isBlank().also { isMissing ->
            if (isMissing) {
                log(Level.WARN, LOG_TAG, "Cannot call '$name': the websiteId is missing")
            }
        }

    inner class Session internal constructor() {
        /**
         * Sets session data, merged with the data set before it is dispatched.
         * Values must be [String], [Boolean] or [Number], others are ignored.
         */
        fun setData(data: Map<String, Any?>) =
            add("session.setData", CrispSessionCall.SetData(sessionValuesToJson(data, LOG_TAG)))

        /**
         * Adds [segments] to the session, or replaces the existing ones if [overwrite] is `true`.
         */
        @JvmOverloads
        fun setSegments(segments: List<String>, overwrite: Boolean = false) =
            add("session.setSegments", CrispSessionCall.SetSegments(segments, overwrite))

        /**
         * Pushes an event to the session. [data] values must be [String], [Boolean] or [Number],
         * others are ignored.
         *
         * Made at most once: if the chatbox is reloaded before answering, the event is not pushed again,
         * as it may have been.
         */
        @JvmOverloads
        fun pushEvent(
            name: String,
            data: Map<String, Any?>? = null,
            color: SessionEventColor? = null
        ) = add(
            "session.pushEvent",
            CrispSessionCall.PushEvent(
                name = name,
                data = data?.let { sessionValuesToJson(it, LOG_TAG) },
                color = color?.toBridge()
            )
        )

        /**
         * Resets the session: the user starts over with a new conversation.
         * Drops the calls not dispatched yet, those made afterward are dispatched to the new session.
         *
         * Done right away if a chatbox is ready with it, reloading it.
         * Otherwise, done when the next chatbox loading it is, before it becomes ready.
         */
        fun reset() {
            if (!warnIfWebsiteMissing("session.reset")) {
                dispatcher.reset(config.session)
            }
        }
    }

    inner class User internal constructor() {
        /**
         * Sets the user email.
         *
         * @param signature HMAC-SHA256 of [email] with your website secret, to verify the user identity.
         */
        @JvmOverloads
        fun setEmail(email: String, signature: String? = null) =
            add("user.setEmail", CrispSessionCall.SetEmail(email, signature))

        /** Sets the user phone. */
        fun setPhone(phone: String) = add("user.setPhone", CrispSessionCall.SetPhone(phone))

        /** Sets the user nickname. */
        fun setNickname(nickname: String) =
            add("user.setNickname", CrispSessionCall.SetNickname(nickname))

        /** Sets the user avatar URL. */
        fun setAvatar(url: String) = add("user.setAvatar", CrispSessionCall.SetAvatar(url))

        /** Sets the user [company][Company]. */
        fun setCompany(company: Company) =
            add("user.setCompany", CrispSessionCall.SetCompany(company.name, company.toBridge()))
    }

    private companion object {
        private const val LOG_TAG = "CrispSessionController"
    }
}
