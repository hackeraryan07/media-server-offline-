package com.example.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class NsdServerPublisher(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    var serviceName: String? = null
        private set

    fun registerService(port: Int, ipAddress: String) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "MobileStreamServer"
            serviceType = "_videostream._tcp"
            setPort(port)
            try {
                setAttribute("server_ip", ipAddress)
            } catch (e: Exception) {
                Log.e("NsdServerPublisher", "Failed to set server_ip attribute", e)
            }
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                serviceName = info.serviceName
                Log.d("NsdServerPublisher", "Registered NSD Server successfully: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e("NsdServerPublisher", "NSD Registration failed, error code: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d("NsdServerPublisher", "NSD Server Unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e("NsdServerPublisher", "NSD Unregistration failed, error: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e("NsdServerPublisher", "Error calling registerService inside NsdManager", e)
        }
    }

    fun unregisterService() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.e("NsdServerPublisher", "Error unregistering NSD service", e)
            }
        }
        registrationListener = null
    }
}
