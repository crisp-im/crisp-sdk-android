package im.crisp.sdk.internal.webview

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.ConsoleMessage.MessageLevel.DEBUG
import android.webkit.ConsoleMessage.MessageLevel.ERROR
import android.webkit.ConsoleMessage.MessageLevel.LOG
import android.webkit.ConsoleMessage.MessageLevel.TIP
import android.webkit.ConsoleMessage.MessageLevel.WARNING
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import im.crisp.sdk.internal.chatbox.CrispChatboxStore
import im.crisp.sdk.internal.chatbox.CrispChatboxEvent
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.log

internal class CrispWebChromeClient(
    private val store: CrispChatboxStore
): WebChromeClient() {
    companion object {
        private const val LOG_TAG = "CrispWebView"
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        val message = "\"${consoleMessage.message()}\", source: ${consoleMessage.sourceId()} (${consoleMessage.lineNumber()})"
        when (consoleMessage.messageLevel()) {
            ERROR -> log(Level.ERROR, LOG_TAG, message)
            WARNING -> log(Level.WARN, LOG_TAG, message)
            DEBUG -> log(Level.DEBUG, LOG_TAG, message)
            TIP -> log(Level.INFO, LOG_TAG, message)
            LOG -> log(Level.VERBOSE, LOG_TAG, message)
        }
        return true
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<out Uri?>?>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        store.emitEvent(CrispChatboxEvent.Upload(filePathCallback))
        return true
    }

    override fun onJsAlert(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?
    ): Boolean {
        // TODO: is only used for permissions?
        /*message?.let {
            store.emitEvent(CrispChatboxEvent.Alert(it))
        }*/
        result?.confirm()
        return true
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.let {
            store.emitEvent(CrispChatboxEvent.Permission(request))
        }
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest?) {
        store.emitEvent(CrispChatboxEvent.PermissionCanceled(request))
    }
}