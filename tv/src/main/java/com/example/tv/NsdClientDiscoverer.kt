package com.example.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class NsdClientDiscoverer(
    context: Context,
    private val onServerFound: (String, Int) -> Unit
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun startDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("NsdClientDiscoverer", "Discovery start failed call, error: $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("NsdClientDiscoverer", "Discovery stop failed call, error: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.d("NsdClientDiscoverer", "NSD scan successfully initiated: $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d("NsdClientDiscoverer", "NSD scan stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d("NsdClientDiscoverer", "Potential matching service discovered: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceType.contains("_videostream")) {
                    try {
                        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                Log.e("NsdClientDiscoverer", "Resolve failed on NsdManager service, error: $errorCode")
                            }

                            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                                val hostAdd = resolvedInfo.host?.hostAddress
                                Log.d("NsdClientDiscoverer", "Service raw resolved host: $hostAdd on port ${resolvedInfo.port}")
                                
                                val attributes = try {
                                    resolvedInfo.attributes
                                } catch (e: Exception) {
                                    null
                                }
                                val txtIp = attributes?.get("server_ip")?.let { String(it, Charsets.UTF_8) }
                                Log.d("NsdClientDiscoverer", "Service TXT attribute server_ip: $txtIp")
                                
                                var resolvedIp = txtIp
                                if (resolvedIp.isNullOrEmpty()) {
                                    if (hostAdd != null) {
                                        var tempIp = hostAdd.trim().removePrefix("/")
                                        if (tempIp.contains("%")) {
                                            tempIp = tempIp.substringBefore("%")
                                        }
                                        resolvedIp = tempIp
                                    }
                                }
                                
                                Log.d("NsdClientDiscoverer", "Connecting with resolved IP: $resolvedIp")
                                if (resolvedIp != null) {
                                    onServerFound(resolvedIp, resolvedInfo.port)
                                }
                            }
                        })
                    } catch (e: Exception) {
                        Log.e("NsdClientDiscoverer", "Error resolving service", e)
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d("NsdClientDiscoverer", "Lost reference to service: ${serviceInfo.serviceName}")
            }
        }

        try {
            nsdManager.discoverServices(
                "_videostream._tcp",
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (e: Exception) {
            Log.e("NsdClientDiscoverer", "Error in discoverServices", e)
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e("NsdClientDiscoverer", "Error releasing discoveryListener reference on lifecycle teardown", e)
            }
        }
        discoveryListener = null
    }
}
