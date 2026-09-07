package com.example.remoteaudio

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    
    // Discovered devices list
    val discoveredDevices = mutableStateListOf<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        startDiscovery()

        setContent {
            MaterialTheme {
                var manualIp by remember { mutableStateOf("") }
                var isSyncing by remember { mutableStateOf(false) }
                var syncingIp by remember { mutableStateOf("") }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(16.dp)
                            .fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Remote Audio",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF21005D),
                            modifier = Modifier.padding(bottom = 24.dp)
                        )

                        if (isSyncing) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFEADDFF)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Syncing Audio with TV",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = Color(0xFF21005D)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Connected to: $syncingIp",
                                        fontSize = 14.sp,
                                        color = Color(0xFF49454F)
                                    )
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Button(
                                        onClick = {
                                            stopAudioSync()
                                            isSyncing = false
                                            syncingIp = ""
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))
                                    ) {
                                        Text("Stop Syncing")
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "Discovered TVs",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            )

                            if (discoveredDevices.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(100.dp)
                                        .background(Color(0xFFF3EDF7), RoundedCornerShape(16.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Scanning for TVs...", color = Color(0xFF49454F))
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(discoveredDevices) { device ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                syncingIp = device.second
                                                isSyncing = true
                                                startAudioSync(device.second)
                                            },
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(16.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = "TV")
                                                Spacer(modifier = Modifier.width(16.dp))
                                                Column {
                                                    Text(text = device.first, fontWeight = FontWeight.Bold)
                                                    Text(text = device.second, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            Text(
                                text = "Manual IP Entry",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            )

                            OutlinedTextField(
                                value = manualIp,
                                onValueChange = { manualIp = it },
                                placeholder = { Text("e.g. 192.168.1.100") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    if (manualIp.isNotBlank()) {
                                        syncingIp = manualIp
                                        isSyncing = true
                                        startAudioSync(manualIp)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Connect Manually")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("RemoteAudio", "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d("RemoteAudio", "Service discovery success $service")
                if (service.serviceType.contains("_tvremote")) {
                    nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e("RemoteAudio", "Resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val ip = serviceInfo.host?.hostAddress
                            if (ip != null) {
                                var tempIp = ip.trim().removePrefix("/")
                                if (tempIp.contains("%")) {
                                    tempIp = tempIp.substringBefore("%")
                                }
                                runOnUiThread {
                                    if (discoveredDevices.none { it.second == tempIp }) {
                                        discoveredDevices.add(Pair(serviceInfo.serviceName, tempIp))
                                    }
                                }
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.e("RemoteAudio", "service lost: $service")
                runOnUiThread {
                    discoveredDevices.removeAll { it.first == service.serviceName }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager?.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        
        try {
            nsdManager?.discoverServices("_tvremote._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e("RemoteAudio", "Discovery error", e)
        }
    }

    private fun startAudioSync(ip: String) {
        val intent = Intent(this, AudioSyncService::class.java).apply {
            action = AudioSyncService.ACTION_START
            putExtra(AudioSyncService.EXTRA_TV_IP, ip)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopAudioSync() {
        val intent = Intent(this, AudioSyncService::class.java).apply {
            action = AudioSyncService.ACTION_STOP
        }
        startService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {}
    }
}
