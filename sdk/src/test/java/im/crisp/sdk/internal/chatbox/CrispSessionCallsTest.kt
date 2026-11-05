package im.crisp.sdk.internal.chatbox

import im.crisp.sdk.internal.bridge.types.Company
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class CrispSessionCallsTest {
    private fun calls(vararg calls: CrispSessionCall) =
        calls.fold(CrispSessionCalls()) { pending, call -> pending + call }

    private fun event(name: String) = CrispSessionCall.PushEvent(name, null, null)

    @Test fun `user setters replace the previous call`() {
        val pending = calls(
            CrispSessionCall.SetEmail("a@example.com", null),
            CrispSessionCall.SetNickname("A"),
            CrispSessionCall.SetEmail("b@example.com", "signature"),
            CrispSessionCall.SetPhone("1"),
            CrispSessionCall.SetPhone("2"),
            CrispSessionCall.SetAvatar("https://example.com/a.png"),
            CrispSessionCall.SetAvatar("https://example.com/b.png"),
            CrispSessionCall.SetCompany("A", Company()),
            CrispSessionCall.SetCompany("B", Company(url = "https://b.com")),
        )

        assertEquals(
            listOf(
                CrispSessionCall.SetNickname("A"),
                CrispSessionCall.SetEmail("b@example.com", "signature"),
                CrispSessionCall.SetPhone("2"),
                CrispSessionCall.SetAvatar("https://example.com/b.png"),
                CrispSessionCall.SetCompany("B", Company(url = "https://b.com")),
            ),
            pending.calls,
        )
    }

    @Test fun `data merges into the previous call`() {
        val pending = calls(
            CrispSessionCall.SetData(mapOf("plan" to JsonPrimitive("free"), "seats" to JsonPrimitive(1))),
            CrispSessionCall.SetData(mapOf("plan" to JsonPrimitive("pro"), "trial" to JsonPrimitive(false))),
        )

        assertEquals(
            listOf(
                CrispSessionCall.SetData(
                    mapOf(
                        "plan" to JsonPrimitive("pro"),
                        "seats" to JsonPrimitive(1),
                        "trial" to JsonPrimitive(false),
                    )
                )
            ),
            pending.calls,
        )
    }

    @Test fun `added segments merge into the previous call`() {
        val pending = calls(
            CrispSessionCall.SetSegments(listOf("a", "b"), overwrite = false),
            CrispSessionCall.SetSegments(listOf("b", "c"), overwrite = false),
        )

        assertEquals(listOf(CrispSessionCall.SetSegments(listOf("a", "b", "c"), overwrite = false)), pending.calls)
    }

    @Test fun `segments added to overwritten ones still overwrite`() {
        val pending = calls(
            CrispSessionCall.SetSegments(listOf("a"), overwrite = true),
            CrispSessionCall.SetSegments(listOf("b"), overwrite = false),
        )

        assertEquals(listOf(CrispSessionCall.SetSegments(listOf("a", "b"), overwrite = true)), pending.calls)
    }

    @Test fun `overwritten segments replace the previous call`() {
        val pending = calls(
            CrispSessionCall.SetSegments(listOf("a"), overwrite = false),
            CrispSessionCall.SetSegments(listOf("b"), overwrite = true),
        )

        assertEquals(listOf(CrispSessionCall.SetSegments(listOf("b"), overwrite = true)), pending.calls)
    }

    @Test fun `events are all kept`() {
        val pending = calls(event("a"), event("a"), event("b"))

        assertEquals(listOf(event("a"), event("a"), event("b")), pending.calls)
    }

    @Test fun `the next call is taken out, unless a reset is requested first`() {
        val pending = calls(event("a"), event("b"))

        assertEquals(listOf(event("b")), pending.takeNext().calls)
        assertEquals(CrispSessionCalls(reset = true), CrispSessionCalls(reset = true).takeNext())
    }

    @Test fun `only events are not idempotent`() {
        assertEquals(false, event("a").isIdempotent)
        assertEquals(
            true,
            listOf(
                CrispSessionCall.SetEmail("a@example.com", null),
                CrispSessionCall.SetPhone("1"),
                CrispSessionCall.SetNickname("A"),
                CrispSessionCall.SetAvatar("https://example.com/a.png"),
                CrispSessionCall.SetCompany("A", Company()),
                CrispSessionCall.SetData(mapOf("plan" to JsonPrimitive("pro"))),
                CrispSessionCall.SetSegments(listOf("vip"), overwrite = false),
            ).all { it.isIdempotent },
        )
    }

    @Test fun `a call put back is made first, replaced or merged into by those made meanwhile`() {
        val pending = calls(
            event("a"),
            CrispSessionCall.SetNickname("Jane"),
            CrispSessionCall.SetData(mapOf("seats" to JsonPrimitive(3))),
        )

        assertEquals(
            listOf(CrispSessionCall.SetPhone("1"), event("a"), CrispSessionCall.SetNickname("Jane")),
            calls(event("a"), CrispSessionCall.SetNickname("Jane")).putBack(CrispSessionCall.SetPhone("1")).calls,
        )
        assertEquals(
            listOf(
                event("a"),
                CrispSessionCall.SetNickname("Jane"),
                CrispSessionCall.SetData(mapOf("plan" to JsonPrimitive("pro"), "seats" to JsonPrimitive(3))),
            ),
            pending.putBack(CrispSessionCall.SetData(mapOf("plan" to JsonPrimitive("pro")))).calls,
        )
        assertEquals(pending.calls, pending.putBack(CrispSessionCall.SetNickname("John")).calls)
    }

    @Test fun `a call put back after a reset request is dropped`() {
        val pending = CrispSessionCalls(reset = true) + event("b")

        assertEquals(pending, pending.putBack(CrispSessionCall.SetNickname("John")))
    }
}
