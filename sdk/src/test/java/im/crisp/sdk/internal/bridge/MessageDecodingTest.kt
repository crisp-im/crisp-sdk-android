package im.crisp.sdk.internal.bridge

import im.crisp.sdk.internal.bridge.js.JSEventBus
import im.crisp.sdk.internal.bridge.js.JSEventException
import im.crisp.sdk.internal.bridge.js.JSEventHandler
import im.crisp.sdk.internal.bridge.types.ConversationMessageContent as C
import im.crisp.sdk.internal.bridge.types.LocalMessage
import im.crisp.sdk.internal.bridge.types.LocalMessageFingerprint
import im.crisp.sdk.internal.bridge.types.Message
import im.crisp.sdk.internal.bridge.types.MessageSender
import im.crisp.sdk.internal.bridge.types.Origin
import im.crisp.sdk.internal.bridge.types.bridgeJson
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MessageDecodingTest {
    private fun decode(json: String) = bridgeJson.decodeFromString(Message.serializer(), json)

    private fun message(type: String, content: String, extra: String = "") =
        """{"type":"$type","content":$content,"from":"user","timestamp":1700000000000,"fingerprint":42 $extra}"""

    @Test fun `text without preview`() {
        val m = decode(message("text", "\"hello\""))
        assertEquals(C.TextContent("hello"), m.content)
        assertEquals(Origin.CHAT, m.origin) // default
        assertEquals(MessageSender.USER, m.from)
        assertEquals(42L, m.fingerprint.rawValue)
        assertFalse(m.read)
    }

    @Test fun `content shape follows the sibling type`() {
        val file = decode(message("file", """{"name":"a.pdf","type":"application/pdf","url":"https://x/a.pdf"}"""))
        assertEquals(C.FileContent("a.pdf", "application/pdf", "https://x/a.pdf"), file.content)

        val note = decode(message("note", "\"internal\""))
        assertEquals(C.NoteContent("internal"), note.content)

        val event = decode(message("event", """{"namespace":"state:resolved","text":"done"}"""))
        assertEquals(C.EventContent(C.EventContent.EventType.StateResolved, "done"), event.content)

        val unknown = decode(message("event", """{"namespace":"foo:bar"}"""))
        assertEquals(C.EventContent(C.EventContent.EventType.Unknown("foo:bar"), null), unknown.content)
    }

    @Test fun `text with preview becomes an attachment`() {
        val preview = """,
            "preview":[{"title":"T","website":"w.com","url":"https://w.com","preview":{"image":"https://w.com/i.png","excerpt":"e"}}]"""
        val m = decode(message("text", "\"see this\"", preview))
        val content = m.content as C.TextContentWithAttachment
        assertEquals("see this", content.text)
        assertEquals(C.AttachmentPreview("e", "https://w.com/i.png"), content.preview)
    }

    @Test fun `video host upgrades preview to video attachment`() {
        val preview = """,
            "preview":[{"title":"T","website":"yt","url":"https://youtube.com/watch?v=1",
                "preview":{"image":"https://i/i.png","embed":"https://www.youtube.com/embed/1"}}]"""
        assertTrue(decode(message("text", "\"v\"", preview)).content is C.TextContentWithVideoAttachment)
    }

    @Test fun `malformed preview is ignored`() {
        val m = decode(message("text", "\"hi\"", ""","preview":"nope""""))
        assertEquals(C.TextContent("hi"), m.content)
    }

    @Test fun `read is truthy unless absent null or false`() {
        assertFalse(decode(message("text", "\"a\"", ""","read":null""")).read)
        assertFalse(decode(message("text", "\"a\"", ""","read":false""")).read)
        assertTrue(decode(message("text", "\"a\"", ""","read":"chat"""")).read)
        assertTrue(decode(message("text", "\"a\"", ""","read":0""")).read)
    }

    @Test fun `missing type fails`() {
        try {
            decode("""{"content":"x","from":"user","timestamp":1,"fingerprint":1}""")
            fail()
        } catch (_: SerializationException) {}
    }

    @Test fun `round trip keeps type content and preview`() {
        val original = decode(
            message(
                "text", "\"see\"",
                ""","preview":[{"title":"T","website":"w","url":"https://w","preview":{"image":"https://w/i.png"}}]""",
            ),
        )
        val again = decode(bridgeJson.encodeToString(Message.serializer(), original))
        assertEquals(original, again)
    }

    @Test fun `local message fingerprint can be numeric or string`() {
        fun local(fp: String) = bridgeJson.decodeFromString(
            LocalMessage.serializer(),
            """{"type":"text","content":"x","from":"user","timestamp":1,"fingerprint":$fp,
                  "metas":{"is_new":true},"sending":true}""",
        )
        assertEquals(LocalMessageFingerprint.Remote(im.crisp.sdk.internal.bridge.types.MessageFingerprint(7)), local("7").fingerprint)
        assertEquals(LocalMessageFingerprint.Local("abc"), local("\"abc\"").fingerprint)
        assertEquals(true, local("7").metas?.isNew)
    }

    @Test fun `event bus reports bad bodies without throwing`() {
        val bus = JSEventBus()
        var error: JSEventException? = null
        bus.onError { _, e -> error = e }
        bus.register("onMessageSent", JSEventHandler.of(Message.serializer()) { fail("not called") })

        bus.dispatch("onMessageSent", 123) // not a String
        assertTrue(error is JSEventException.BadSerialization)

        bus.dispatch("onMessageSent", "{}")
        assertTrue(error is JSEventException.DecodingException)
    }
}
