package im.crisp.sdk.internal.notification

import im.crisp.sdk.api.CrispConfig
import kotlinx.serialization.Serializable

/**
 * The notification state of a website, see [CrispNotificationStore].
 *
 * Notifications are opted-in per website ([enabled]),
 * as they need the app to set up Firebase and the website to be set up for them in the Crisp Dashboard.
 *
 * The Web client keeps one session per website and `tokenId` in the WebView storage,
 * and mirrors the last one saved under the website key, see `crisp-library-client` `Session`.
 * So the [session] recorded is the last one a chatbox of the website loaded,
 * which the Web client restores when loading its `tokenId` again.
 */
@Serializable
internal data class CrispNotificationRecord(
    val enabled: Boolean = false,
    val session: CrispNotificationSession? = null,
)

/**
 * The last session of a website loaded by a chatbox,
 * with the FID or legacy FCM token registered on it if any.
 *
 * The Crisp servers keep the FIDs or legacy FCM tokens per session (`notification_ids`),
 * and only unregister them from the session joined by the socket sending `notification:provider:unregister`.
 * So a [notificationId] must be unregistered from the session it was registered on,
 * which must then be loaded again, see [CrispNotificationPolicy].
 */
@Serializable
internal data class CrispNotificationSession(
    val sessionId: String,
    /** The `tokenId` the session was loaded with, to load it again. */
    val tokenId: String? = null,
    /** The FID or legacy FCM token registered on this session, until unregistered. */
    val notificationId: String? = null,
    /**
     * Whether this session was reset through [CrispSessionController][im.crisp.sdk.api.controller.CrispSessionController]
     * (e.g. on logout) while no chatbox was ready with it.
     *
     * Its notifications are not shown, nor do they open the chatbox,
     * until its `notificationId` is unregistered once a chatbox loads the website again.
     */
    val revoked: Boolean = false,
    /** Whether the user answered the in-chat prompt to enable notifications on this session. */
    val promptAnswered: Boolean = false,
    /**
     * Whether the in-chat prompt was shown on this session, to show it again right away until answered,
     * as SDK 2.x did (its `ASKED` status), see [CrispNotificationPrompt].
     */
    val promptShown: Boolean = false,
)

/** What to do once a chatbox joined a session, see [CrispNotificationPolicy.onSessionLoaded]. */
internal data class CrispNotificationPlan(
    val record: CrispNotificationRecord,
    /** The `notificationId` to unregister from the joined session first, if any. */
    val unregister: String? = null,
    /** The `notificationId` to register on the joined session afterwards, if any. */
    val register: String? = null,
    /**
     * A `notificationId` registered on a previous session that cannot be reached anymore,
     * logged as left on it.
     */
    val unreachable: CrispNotificationSession? = null,
)

// TODO: ask the Crisp servers to make the handover and the revocation unnecessary.
//  They only unregister a token from the session joined by the socket sending `notification:provider:unregister`,
//  hence loading the previous session again first
//  (an extra page load each time the `tokenId` changes, e.g. on login),
//  the revoked sessions and the spacing of updates (CrispNotificationSpacing).
//  Either would remove them:
//  - an endpoint unregistering a token from a given `session_id` (e.g. authenticated by the session),
//  - or the servers dropping a token from the other sessions of a website once registered on another one.
/**
 * The decisions on notifications, pure so that they can be tested,
 * see [CrispNotificationSync] for when they are made.
 *
 * Safety rules, not to notify a user of the conversations of another one using the same device:
 * - a notification never chooses the session: its `session_id` is only checked against the recorded session,
 *   which is loaded again from its `websiteId` and `tokenId`, never by injecting the `session_id`,
 * - a `notificationId` is unregistered from the session it was registered on,
 *   by loading that session again first ([handoverFrom], best effort) or before it is reset ([onBeforeReset]),
 * - a revoked or disabled session is neither shown nor opened, see [activeSession].
 */
internal object CrispNotificationPolicy {
    /**
     * The recorded session to hand the device over from before loading [config][CrispConfig]:
     * loaded again first, to unregister its `notificationId` there,
     * as a chatbox of another `tokenId` replaces it (the Web client then restores another session).
     *
     * Not needed for the same `tokenId`, the Web client restores the recorded session,
     * whose `notificationId` is unregistered there (e.g. before resetting it if revoked).
     *
     * Even if revoked: its `notificationId` is left until unregistered,
     * while its reset stays requested for its `tokenId` (not made during the handover).
     * The session of the pre-3.x SDK not saved by the Web client yet is injected,
     * see [CrispNotificationSync.handoverConfig].
     */
    fun handoverFrom(record: CrispNotificationRecord, config: CrispConfig): CrispNotificationSession? =
        record.session?.takeIf { it.notificationId != null && it.tokenId != config.tokenId }

    /**
     * Once a chatbox loaded with [tokenId] joined [sessionId], the token to unregister or register on it.
     *
     * [deviceToken] is the FID or FCM token of the device to register,
     * if notifications can be shown (enabled, Firebase set up and allowed by the user),
     * `null` to unregister the registered one.
     *
     * A recorded session that is not the joined one cannot be reached anymore
     * (it is not the one a handover would load, so its storage expired or was cleared,
     * or it was replaced, e.g. purged).
     * Its `notificationId` is left on it, and its notifications are no longer shown,
     * as it is no longer recorded.
     *
     * Cleaner, but not done: loading it again by injecting its `session_id` from this record
     * (never from a notification), unregistering its `notificationId`,
     * then resetting it (as the forced join saves it again in the storage),
     * so that it never notifies the device again.
     * Not worth it for a session whose storage expired (about 6 months) or was cleared.
     * It would create a throwaway session each time,
     * for notifications the device does not show anyway.
     * The servers only keep the 5 last tokens of a session.
     */
    fun onSessionLoaded(
        record: CrispNotificationRecord,
        sessionId: String,
        tokenId: String?,
        deviceToken: String?,
    ): CrispNotificationPlan {
        val recorded = record.session
        // Nothing to record for a website that never used notifications
        if (!record.enabled && recorded?.notificationId == null) {
            return CrispNotificationPlan(record)
        }
        val isRecorded = recorded?.sessionId == sessionId
        val unreachable = recorded?.takeIf { !isRecorded && it.notificationId != null }
        val session = if (isRecorded) recorded.copy(tokenId = tokenId) else CrispNotificationSession(sessionId, tokenId)
        // Unregistered once reset, see onBeforeReset
        if (session.revoked) {
            return CrispNotificationPlan(record.copy(session = session), unreachable = unreachable)
        }
        val wanted = deviceToken?.takeIf { record.enabled }
        val registered = session.notificationId
        return CrispNotificationPlan(
            record = record.copy(session = session.copy(notificationId = wanted)),
            unregister = registered?.takeIf { it != wanted },
            // Registered again on each load, as it is idempotent,
            // but only added to the conversations of the session existing when registered
            // (not to the ones created afterwards), and can be dropped by the servers
            register = wanted,
            unreachable = unreachable,
        )
    }

    /**
     * Before resetting the joined [sessionId], the `notificationId` to unregister from it,
     * then the record without session (the next one is recorded once loaded).
     */
    fun onBeforeReset(record: CrispNotificationRecord, sessionId: String): Pair<CrispNotificationRecord, String?> {
        val recorded = record.session?.takeIf { it.sessionId == sessionId } ?: return record to null
        return record.copy(session = null) to recorded.notificationId
    }

    /**
     * Once a handover from [previous][CrispNotificationSession] joined [sessionId]
     * (`null` if it failed), the record without [previous][CrispNotificationSession],
     * whose token is to unregister if the handover joined it
     * (`null` otherwise: unreachable, see [onSessionLoaded]).
     *
     * [previous][CrispNotificationSession] is matched by its `sessionId` only,
     * as the recorded session can change meanwhile (e.g. revoked or its prompt answered),
     * not to hand it over again.
     */
    fun onHandoverLoaded(
        record: CrispNotificationRecord,
        previous: CrispNotificationSession,
        sessionId: String?,
    ): Pair<CrispNotificationRecord, String?> {
        val next = if (record.session?.sessionId == previous.sessionId) record.copy(session = null) else record
        return next to previous.notificationId?.takeIf { previous.sessionId == sessionId }
    }

    /** Marks the recorded session revoked if loaded with [tokenId], see [CrispNotificationSession.revoked]. */
    fun revoke(record: CrispNotificationRecord, tokenId: String?): CrispNotificationRecord {
        val session = record.session?.takeIf { it.tokenId == tokenId } ?: return record
        return record.copy(session = session.copy(revoked = true))
    }

    /**
     * The recorded session of [sessionId] whose notifications can be shown and opened,
     * i.e. enabled, registered and not revoked.
     */
    fun activeSession(record: CrispNotificationRecord, sessionId: String): CrispNotificationSession? =
        record.session?.takeIf {
            record.enabled && it.sessionId == sessionId && it.notificationId != null && !it.revoked
        }
}
