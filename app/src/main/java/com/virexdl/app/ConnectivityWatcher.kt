package com.virexdl.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow

class ConnectivityWatcher(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    val online = MutableStateFlow(connected())
    private fun connected(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork)?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } ?: false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        override fun onLost(network: Network) { online.value = if (manager.activeNetwork == network) false else connected() }
    }
    init { manager.registerDefaultNetworkCallback(callback) }
}
