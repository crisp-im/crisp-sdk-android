package im.crisp.sdk.internal.chatbox

import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.ValueCallback

internal sealed interface CrispChatboxEvent {
    data object Close: CrispChatboxEvent
    data class OpenExternalUrl(val url: String): CrispChatboxEvent
    data class Upload(val filePathCallback: ValueCallback<Array<out Uri?>?>?): CrispChatboxEvent
    /** [x] and [y] are in CSS pixels, relative to the Web content. */
    data class ShowCopyMenu(val text: String, val x: Double, val y: Double): CrispChatboxEvent
    data class Alert(val message: String): CrispChatboxEvent
    data class Permission(val request: PermissionRequest): CrispChatboxEvent
    data class PermissionCanceled(val request: PermissionRequest?): CrispChatboxEvent
}