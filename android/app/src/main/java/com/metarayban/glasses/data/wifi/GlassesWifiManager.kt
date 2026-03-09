package com.metarayban.glasses.data.wifi

import android.content.Context
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Manages WiFi connection to the glasses' temporary hotspot.
 *
 * Uses WifiNetworkSpecifier API (Android 10+) which does NOT require root.
 * The system shows a dialog asking the user to confirm the connection.
 *
 * TODO: After protocol capture analysis, fill in:
 * - Exact SSID pattern
 * - WPA2 password (received via BLE or derived)
 */
class GlassesWifiManager(private val context: Context) {

    companion object {
        private const val TAG = "MetaWiFi"
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null

    /**
     * Connect to the glasses' WiFi hotspot.
     *
     * @param ssid The SSID of the glasses' hotspot
     * @param password The WPA2 password (from BLE exchange)
     * @return true if connected successfully
     */
    suspend fun connectToGlasses(ssid: String, password: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            Log.d(TAG, "Connecting to glasses WiFi: $ssid")

            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Connected to glasses WiFi")
                    currentNetwork = network
                    // Bind all socket traffic to this network
                    connectivityManager.bindProcessToNetwork(network)
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }

                override fun onUnavailable() {
                    Log.e(TAG, "Glasses WiFi unavailable")
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "Glasses WiFi lost")
                    currentNetwork = null
                    connectivityManager.bindProcessToNetwork(null)
                }
            }

            networkCallback = callback
            connectivityManager.requestNetwork(request, callback)

            continuation.invokeOnCancellation {
                disconnect()
            }
        }

    /**
     * Disconnect from the glasses' WiFi and restore normal networking.
     */
    fun disconnect() {
        networkCallback?.let {
            connectivityManager.unregisterNetworkCallback(it)
        }
        connectivityManager.bindProcessToNetwork(null)
        networkCallback = null
        currentNetwork = null
        Log.d(TAG, "Disconnected from glasses WiFi")
    }

    /**
     * Get the current network bound to the glasses.
     */
    fun getNetwork(): Network? = currentNetwork

    val isConnected: Boolean
        get() = currentNetwork != null
}
