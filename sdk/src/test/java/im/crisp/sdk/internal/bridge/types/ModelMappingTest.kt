package im.crisp.sdk.internal.bridge.types

import im.crisp.sdk.model.MessageContent
import im.crisp.sdk.model.Picker
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL
import im.crisp.sdk.model.Company as ModelCompany
import im.crisp.sdk.model.Message as ModelMessage

class ModelMappingTest {
    private fun decode(json: String) = bridgeJson.decodeFromString(Message.serializer(), json).toModel()

    @Test fun `text message maps to the public model`() {
        val message = decode(
            """
            {
                "type": "text",
                "content": "Hello Crisp!",
                "from": "user",
                "origin": "local",
                "timestamp": 1700000000000,
                "fingerprint": 123456789,
                "user": {
                    "nickname": "John",
                    "userId": "user_123",
                    "avatar": "https://example.com/avatar.png"
                }
            }
            """.trimIndent()
        )!!

        assertTrue(message.isMe)
        assertEquals(ModelMessage.Sender.USER, message.from)
        assertEquals(ModelMessage.Origin.LOCAL, message.origin)
        assertEquals(1700000000000L, message.timestamp.time)
        assertEquals(123456789L, message.fingerprint)
        assertEquals(MessageContent.TextContent("Hello Crisp!"), message.content)
        assertEquals(
            ModelMessage.User("John", "user_123", URL("https://example.com/avatar.png")),
            message.user
        )
    }

    @Test fun `carousel message maps to the public model`() {
        val message = decode(
            """
            {
                "type": "carousel",
                "content": {
                    "text": "Mobile OS",
                    "targets": [
                        {
                            "title": "Android",
                            "description": "FOSS OS",
                            "image": "https://example.com/android.png",
                            "actions": [{"label": "Open Website", "url": "https://www.android.com/"}]
                        }
                    ]
                },
                "from": "operator",
                "timestamp": 1700000000000,
                "fingerprint": 987654321
            }
            """.trimIndent()
        )!!

        assertFalse(message.isMe)
        assertEquals(ModelMessage.Sender.OPERATOR, message.from)
        // Chat, the default origin, has no public counterpart
        assertEquals(ModelMessage.Origin.NETWORK, message.origin)
        val carousel = (message.content as MessageContent.CarouselContent).carousel
        assertEquals("Mobile OS", carousel.text)
        assertEquals(1, carousel.targets.size)
        assertEquals("Android", carousel.targets[0].title)
        assertEquals(URL("https://www.android.com/"), carousel.targets[0].actions[0].url)
    }

    @Test fun `messages without a public counterpart are dropped`() {
        fun message(type: String, content: String) =
            """{"type":"$type","content":$content,"from":"user","timestamp":1,"fingerprint":1}"""

        assertNull(decode(message("note", "\"internal\"")))
        assertNull(decode(message("event", """{"namespace":"state:resolved"}""")))
        assertNull(decode(message("file", """{"name":"a.pdf","type":"application/pdf","url":"invalid"}""")))
    }

    @Test fun `invalid avatars are ignored`() {
        val message = decode(
            """{"type":"text","content":"a","from":"user","timestamp":1,"fingerprint":1,"user":{"avatar":"not a url"}}"""
        )!!

        assertNull(message.user?.avatar)
    }

    @Test fun `picker round trips`() {
        val pickerContent = MessageContent.PickerContent(
            Picker(
                id = "picker_1",
                text = "Pick one",
                choices = listOf(
                    Picker.Choice(
                        label = "Docs",
                        selected = false,
                        value = "docs",
                        action = Picker.Choice.Action(
                            Picker.Choice.Action.Type.LINK,
                            URL("https://crisp.chat/docs"),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(pickerContent, pickerContent.toBridge().toModel())
    }

    @Test fun `company maps both ways`() {
        val company = ModelCompany(
            name = "Crisp",
            url = "https://crisp.chat",
            description = "Customer messaging",
            employment = ModelCompany.Employment(role = "Software Engineer", title = "CTO"),
            geolocation = ModelCompany.Geolocation(country = "FR"),
        )

        // The name is passed apart to the Web client, which returns it within the company
        assertEquals(company, company.toBridge().copy(name = company.name).toModel())
        assertNull(Company(name = null, url = "https://crisp.chat").toModel())
    }

    @Test fun `session values`() {
        assertEquals(JsonPrimitive("a"), sessionValueToJson("a"))
        assertEquals(JsonPrimitive(1), sessionValueToJson(1))
        assertEquals(JsonPrimitive(true), sessionValueToJson(true))
        assertNull(sessionValueToJson(null))
        assertNull(sessionValueToJson(listOf("a")))

        assertEquals("a", sessionValueFromJson(JsonPrimitive("a")))
        assertEquals("1", sessionValueFromJson(JsonPrimitive("1")))
        assertEquals(1L, sessionValueFromJson(JsonPrimitive(1)))
        assertEquals(1.5, sessionValueFromJson(JsonPrimitive(1.5)))
        assertEquals(false, sessionValueFromJson(JsonPrimitive(false)))
        assertNull(sessionValueFromJson(JsonNull))
        assertEquals("""{"a":1}""", sessionValueFromJson(buildJsonObject { put("a", 1) }))
    }
}
