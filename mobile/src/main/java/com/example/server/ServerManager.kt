package com.example.server

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ServerManager {
    var localVideoServer: LocalVideoServer? = null
        private set
    var nsdPublisher: NsdServerPublisher? = null
        private set
    var multicastLock: WifiManager.MulticastLock? = null

    private val _isServerRunningFlow = MutableStateFlow(false)
    val isServerRunningFlow: StateFlow<Boolean> = _isServerRunningFlow.asStateFlow()
    var isServerRunning: Boolean
        get() = _isServerRunningFlow.value
        set(value) { _isServerRunningFlow.value = value }

    private val _serverAddressFlow = MutableStateFlow<String?>(null)
    val serverAddressFlow: StateFlow<String?> = _serverAddressFlow.asStateFlow()
    var serverAddress: String?
        get() = _serverAddressFlow.value
        set(value) { _serverAddressFlow.value = value }

    private val _errorMessageFlow = MutableStateFlow<String?>(null)
    val errorMessageFlow: StateFlow<String?> = _errorMessageFlow.asStateFlow()
    var errorMessage: String?
        get() = _errorMessageFlow.value
        set(value) { _errorMessageFlow.value = value }

    private val _emptyVideosFlow = MutableStateFlow<List<LocalVideoServer.SharedVideo>>(emptyList())
    val videosFlow: StateFlow<List<LocalVideoServer.SharedVideo>>
        get() = localVideoServer?.videosFlow ?: _emptyVideosFlow

    private val _emptyClientsFlow = MutableStateFlow<List<LocalVideoServer.ConnectedClient>>(emptyList())
    val connectedClientsFlow: StateFlow<List<LocalVideoServer.ConnectedClient>>
        get() = localVideoServer?.connectedClientsFlow ?: _emptyClientsFlow

    fun initialize(context: Context) {
        if (localVideoServer == null) {
            localVideoServer = LocalVideoServer(context.applicationContext)
        }
        if (nsdPublisher == null) {
            nsdPublisher = NsdServerPublisher(context.applicationContext)
        }
    }
}
