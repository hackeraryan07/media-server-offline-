package com.example

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import android.content.Intent
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import com.example.server.ServerManager
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import org.json.JSONObject

data class OptionItem(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val action: String,
    val description: String
)

class RemoteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val tvIp = intent.getStringExtra("tv_ip") ?: intent.getStringExtra("tvIp") ?: return finish()

        val serviceIntent = Intent(this, com.example.server.TvControlService::class.java).apply {
            action = com.example.server.TvControlService.ACTION_START
            putExtra(com.example.server.TvControlService.EXTRA_TV_IP, tvIp)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setContent {
            MyApplicationTheme {
                RemoteScreen(tvIp, onBack = { finish() })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val serviceIntent = Intent(this, com.example.server.TvControlService::class.java).apply {
            action = com.example.server.TvControlService.ACTION_STOP
        }
        startService(serviceIntent)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RemoteScreen(tvIp: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    DisposableEffect(tvIp) {
        val serviceIntent = Intent(context, com.example.server.TvControlService::class.java).apply {
            action = com.example.server.TvControlService.ACTION_START
            putExtra(com.example.server.TvControlService.EXTRA_TV_IP, tvIp)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        
        onDispose {
            val stopIntent = Intent(context, com.example.server.TvControlService::class.java).apply {
                action = com.example.server.TvControlService.ACTION_STOP
            }
            context.startService(stopIntent)
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf<String?>(null) }
    var currentVideoId by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableStateOf(0L) }
    var positionUpdateTime by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var isShifting by remember { mutableStateOf(false) }
    var needsResumeChoice by remember { mutableStateOf(false) }
    var resumePosition by remember { mutableStateOf(0L) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var needsSpeedChoice by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var needsAudioShiftChoice by remember { mutableStateOf(false) }
    var showAudioShiftDialog by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableStateOf(1.0f) }
    var audioShiftMs by remember { mutableStateOf(0L) }
    var isRemoteAudioEnabled by remember { mutableStateOf(false) }
    var isContinuousSyncEnabled by remember { mutableStateOf(true) }
    var videoUrl by remember { mutableStateOf("") }

    val options = remember(isMuted, isLocked, isRemoteAudioEnabled) {
        listOf(
            OptionItem(if (isMuted) "Unmute" else "Mute", if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, "mute", "Toggle audio volume"),
            OptionItem(if (isRemoteAudioEnabled) "Remote Audio On" else "Remote Audio Off", Icons.Default.Audiotrack, "audio_track", "Switch audio track"),
            OptionItem("Subtitles", Icons.Default.Subtitles, "subtitles", "Toggle subtitles"),
            OptionItem("Playback Speed", Icons.Default.Speed, "speed", "Change video speed"),
            OptionItem("Aspect Ratio", Icons.Default.AspectRatio, "resize", "Fit, Fill or Zoom screen"),
            OptionItem("PiP Mode", Icons.Default.PictureInPicture, "pip", "Picture in Picture"),
            OptionItem("Screen Rotation", Icons.Default.ScreenRotation, "rotate", "Rotate TV playback orientation"),
            OptionItem("Playlist", Icons.Default.PlaylistPlay, "playlist", "Open playlist overview"),
            OptionItem("Cast Scan", Icons.Default.Cast, "cast", "Scan and connect to devices"),
            OptionItem("Screenshot", Icons.Default.PhotoCamera, "screenshot", "Capture TV screen"),
            OptionItem(if (isLocked) "Unlock Controls" else "Lock Controls", if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen, "lock", "Lock video player overlays"),
            OptionItem("System Settings", Icons.Default.Settings, "settings", "Open settings panel"),
            OptionItem("TV Back Press", Icons.AutoMirrored.Filled.ArrowBack, "back", "Go back on TV")
        )
    }
    
    LaunchedEffect(isRemoteAudioEnabled) {
        if (isRemoteAudioEnabled) {
            val intent = android.content.Intent(context, com.example.server.AudioSyncService::class.java).apply {
                action = com.example.server.AudioSyncService.ACTION_START
                putExtra(com.example.server.AudioSyncService.EXTRA_TV_IP, tvIp)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    val client = remember { OkHttpClient() }
    val videoList = remember { ServerManager.localVideoServer?.getVideosList() ?: emptyList() }
    val listState = rememberLazyListState()

    LaunchedEffect(currentVideoId, videoList) {
        if (currentVideoId != null && videoList.isNotEmpty()) {
            val index = videoList.indexOfFirst { it.id == currentVideoId }
            if (index >= 0) {
                listState.animateScrollToItem(index)
            }
        }
    }

    LaunchedEffect(tvIp) {
        while (true) {
            try {
                val request = Request.Builder().url("http://$tvIp:9000/state").build()
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()
                            if (body != null) {
                                val json = JSONObject(body)
                                isPlaying = json.optBoolean("isPlaying", false)
                                val t = json.optString("title", "")
                                currentTitle = if (t.isNotBlank()) t else null
                                val vId = json.optString("videoId", "")
                                currentVideoId = if (vId.isNotBlank()) vId else null
                                duration = json.optLong("duration", 0L)
                                needsResumeChoice = json.optBoolean("needsResumeChoice", false)
                                resumePosition = json.optLong("resumePosition", 0L)
                                isLocked = json.optBoolean("isLocked", false)
                                isMuted = json.optBoolean("isMuted", false)
                                needsSpeedChoice = json.optBoolean("needsSpeedChoice", false)
                                currentSpeed = json.optDouble("currentSpeed", 1.0).toFloat()
                                needsAudioShiftChoice = json.optBoolean("needsAudioShiftChoice", false)
                                if (!isShifting) {
                                    audioShiftMs = json.optLong("audioShiftMs", 0L)
                                }
                                isRemoteAudioEnabled = json.optBoolean("isRemoteAudioEnabled", false)
                                isContinuousSyncEnabled = json.optBoolean("isContinuousSyncEnabled", true)
                                videoUrl = json.optString("videoUrl", "")
                                if (!isSeeking) {
                                    position = json.optLong("position", 0L)
                                    positionUpdateTime = android.os.SystemClock.elapsedRealtime()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("RemoteActivity", "Error polling state", e)
            }
            delay(1000)
        }
    }

    fun sendCommand(action: String, extraId: String? = null) {
        Thread {
            try {
                var url = "http://$tvIp:9000/command?action=$action"
                if (extraId != null) url += "&id=$extraId"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.e("RemoteActivity", "Command failed: $action", e)
            }
        }.start()
    }

    if (needsResumeChoice) {
        var timeRemainingMs by remember { mutableStateOf(10000L) }
        val startOverFocusRequester = remember { FocusRequester() }
        
        LaunchedEffect(needsResumeChoice) {
            if (needsResumeChoice) {
                try {
                    startOverFocusRequester.requestFocus()
                } catch (e: Exception) {}
                timeRemainingMs = 10000L
                val startTime = System.currentTimeMillis()
                while (timeRemainingMs > 0 && needsResumeChoice) {
                    val elapsed = System.currentTimeMillis() - startTime
                    timeRemainingMs = (10000L - elapsed).coerceAtLeast(0L)
                    if (timeRemainingMs == 0L) {
                        Thread {
                            val request = Request.Builder().url("http://$tvIp:9000/command?action=resume_choice&choice=start_over").build()
                            client.newCall(request).execute().close()
                        }.start()
                        needsResumeChoice = false
                    }
                    delay(50)
                }
            }
        }

        AlertDialog(
            onDismissRequest = { /* Do nothing, force choice */ },
            containerColor = Color(0xFF222222),
            title = { Text("Resume Playback?", fontSize = 28.sp, color = Color.White, fontWeight = FontWeight.Bold) },
            text = { 
                val mins = (resumePosition / 1000) / 60
                val secs = (resumePosition / 1000) % 60
                val timeStr = String.format("%d:%02d", mins, secs)
                Text("Would you like to resume \"${currentTitle ?: "this video"}\" from $timeStr?", fontSize = 18.sp, color = Color(0xFFCCCCCC))
            },
            confirmButton = {
                val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val isFocused by interactionSource.collectIsFocusedAsState()
                val isPressed by interactionSource.collectIsPressedAsState()
                val active = isFocused || isPressed
                
                OutlinedButton(
                    onClick = { 
                        Thread {
                            val request = Request.Builder().url("http://$tvIp:9000/command?action=resume_choice&choice=continue").build()
                            client.newCall(request).execute().close()
                        }.start()
                        needsResumeChoice = false
                    },
                    interactionSource = interactionSource,
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (active) Color.White else Color.Transparent,
                        contentColor = if (active) Color.Black else Color.White
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text("Continue", fontSize = 18.sp)
                }
            },
            dismissButton = {
                val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val isFocused by interactionSource.collectIsFocusedAsState()
                val isPressed by interactionSource.collectIsPressedAsState()
                val active = isFocused || isPressed
                
                OutlinedButton(
                    onClick = { 
                        Thread {
                            val request = Request.Builder().url("http://$tvIp:9000/command?action=resume_choice&choice=start_over").build()
                            client.newCall(request).execute().close()
                        }.start()
                        needsResumeChoice = false
                    },
                    interactionSource = interactionSource,
                    modifier = Modifier.focusRequester(startOverFocusRequester),
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (active) Color.White else Color.Transparent,
                        contentColor = if (active) Color.Black else Color.White
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    val secsLeft = (timeRemainingMs / 1000) + 1
                    Text(
                        text = "Start Over ($secsLeft)",
                        fontSize = 18.sp
                    )
                }
            }
        )
    }

    if (showAudioShiftDialog || needsAudioShiftChoice) {
        AlertDialog(
            onDismissRequest = {
                showAudioShiftDialog = false
                needsAudioShiftChoice = false
                Thread {
                    val request = Request.Builder().url("http://$tvIp:9000/command?action=cancel_audio_shift").build()
                    client.newCall(request).execute().close()
                }.start()
            },
            title = { Text("Audio Shift") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(String.format("%.2fs", audioShiftMs / 1000f), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = {
                            val newShift = (audioShiftMs - 100).coerceAtLeast(-60000)
                            audioShiftMs = newShift
                            Thread {
                                val request = Request.Builder().url("http://$tvIp:9000/command?action=set_audio_shift&value=$newShift").build()
                                client.newCall(request).execute().close()
                            }.start()
                        }) {
                            Text("-0.1s")
                        }
                        Slider(
                            value = audioShiftMs.toFloat(),
                            onValueChange = { newVal ->
                                isShifting = true
                                val stepped = Math.round(newVal / 100f) * 100f
                                audioShiftMs = stepped.toLong()
                            },
                            onValueChangeFinished = {
                                isShifting = false
                                Thread {
                                    val request = Request.Builder().url("http://$tvIp:9000/command?action=set_audio_shift&value=$audioShiftMs").build()
                                    client.newCall(request).execute().close()
                                }.start()
                            },
                            valueRange = -60000f..60000f,
                            steps = 1199,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Button(onClick = {
                            val newShift = (audioShiftMs + 100).coerceAtMost(60000)
                            audioShiftMs = newShift
                            Thread {
                                val request = Request.Builder().url("http://$tvIp:9000/command?action=set_audio_shift&value=$newShift").build()
                                client.newCall(request).execute().close()
                            }.start()
                        }) {
                            Text("+0.1s")
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { sendCommand("toggle_continuous_sync") }) {
                        Text(if (isContinuousSyncEnabled) "Stop Syncing" else "Start Syncing")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAudioShiftDialog = false
                    needsAudioShiftChoice = false
                    Thread {
                        val request = Request.Builder().url("http://$tvIp:9000/command?action=cancel_audio_shift").build()
                        client.newCall(request).execute().close()
                    }.start()
                }) {
                    Text("Close")
                }
            }
        )
    }

    if (showSpeedDialog || needsSpeedChoice) {
        val speeds = listOf("0.5x" to 0.5f, "0.75x" to 0.75f, "1.0x" to 1.0f, "1.25x" to 1.25f, "1.5x" to 1.5f, "2.0x" to 2.0f)
        AlertDialog(
            onDismissRequest = { 
                showSpeedDialog = false
                needsSpeedChoice = false
            },
            title = { Text("Playback Speed") },
            text = {
                Column {
                    speeds.forEach { (label, value) ->
                        val isSelected = kotlin.math.abs(value - currentSpeed) < 0.01f
                        TextButton(
                            onClick = {
                                Thread {
                                    val request = Request.Builder().url("http://$tvIp:9000/command?action=set_speed&value=$value").build()
                                    client.newCall(request).execute().close()
                                }.start()
                                showSpeedDialog = false
                                needsSpeedChoice = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (isSelected) androidx.compose.material3.ButtonDefaults.textButtonColors(containerColor = Color(0xFFD3E3FD), contentColor = Color(0xFF041E49)) else androidx.compose.material3.ButtonDefaults.textButtonColors()
                        ) {
                            Text(label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            containerColor = Color(0xFFF7F2FA),
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Advanced TV Controls",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF21005D),
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)
                ) {
                    items(options) { option ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (option.action == "speed") {
                                            showSpeedDialog = true
                                            showBottomSheet = false
                                        } else {
                                            sendCommand(option.action)
                                            if (option.action != "mute" && option.action != "lock" && option.action != "audio_track") {
                                                showBottomSheet = false
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (option.action == "audio_track") {
                                            showAudioShiftDialog = true
                                            showBottomSheet = false
                                        }
                                    }
                                ),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEADBFF))
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(Color(0xFFEADDFF), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = option.icon,
                                        contentDescription = option.label,
                                        tint = Color(0xFF21005D),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = option.label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1C1B1F),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = option.description,
                                    fontSize = 9.sp,
                                    color = Color(0xFF49454F),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    lineHeight = 11.sp,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TV Remote Controls", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFF7F2FA)
                )
            )
        },
        containerColor = Color(0xFFF7F2FA)
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            ) {
                // Now Playing Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFEADDFF))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "NOW PLAYING ON TV",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF21005D),
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = currentTitle ?: "No Media Playing",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF21005D),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            // Slider for Seek
                            Slider(
                                value = if (duration > 0) (position.toFloat() / duration.toFloat()) else 0f,
                                onValueChange = { percent ->
                                    isSeeking = true
                                    position = (percent * duration).toLong()
                                },
                                onValueChangeFinished = {
                                    isSeeking = false
                                    sendCommand("seek&position=$position")
                                },
                                enabled = !isLocked,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF6750A4),
                                    activeTrackColor = Color(0xFF6750A4),
                                    inactiveTrackColor = Color(0xFF6750A4).copy(alpha = 0.3f)
                                )
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Player Controls
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (isLocked) 0.5f else 1f),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { if (!isLocked) sendCommand("prev") },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color.White, CircleShape)
                                ) {
                                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color(0xFF21005D))
                                }

                                IconButton(
                                    onClick = { if (!isLocked) sendCommand("seek&position=${maxOf(0, position - 5000)}") },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color.White, CircleShape)
                                ) {
                                    Icon(Icons.Default.FastRewind, contentDescription = "Rewind 5s", tint = Color(0xFF21005D))
                                }

                                IconButton(
                                    onClick = { if (!isLocked) sendCommand(if (isPlaying) "pause" else "play") },
                                    modifier = Modifier
                                        .size(64.dp)
                                        .background(Color(0xFF6750A4), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }

                                IconButton(
                                    onClick = { if (!isLocked) sendCommand("seek&position=${minOf(duration, position + 10000)}") },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color.White, CircleShape)
                                ) {
                                    Icon(Icons.Default.FastForward, contentDescription = "Forward 10s", tint = Color(0xFF21005D))
                                }

                                IconButton(
                                    onClick = { if (!isLocked) sendCommand("next") },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color.White, CircleShape)
                                ) {
                                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color(0xFF21005D))
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Button(
                                    onClick = { sendCommand("lock") },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isLocked) Color(0xFFB3261E) else Color(0xFFEADDFF),
                                        contentColor = if (isLocked) Color.White else Color(0xFF21005D)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                        contentDescription = "Lock"
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isLocked) "Unlock" else "Lock",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }

                                Button(
                                    onClick = { if (!isLocked) showBottomSheet = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .alpha(if (isLocked) 0.5f else 1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "More Options",
                                        tint = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "More",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                
                Text(
                    text = "Cast New Video",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF49454F),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Video List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isLocked) 0.5f else 1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = !isLocked
            ) {
                items(videoList) { video ->
                    val isCurrent = video.id == currentVideoId
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (!isLocked) sendCommand("play_video", video.id) },
                        colors = CardDefaults.cardColors(containerColor = if (isCurrent) Color(0xFFD3E3FD) else Color.White),
                        border = if (isCurrent) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCAC4D0)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.PlayArrow, 
                                contentDescription = "Play", 
                                tint = if (isCurrent) Color(0xFF001D35) else Color(0xFF6750A4)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = video.title,
                                fontSize = 16.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) Color(0xFF001D35) else Color(0xFF1C1B1F)
                            )
                        }
                    }
                }
            }
        }
    }
}
