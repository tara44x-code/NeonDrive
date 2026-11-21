package com.neondrive.emu.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentLinkedQueue

/** A NeonDrive host found on the local network. */
data class NetHost(val name: String, val address: String, val port: Int)

/** Finds NeonDrive hosts on the same Wi-Fi / hotspot (DNS-SD), resolving them one at a time. */
class NetplayDiscovery(context: Context, private val onChange: (List<NetHost>) -> Unit) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val hosts = linkedMapOf<String, NetHost>()
    private val toResolve = ConcurrentLinkedQueue<NsdServiceInfo>()
    @Volatile private var resolving = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        runCatching {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("neondrive-discovery").apply { setReferenceCounted(false); acquire() }
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve.add(info); resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { if (hosts.remove(info.serviceName) != null) onChange(hosts.values.toList()) }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(NetplaySession.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    @Suppress("DEPRECATION") // resolveService is fine for one-shot resolving on all supported versions
    private fun resolveNext() {
        if (resolving) return
        val next = toResolve.poll() ?: return
        resolving = true
        runCatching {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    resolving = false; resolveNext()
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val address = info.host?.hostAddress
                    if (address != null && address != NetplaySession.localAddress()) {
                        main.post {
                            hosts[info.serviceName] = NetHost(info.serviceName, address, info.port)
                            onChange(hosts.values.toList())
                        }
                    }
                    resolving = false; resolveNext()
                }
            })
        }.onFailure { resolving = false }
    }

    fun stop() {
        runCatching { listener?.let { nsd.stopServiceDiscovery(it) } }
        listener = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }
}
