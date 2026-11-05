package im.crisp.sdk.internal.chatbox

internal sealed interface CrispChatboxState {
    data object Loading: CrispChatboxState
    data object Ready: CrispChatboxState
    data class Error(val error: Throwable): CrispChatboxState
}