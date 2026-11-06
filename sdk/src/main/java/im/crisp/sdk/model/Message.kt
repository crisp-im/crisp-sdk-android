package im.crisp.sdk.model

import java.net.URL
import java.util.Date

data class Message(
    val isMe: Boolean,
    val content: MessageContent,
    val origin: Origin,
    val timestamp: Date,
    val fingerprint: Long,
    val from: Sender,
    val user: User? = null
) {
    enum class Origin {
        LOCAL,
        NETWORK,
        UPDATE
    }

    enum class Sender {
        USER,
        OPERATOR
    }

    data class User(
        val nickname: String? = null,
        val userId: String? = null,
        val avatar: URL? = null
    )
}
