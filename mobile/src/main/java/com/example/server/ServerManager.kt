package com.example.server

import android.content.Context
import android.net.wifi.WifiManager

object ServerManager {
    var localVideoServer: LocalVideoServer? = null
        private set
    var nsdPublisher: NsdServerPublisher? = null
        private set
    var multicastLock: WifiManager.MulticastLock? = null
    
    var isServerRunning = false
    var serverAddress: String? = null
    var errorMessage: String? = null

    fun initialize(context: Context) {
        if (localVideoServer == null) {
            localVideoServer = LocalVideoServer(context.applicationContext)
        }
        if (nsdPublisher == null) {
            nsdPublisher = NsdServerPublisher(context.applicationContext)
        }
    }
}
