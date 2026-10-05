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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.remoteaudio.ui.theme.GreenSuccess
import com.example.remoteaudio.ui.theme.RemoteAudioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    val discoveredDevices = mutableStateListOf<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        startDiscovery()

        setContent {
            RemoteAudioTheme {
                var manualIp by remember { mutableStateOf("") }
                var isSyncing by remember { mutableStateOf(false) }
                var syncingIp by remember { mutableStateOf("") }
                var audioShiftMs by remember { mutableLongStateOf(0L) }
                var isContinuousSyncEnabled by remember { mutableStateOf(true) }
                var currentTitle by remember { mutableStateOf<String?>(null) }
                val coroutineScope = rememberCoroutineScope()
                val httpClient = remember { OkHttpClient() }

                // State polling while syncing
                LaunchedEffect(isSyncing, syncingIp) {
                    if (isSyncing && syncingIp.isNotBlank()) {
                        while (isActive && isSyncing) {
                            try {
                                val req = Request.Builder().url("http://$syncingIp:9000/state").build()
                                withContext(Dispatchers.IO) {
                                    httpClient.newCall(req).execute().use { resp ->
                                        if (resp.isSuccessful) {
                                            val body = resp.body?.string()
                                            if (body != null) {
                                                val json = JSONObject(body)
                                                audioShiftMs = json.optLong("audioShiftMs", 0L)
                                                isContinuousSyncEnabled = json.optBoolean("isContinuousSyncEnabled", true)
                                                val t = json.optString("title", "")
                                                currentTitle = if (t.isNotBlank()) t else null
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("RemoteAudio", "State poll error", e)
                            }
                            delay(1500)
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Audiotrack,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = "Remote Audio Receiver",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            actions = {
                                if (!isSyncing) {
                                    IconButton(
                                        onClick = {
                                            restartDiscovery()
                                        },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Refresh Discovery",
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        AnimatedContent(
                            targetState = isSyncing,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
                            },
                            label = "SyncStateTransition",
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 640.dp)
                                .align(Alignment.TopCenter)
                        ) { syncing ->
                            if (syncing) {
                                // SYNCING ACTIVE SCREEN
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    // Hero Card
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(28.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer
                                        ),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(24.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(10.dp)
                                                        .background(GreenSuccess, CircleShape)
                                                )
                                                Text(
                                                    text = "AUDIO SYNC STREAMING",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                                    letterSpacing = 1.2.sp
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(10.dp))

                                            Text(
                                                text = currentTitle ?: "Synchronized to TV Playback",
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                textAlign = TextAlign.Center,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )

                                            Spacer(modifier = Modifier.height(4.dp))

                                            Text(
                                                text = "TV Host: $syncingIp",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                            )

                                            Spacer(modifier = Modifier.height(24.dp))

                                            // Animated Waveform Visualizer
                                            AnimatedAudioBars(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(56.dp)
                                                    .padding(horizontal = 16.dp),
                                                barColor = MaterialTheme.colorScheme.primary
                                            )

                                            Spacer(modifier = Modifier.height(24.dp))

                                            Button(
                                                onClick = {
                                                    stopAudioSync()
                                                    isSyncing = false
                                                    syncingIp = ""
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = MaterialTheme.colorScheme.error,
                                                    contentColor = MaterialTheme.colorScheme.onError
                                                ),
                                                shape = RoundedCornerShape(20.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(48.dp)
                                            ) {
                                                Icon(Icons.Default.Stop, contentDescription = null)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Stop Audio Stream", fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }

                                    // Audio Calibration Card
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(24.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                        ),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(20.dp),
                                            verticalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "Audio Shift Calibration",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = String.format("%.2fs", audioShiftMs / 1000f),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }

                                            Text(
                                                text = "Fine-tune speaker latency to match Bluetooth or headphone delay with sub-frame accuracy.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                FilledTonalButton(
                                                    onClick = {
                                                        val newShift = (audioShiftMs - 100).coerceAtLeast(-60000)
                                                        audioShiftMs = newShift
                                                        coroutineScope.launch(Dispatchers.IO) {
                                                            try {
                                                                val req = Request.Builder().url("http://$syncingIp:9000/command?action=set_audio_shift&value=$newShift").build()
                                                                httpClient.newCall(req).execute().close()
                                                            } catch (e: Exception) {}
                                                        }
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    shape = RoundedCornerShape(14.dp)
                                                ) {
                                                    Text("-0.1s Delay")
                                                }

                                                FilledTonalButton(
                                                    onClick = {
                                                        val newShift = (audioShiftMs + 100).coerceAtMost(60000)
                                                        audioShiftMs = newShift
                                                        coroutineScope.launch(Dispatchers.IO) {
                                                            try {
                                                                val req = Request.Builder().url("http://$syncingIp:9000/command?action=set_audio_shift&value=$newShift").build()
                                                                httpClient.newCall(req).execute().close()
                                                            } catch (e: Exception) {}
                                                        }
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    shape = RoundedCornerShape(14.dp)
                                                ) {
                                                    Text("+0.1s Delay")
                                                }
                                            }

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Continuous Sync",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = "Continuously align timeline drift",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Switch(
                                                    checked = isContinuousSyncEnabled,
                                                    onCheckedChange = {
                                                        coroutineScope.launch(Dispatchers.IO) {
                                                            try {
                                                                val req = Request.Builder().url("http://$syncingIp:9000/command?action=toggle_continuous_sync").build()
                                                                httpClient.newCall(req).execute().close()
                                                            } catch (e: Exception) {}
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                // DISCONNECTED / SCANNING SCREEN
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(20.dp)
                                ) {
                                    // Instructions Banner
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(24.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                        ),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(20.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Hearing,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.size(26.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Column {
                                                Text(
                                                    text = "Listen from TV Remotely",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = "Connect headphones to this device and receive real-time, low-latency audio from TV playback.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }

                                    // Discovered TVs Header
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "DISCOVERED TVS",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            letterSpacing = 1.2.sp
                                        )
                                        if (discoveredDevices.isNotEmpty()) {
                                            Text(
                                                text = "${discoveredDevices.size} found",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    if (discoveredDevices.isEmpty()) {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(20.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                            ),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(24.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(32.dp),
                                                    strokeWidth = 3.dp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.height(14.dp))
                                                Text(
                                                    text = "Scanning for TV Streamers on Wi-Fi...",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    text = "Ensure TV app is running on the same network",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            discoveredDevices.forEach { device ->
                                                Card(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            syncingIp = device.second
                                                            isSyncing = true
                                                            startAudioSync(device.second)
                                                        },
                                                    shape = RoundedCornerShape(18.dp),
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                                    ),
                                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(16.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(44.dp)
                                                                .background(
                                                                    MaterialTheme.colorScheme.primaryContainer,
                                                                    CircleShape
                                                                ),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Tv,
                                                                contentDescription = "TV",
                                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                                            )
                                                        }
                                                        Spacer(modifier = Modifier.width(16.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = device.first,
                                                                style = MaterialTheme.typography.bodyLarge,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.onSurface
                                                            )
                                                            Text(
                                                                text = device.second,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                fontFamily = FontFamily.Monospace,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                        Button(
                                                            onClick = {
                                                                syncingIp = device.second
                                                                isSyncing = true
                                                                startAudioSync(device.second)
                                                            },
                                                            shape = RoundedCornerShape(16.dp),
                                                            modifier = Modifier.height(40.dp)
                                                        ) {
                                                            Text("Connect")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Manual IP Entry Card
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(24.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                        ),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(20.dp),
                                            verticalArrangement = Arrangement.spacedBy(14.dp)
                                        ) {
                                            Text(
                                                text = "Manual Connection",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            OutlinedTextField(
                                                value = manualIp,
                                                onValueChange = { manualIp = it },
                                                placeholder = { Text("e.g. 192.168.1.100") },
                                                label = { Text("TV IP Address") },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true,
                                                shape = RoundedCornerShape(14.dp)
                                            )
                                            Button(
                                                onClick = {
                                                    if (manualIp.isNotBlank()) {
                                                        val clean = manualIp.trim()
                                                        syncingIp = clean
                                                        isSyncing = true
                                                        startAudioSync(clean)
                                                    }
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(48.dp),
                                                shape = RoundedCornerShape(20.dp),
                                                enabled = manualIp.isNotBlank()
                                            ) {
                                                Text("Connect by IP", fontWeight = FontWeight.Medium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun restartDiscovery() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {}
        discoveredDevices.clear()
        startDiscovery()
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

@Composable
fun AnimatedAudioBars(
    modifier: Modifier = Modifier,
    barColor: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "audioBars")

    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase1"
    )

    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(380, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase2"
    )

    val phase3 by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase3"
    )

    val phase4 by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(410, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase4"
    )

    val phases = listOf(phase1, phase2, phase3, phase4, phase2, phase3, phase1, phase4, phase3, phase2)

    Canvas(modifier = modifier) {
        val totalBars = phases.size
        val availableWidth = size.width
        val barWidth = 6.dp.toPx()
        val spacing = (availableWidth - (totalBars * barWidth)) / (totalBars + 1)
        val maxHeight = size.height

        phases.forEachIndexed { index, fraction ->
            val barHeight = maxHeight * fraction
            val left = spacing + index * (barWidth + spacing)
            val top = (maxHeight - barHeight) / 2f

            drawRoundRect(
                color = barColor,
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
