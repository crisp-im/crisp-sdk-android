---
paths:
  - "sdk/src/*/java/im/crisp/sdk/**/notification/**"
  - "sdk/src/main/java/im/crisp/sdk/ui/CrispChatboxView.kt"
  - "sdk/src/main/java/im/crisp/sdk/internal/chatbox/CrispLegacyStorage.kt"
---

# Notifications: settled decisions

Checked and decided. Don't suggest changing them in reviews:

- `POST_NOTIFICATIONS` is not declared by the SDK: `firebase-messaging` merges it into the app manifest. If an app removes it (`tools:node="remove"`), `CrispNotificationSync.promptWhenNeeded` logs an error and does not prompt.
- `CrispNotifications.setEnabled` is the opt-out of the in-chat prompt, as `setShouldPromptForNotificationPermission` is on iOS: no separate flag for the prompt.
- Notifications are not suppressed while a chatbox shows their session: the Crisp servers do not send them while the session is joined.
- The Web client does not track tokens: `registerNotification` and `unregisterNotification` only emit `notification:provider:(un)register` on the current socket, so the SDK handles everything else (sessions, resets, handover).
- The Java overloads of `CrispNotifications.handleNotification` differ from 2.x on purpose. The documentation covers it.
- The in-chat prompt is answered per session (`promptAnswered`), as 2.x did: it is shown again on a new session while the permission is not granted, even if the user denied it before. Don't gate it on the permission state (as iOS does with `.notDetermined`), nor add a device-wide "asked" flag.
- The in-chat prompt is shown following these rules, in order (`CrispNotificationPrompt.decide`):
  1. shown before on the session (`promptShown`): right away, below the `$identity` message if shown, otherwise standalone,
  2. `$identity` message shown: right away, below it, without header,
  3. identity form shown: wait,
  4. identity form closed: wait for the `$identity` message,
  5. the user sent a message in this page and `IDENTITY_DELAY` (6s) elapsed without identity request: right away, standalone.
  A user message restored on load does not show it standalone (unlike iOS): it waits for the next message sent.
- The handover (`CrispChatboxNotifications.handOverIfNeeded`) loads the previous session again in the Web client to unregister its token, as the servers only unregister from the joined session. Its side effects on the previous session (the visitor seen online, messages possibly marked read, triggers) are accepted until the servers can unregister otherwise (TODO in `CrispNotificationPolicy`).
- Blocking a session (from the Crisp Dashboard) blocks any attempt to join it, or to join a new one, even with another `tokenId`. So while blocked, neither the handover nor the session loaded next can be joined.
- A handover not joined in time keeps the previous session and fails the load (`Offline` or `LoadingTimeout`), retried on the next load: whatever prevents joining it (offline, background, blocked, outage) prevents joining any session. It is never dropped on timeout.
- An unknown `sessionId` injected (e.g. the 2.x session of a handover) never times out: the servers refuse it ("Session could not be joined from server") and the Web client creates a new session right away, with or without `tokenId` (checked on a device, `crisp-client` 4.15.3). The handover then forgets the previous session as unreachable.
- A reset requested for the session handed over (revoked) is not applied during the handover, which only unregisters its token. Switching the `tokenId` does not clear that session from the Web storage either (the Web client keeps one per `tokenId`, as on the Web), and the reset stays requested (persisted by `CrispControllerDispatcher`), applied before the chatbox becomes ready once that `tokenId` is loaded again. Its notifications are already revoked. Don't suggest applying it during the handover.
- Two chatboxes of the same website with different `tokenId`s are not shown at the same time, and each restores its own session: no guard against them handing over from each other.
- Notifications are Crisp blue without the `com.google.firebase.messaging.default_notification_color` meta-data, not the website theme color as in 2.x: `crisp-client` has no getter for it (only CSS variables on `.crisp-client`, e.g. `--crisp-color-theme-500`, which are internals), and the light color of existing channels cannot be updated anyway.
- Registering the Firebase installation ID (FID, `firebase_messaging_installation_id_enabled`) instead of the FCM token works end to end: the Crisp servers send to it.
- The `websiteId` is used as is everywhere (notification tag and channel, cancel, configs), as integrators copy it from the Crisp Dashboard, which the servers send back in `website_id`. Only the record keys of `CrispNotificationStore` are lowercased, as SDK 2.x keyed its settings, to migrate them. Don't suggest normalizing its case.
- The pre-3.x migration (`CrispNotificationStore.migrateLegacyOnce`) runs in the background from the first `CrispChatboxView` (`CrispLegacyStorage.cleanUpOnce`), but on the calling thread if `CrispNotifications` is used before (e.g. `setEnabled` in `Application.onCreate`, or a notification received first). Accepted: a few small files, read once per install upgraded from 2.x. Don't suggest moving it off the main thread (e.g. from `Crisp.preheat`, or an asynchronous `setEnabled`, which would make `isEnabled` inconsistent right after).
