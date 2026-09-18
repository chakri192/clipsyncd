package com.chakri.clipsyncd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Discovers the Mac via the Bonjour service clipsyncd_mac.py advertises
 * (_clipsyncd._tcp, instance name "clipsyncd") instead of relying on a
 * hardcoded IP, so the app works on whatever network both devices are on.
 * Requires a WifiManager.MulticastLock — without it Android silently drops
 * the mDNS multicast traffic this depends on.
 */
class NsdHelper(
    context: Context,
    private val onResolved: (host: String, port: Int) -> Unit,
    private val onLost: () -> Unit
) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile private var resolving = false

    fun start() {
        if (discoveryListener != null) return
        multicastLock = wifiManager.createMulticastLock("clipsyncd-mdns").apply {
            setReferenceCounted(true)
            acquire()
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceName == SERVICE_NAME) resolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                if (service.serviceName == SERVICE_NAME) {
                    Log.i(TAG, "mac service lost")
                    onLost()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices failed: ${e.message}")
        }
    }

    @Synchronized
    private fun resolve(service: NsdServiceInfo) {
        if (resolving) return
        resolving = true
        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolving = false
                Log.w(TAG, "resolve failed: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                resolving = false
                val host = serviceInfo.host?.hostAddress ?: return
                onResolved(host, serviceInfo.port)
            }
        })
    }

    fun stop() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.w(TAG, "stopServiceDiscovery failed: ${e.message}")
            }
        }
        discoveryListener = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    companion object {
        private const val TAG = "NsdHelper"
        private const val SERVICE_TYPE = "_clipsyncd._tcp."
        private const val SERVICE_NAME = "clipsyncd"
    }
}
