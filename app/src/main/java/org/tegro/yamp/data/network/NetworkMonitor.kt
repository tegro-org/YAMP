package org.tegro.yamp.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun isCurrentlyConnected(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            android.util.Log.e("NetworkMonitor", "Unexpected error", e)
            false
        }
    }

    val isConnectedFlow: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                trySend(true)
            }

            // The default network: moving from Wi-Fi to mobile data only replaces it, and this comes when
            // there is no network left at all
            override fun onLost(network: Network) {
                super.onLost(network)
                trySend(false)
            }
        }

        try {
            connectivityManager.registerDefaultNetworkCallback(callback)

            // Initial state
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null) {
                val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
                val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                trySend(hasInternet)
            } else {
                trySend(false)
            }
        } catch (e: Exception) {
            android.util.Log.e("NetworkMonitor", "Unexpected error", e)
            trySend(true) // Fallback to true if we can't check
        }

        awaitClose {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                android.util.Log.e("NetworkMonitor", "Unexpected error", e)
            }
        }
    }.distinctUntilChanged()

    /**
     * Whether the network now in use is metered (mobile data, or a Wi-Fi the user marked as metered — the
     * car's 4G router on a head unit): per-account quality picks its "metered" choice then.
     */
    fun isMetered(): Boolean = runCatching { connectivityManager.isActiveNetworkMetered }.getOrDefault(false)

    /** [isMetered] as it changes: a move between Wi-Fi and mobile data, or the metered flag toggled. */
    val meteredFlow: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }

            override fun onLost(network: Network) {
                trySend(isMetered())
            }
        }
        trySend(isMetered())
        val registered = runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }.isSuccess
        awaitClose { if (registered) runCatching { connectivityManager.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    suspend fun isYandexReachable(timeoutMs: Int = 1500): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("oauth.yandex.ru", 443), timeoutMs)
            }
            true
        } catch (e: java.io.IOException) {
            // Unreachable, timed out or refused: all the same here
            false
        }
    }

    /**
     * Whether [host] answers at all on 443. The service may answer while the server the streams come from does
     * not (a network letting only some addresses through): that is not a weak link.
     */
    suspend fun isReachable(host: String, timeoutMs: Int = 1500): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, 443), timeoutMs)
            }
            true
        } catch (e: java.io.IOException) {
            false
        }
    }
}
