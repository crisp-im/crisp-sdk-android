package im.crisp.sdk.internal.notification

import im.crisp.sdk.internal.bridge.IdentityPromptState
import im.crisp.sdk.internal.notification.CrispNotificationPrompt.Decision
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.junit.Assert.assertEquals
import org.junit.Test

class CrispNotificationPromptTest {
    private val time = TestTimeSource()
    private val prompt = CrispNotificationPrompt(time)

    private val empty = IdentityPromptState()
    private val sent = IdentityPromptState(hasUserMessage = true)
    private val identityMessage = IdentityPromptState(hasUserMessage = true, hasIdentityMessage = true)
    private val identityForm = IdentityPromptState(hasUserMessage = true, isIdentityFormShown = true)

    @Test fun `shown below the identity message right away, even without message sent`() {
        assertEquals(Decision.SHOW_BELOW_IDENTITY, prompt.decide(IdentityPromptState(hasIdentityMessage = true)))
    }

    @Test fun `nothing more once shown`() {
        assertEquals(Decision.DONE, prompt.decide(identityMessage.copy(hasPrompt = true)))
    }

    @Test fun `not shown on load without identity message, even after messages sent before`() {
        assertEquals(Decision.WAIT, prompt.decide(sent))
        time += 10.seconds
        assertEquals(Decision.WAIT, prompt.decide(sent))
    }

    @Test fun `shown standalone once a message sent is not followed by the identity request`() {
        prompt.onMessageSent()
        time += CrispNotificationPrompt.IDENTITY_DELAY - 1.seconds
        assertEquals(Decision.WAIT, prompt.decide(sent))
        time += 1.seconds
        assertEquals(Decision.SHOW_STANDALONE, prompt.decide(sent))
    }

    @Test fun `shown below the identity message once shown after a message sent`() {
        prompt.onMessageSent()
        time += 5.seconds
        assertEquals(Decision.SHOW_BELOW_IDENTITY, prompt.decide(identityMessage))
    }

    @Test fun `not shown standalone without message sent by the user`() {
        prompt.onMessageSent()
        time += 10.seconds
        assertEquals(Decision.WAIT, prompt.decide(empty))
    }

    @Test fun `not shown while the identity form is shown, then below the identity message once closed`() {
        prompt.onMessageSent()
        time += 5.seconds
        assertEquals(Decision.WAIT, prompt.decide(identityForm))
        time += 30.seconds
        assertEquals(Decision.WAIT, prompt.decide(identityForm))
        // Closed: its `$identity` message is shown, maybe a moment later
        assertEquals(Decision.WAIT, prompt.decide(sent))
        assertEquals(Decision.SHOW_BELOW_IDENTITY, prompt.decide(identityMessage))
    }

    @Test fun `shown standalone once the identity form is submitted`() {
        assertEquals(Decision.WAIT, prompt.decide(identityForm))
        // Submitted: no `$identity` message
        assertEquals(Decision.WAIT, prompt.decide(sent))
        time += CrispNotificationPrompt.FORM_CLOSED_DELAY
        assertEquals(Decision.SHOW_STANDALONE, prompt.decide(sent))
    }

    @Test fun `a message sent while the identity form is shown waits for it`() {
        assertEquals(Decision.WAIT, prompt.decide(identityForm))
        prompt.onMessageSent()
        time += 10.seconds
        assertEquals(Decision.WAIT, prompt.decide(identityForm))
    }

    @Test fun `shown before, shown again right away below the identity message`() {
        assertEquals(Decision.SHOW_BELOW_IDENTITY, prompt.decide(identityMessage, wasShown = true))
    }

    @Test fun `shown before, shown again standalone once the identity message could be restored`() {
        assertEquals(Decision.WAIT, prompt.decide(empty, wasShown = true))
        time += CrispNotificationPrompt.RESTORE_DELAY
        assertEquals(Decision.SHOW_STANDALONE, prompt.decide(empty, wasShown = true))
    }

    @Test fun `shown before, not shown again while the identity form is shown`() {
        time += 10.seconds
        assertEquals(Decision.WAIT, prompt.decide(identityForm, wasShown = true))
    }
}
