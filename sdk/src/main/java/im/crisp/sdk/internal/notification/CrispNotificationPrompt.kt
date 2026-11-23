package im.crisp.sdk.internal.notification

import im.crisp.sdk.internal.bridge.IdentityPromptState
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * When to show the prompt to enable notifications in the chat (see [CrispNotificationSync.promptWhenNeeded]),
 * from the [state][IdentityPromptState] of the page, checked again and again.
 *
 * The identity request of the Web client starts with the same "reply" header as a standalone prompt,
 * so the prompt is shown without it below the `$identity` message, as soon as it is shown:
 * on load, after a message sent, once asked by an operator or once its identity form is closed.
 *
 * Otherwise, the prompt is shown standalone, with the header, once the user sent a message:
 * - [IDENTITY_DELAY] after a message sent, if the Web client did not ask for their identity meanwhile,
 * - or right after its identity form is submitted ([FORM_CLOSED_DELAY],
 *   for its `$identity` message to be shown instead if it was closed).
 *
 * Once shown on the session (`wasShown`, as the `ASKED` status of SDK 2.x),
 * it is shown again right away when the chatbox loads it again, until answered: the page does not keep it, as it is injected.
 * Below the `$identity` message if shown, otherwise standalone,
 * after [RESTORE_DELAY] for the `$identity` message to be restored first.
 *
 * Never while the identity form of the Web client is shown over the chatbox
 * (instead of the `$identity` message, when the website only asks for the email or the phone).
 *
 * The Web client keeps its identity request in the session state (its `identity` intent), restored on load:
 * `"default"` shows the `$identity` message, `"email"` or `"phone"` the form, `false` once given.
 * It is only set 5s after a message sent (see [IDENTITY_DELAY]), so not if the chatbox is closed meanwhile,
 * until the next message sent.
 */
// TODO: listen for the changes of the `identity` intent instead of checking the page again and again,
//  once `crisp-client` sends them, e.g. a `chat:intent:identity` event with its value (and a getter for the load),
//  forwarded by the generated JS (`write_crisp_client_android` in `.mise/lib/web-client.sh`):
//  `"default"` shows the prompt below the `$identity` message, `"email"` or `"phone"` (the form) waits,
//  `false` (given) or not set within IDENTITY_DELAY after a message sent shows it standalone.
//  It would not depend on the markup of the identity form (see `getIdentityPromptState` in `index.html`).
internal class CrispNotificationPrompt(
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    enum class Decision {
        /** Checked again later. */
        WAIT,

        /** Shown already in the page: nothing more to do. */
        DONE,

        /** Below the `$identity` message of the Web client, without header. */
        SHOW_BELOW_IDENTITY,

        /** Standalone, with the header. */
        SHOW_STANDALONE,
    }

    // When the prompt is shown standalone if the Web client does not ask for the identity before
    private var standaloneAt: ComparableTimeMark? = null

    // Whether the identity form of the Web client was shown on the last check
    private var isFormShown = false

    // When the prompt shown before is shown again standalone, once checked first
    private var restoreAt: ComparableTimeMark? = null

    /** The user sent a message, shown standalone after [IDENTITY_DELAY] unless the identity is asked meanwhile. */
    fun onMessageSent() {
        standaloneAt = timeSource.markNow() + IDENTITY_DELAY
    }

    /** What to do in the page of [state], [wasShown] telling whether the prompt was shown on the session before. */
    fun decide(state: IdentityPromptState, wasShown: Boolean = false): Decision = when {
        state.hasPrompt -> Decision.DONE
        state.hasIdentityMessage -> Decision.SHOW_BELOW_IDENTITY
        state.isIdentityFormShown -> {
            isFormShown = true
            standaloneAt = null
            Decision.WAIT
        }
        // Closed (then the `$identity` message is shown) or submitted (then it is not)
        isFormShown -> {
            isFormShown = false
            standaloneAt = timeSource.markNow() + FORM_CLOSED_DELAY
            Decision.WAIT
        }
        wasShown && (restoreAt ?: (timeSource.markNow() + RESTORE_DELAY).also { restoreAt = it }).hasPassedNow() ->
            Decision.SHOW_STANDALONE
        state.hasUserMessage && standaloneAt?.hasPassedNow() == true -> Decision.SHOW_STANDALONE
        else -> Decision.WAIT
    }

    companion object {
        /**
         * How long the identity request of the Web client is waited for after a message sent.
         *
         * `crisp-client` asks for it 5s after a message sent in the page by a user without email nor phone
         * (`ui = 5e3` in `checkUserIdentity`, called by `handleSent`, in `chat-alert-*.js`),
         * if `email_visitors` or `phone_visitors` is set and not asked before, right away if `force_identify` is,
         * or later while a bot is typing. Plus a margin for the dispatch: check it on each `crisp-client` update.
         */
        val IDENTITY_DELAY = 6.seconds

        /** How long the `$identity` message is waited for once the identity form is not shown anymore. */
        val FORM_CLOSED_DELAY = 1.seconds

        /** How long the `$identity` message is waited for before showing again the prompt shown before. */
        val RESTORE_DELAY = 1.seconds

        /** Between two checks of the page. */
        val POLLING = 1.seconds
    }
}
