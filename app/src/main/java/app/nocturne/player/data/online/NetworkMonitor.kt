package app.nocturne.player.data.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.nocturne.player.data.model.NetworkSpeedTier

class NetworkMonitor(private val context: Context) {

    fun currentSpeedTier(): NetworkSpeedTier {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkSpeedTier.STANDARD
        val activeNetwork = cm.activeNetwork ?: return NetworkSpeedTier.OFFLINE
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkSpeedTier.OFFLINE

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!hasInternet) return NetworkSpeedTier.OFFLINE

        val isUnmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

        val downstreamKbps = caps.linkDownstreamBandwidthKbps

        return when {
            isUnmetered && (downstreamKbps <= 0 || downstreamKbps >= 2500) -> NetworkSpeedTier.FAST_UNMETERED
            !isUnmetered && downstreamKbps in 1..1400 -> NetworkSpeedTier.SLOW_OR_METERED
            !isUnmetered -> NetworkSpeedTier.STANDARD
            downstreamKbps in 1..1200 -> NetworkSpeedTier.SLOW_OR_METERED
            else -> NetworkSpeedTier.STANDARD
        }
    }

    fun isUnmeteredOrWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun isOnline(): Boolean = currentSpeedTier() != NetworkSpeedTier.OFFLINE
}
