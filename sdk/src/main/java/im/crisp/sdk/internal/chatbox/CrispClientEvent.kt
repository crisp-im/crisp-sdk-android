package im.crisp.sdk.internal.chatbox

import im.crisp.sdk.model.Message

/** Web client events forwarded to the attached controller, see [CrispEventCallbacks][im.crisp.sdk.api.controller.chatbox.CrispEventCallbacks]. */
internal sealed interface CrispClientEvent {
    /** The page is ready with [sessionId], emitted again if the Web client resets it in the page. */
    data class Loaded(val sessionId: String) : CrispClientEvent
    data class MessageSent(val message: Message): CrispClientEvent
    data class MessageReceived(val message: Message): CrispClientEvent
}
