package im.crisp.sdk.internal.bridge.js

internal sealed class JSEventException(message: String) : Exception(message) {
    /** The message body wasn't a String (the JS side always sends `JSON.stringify(payload)`). */
    class BadSerialization : JSEventException("JS message body should be serialized as a String")

    class DecodingException(message: String) : JSEventException(message)
}
