package im.crisp.sdk.internal.environment

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val LOG_TAG = "Connectivity"

/**
 * Whether the app is in foreground, i.e. one of its activities is started.
 *
 * Considered in foreground until the process lifecycle is created,
 * e.g. if an app removed its initializer, not to consider it in background forever.
 */
private fun isAppInForeground(): Flow<Boolean> =
    ProcessLifecycleOwner.get().lifecycle.currentStateFlow
        .map { it != Lifecycle.State.CREATED }
        .distinctUntilChanged()

/**
 * Whether the device has a default network to reach the Internet.
 *
 * Considered online if the network state cannot be read, e.g. if an app removed the
 * `ACCESS_NETWORK_STATE` permission, not to consider it offline forever.
 */
private fun Context.isOnline(): Flow<Boolean> = callbackFlow {
    val connectivityManager = getSystemService(ConnectivityManager::class.java)
    if (connectivityManager == null) {
        send(true)
        awaitClose()
        return@callbackFlow
    }
    // Only called when there is a default network or none anymore, not when switching
    // between networks (e.g. Wi-Fi to mobile data), as onAvailable is then called with the new one
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trySend(true)
        }

        override fun onLost(network: Network) {
            trySend(false)
        }
    }
    try {
        // The callback only notifies a default network, if any, once registered
        send(connectivityManager.activeNetwork != null)
        connectivityManager.registerDefaultNetworkCallback(callback)
    } catch (e: SecurityException) {
        log(Level.WARN, LOG_TAG, "Cannot read the network state", e)
        send(true)
        awaitClose()
        return@callbackFlow
    }
    awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()

/** Whether the app is in foreground and online, i.e. a Web socket can and should be connected. */
internal fun Context.isAppInForegroundAndOnline(): Flow<Boolean> =
    combine(isAppInForeground(), applicationContext.isOnline()) { inForeground, online ->
        inForeground && online
    }.distinctUntilChanged()
