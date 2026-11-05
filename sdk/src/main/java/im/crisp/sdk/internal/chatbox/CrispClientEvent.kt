package im.crisp.sdk.internal.chatbox

import im.crisp.sdk.model.Message

/** Web client events forwarded to the attached controller, see [CrispEventCallbacks][im.crisp.sdk.api.CrispEventCallbacks]. */
internal sealed interface CrispClientEvent {
    data object Loaded : CrispClientEvent
    data class MessageSent(val message: Message): CrispClientEvent
    data class MessageReceived(val message: Message): CrispClientEvent
}
