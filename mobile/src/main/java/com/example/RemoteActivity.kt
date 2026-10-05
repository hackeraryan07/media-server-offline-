package com.example

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.server.AudioSyncService
import com.example.server.ServerManager
import com.example.server.TvControlService
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

        val serviceIntent = Intent(this, TvControlService::class.java).apply {
            action = TvControlService.ACTION_START
            putExtra(TvControlService.EXTRA_TV_IP, tvIp)
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
        val serviceIntent = Intent(this, TvControlService::class.java).apply {
            action = TvControlService.ACTION_STOP
        }
        startService(serviceIntent)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RemoteScreen(tvIp: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    BackHandler {
        onBack()
    }

    DisposableEffect(tvIp) {
        val serviceIntent = Intent(context, TvControlService::class.java).apply {
            action = TvControlService.ACTION_START
            putExtra(TvControlService.EXTRA_TV_IP, tvIp)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        onDispose {
            val stopIntent = Intent(context, TvControlService::class.java).apply {
                action = TvControlService.ACTION_STOP
            }
            context.startService(stopIntent)
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf<String?>(null) }
    var currentVideoId by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var positionUpdateTime by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var isShifting by remember { mutableStateOf(false) }
    var needsResumeChoice by remember { mutableStateOf(false) }
    var resumePosition by remember { mutableLongStateOf(0L) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var needsSpeedChoice by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var needsAudioShiftChoice by remember { mutableStateOf(false) }
    var showAudioShiftDialog by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var audioShiftMs by remember { mutableLongStateOf(0L) }
    var isRemoteAudioEnabled by remember { mutableStateOf(false) }
    var isContinuousSyncEnabled by remember { mutableStateOf(true) }
    var videoUrl by remember { mutableStateOf("") }

    val options = remember(isMuted, isLocked, isRemoteAudioEnabled) {
        listOf(
            OptionItem(if (isMuted) "Unmute" else "Mute", if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, "mute", "Toggle audio volume"),
            OptionItem(if (isRemoteAudioEnabled) "Remote Audio On" else "Remote Audio Off", Icons.Default.Audiotrack, "audio_track", "Toggle remote audio sync"),
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
            val intent = Intent(context, AudioSyncService::class.java).apply {
                action = AudioSyncService.ACTION_START
                putExtra(AudioSyncService.EXTRA_TV_IP, tvIp)
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

    // Polling loop with coroutine safety
    LaunchedEffect(tvIp) {
        while (isActive) {
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
        coroutineScope.launch(Dispatchers.IO) {
            try {
                var url = "http://$tvIp:9000/command?action=$action"
                if (extraId != null) url += "&id=$extraId"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.e("RemoteActivity", "Command failed: $action", e)
            }
        }
    }

    if (needsResumeChoice) {
        var timeRemainingMs by remember { mutableLongStateOf(10000L) }
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
                        coroutineScope.launch(Dispatchers.IO) {
                            val request = Request.Builder().url("http://$tvIp:9000/command?action=resume_choice&choice=start_over").build()
                            client.newCall(request).execute().close()
                        }
                        needsResumeChoice = false
                    }
                    delay(50)
                }
            }
        }

        AlertDialog(
            onDismissRequest = { /* force choice */ },
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = "Resume Playback?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                val mins = (resumePosition / 1000) / 60
                val secs = (resumePosition / 1000) % 60
                val timeStr = String.format("%d:%02d", mins, secs)
                Text(
                    text = "Would you like to resume \"${currentTitle ?: "this video"}\" from $timeStr?",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            val request = Request.Builder().url("http://$tvIp:9000/command?action=resume_choice&choice=continue").build()
                            client.newCall(request).execute().close()
                        }
                        needsResumeChoice = false
                    }
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            val request = Request.Builder().url("http://$tvIp:9000/command?action=resume_choice&choice=start_over").build()
                            client.newCall(request).execute().close()
                        }
                        needsResumeChoice = false
                    },
                    modifier = Modifier.focusRequester(startOverFocusRequester)
                ) {
                    val secsLeft = (timeRemainingMs / 1000) + 1
                    Text("Start Over ($secsLeft)")
                }
            }
        )
    }

    if (showAudioShiftDialog || needsAudioShiftChoice) {
        AlertDialog(
            onDismissRequest = {
                showAudioShiftDialog = false
                needsAudioShiftChoice = false
                coroutineScope.launch(Dispatchers.IO) {
                    val request = Request.Builder().url("http://$tvIp:9000/command?action=cancel_audio_shift").build()
                    client.newCall(request).execute().close()
                }
            },
            shape = RoundedCornerShape(24.dp),
            title = { Text("Audio Shift Calibration", fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = String.format("%.2fs", audioShiftMs / 1000f),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = {
                                val newShift = (audioShiftMs - 100).coerceAtLeast(-60000)
                                audioShiftMs = newShift
                                coroutineScope.launch(Dispatchers.IO) {
                                    val request = Request.Builder().url("http://$tvIp:9000/command?action=set_audio_shift&value=$newShift").build()
                                    client.newCall(request).execute().close()
                                }
                            }
                        ) {
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
                                coroutineScope.launch(Dispatchers.IO) {
                                    val request = Request.Builder().url("http://$tvIp:9000/command?action=set_audio_shift&value=$audioShiftMs").build()
                                    client.newCall(request).execute().close()
                                }
                            },
                            valueRange = -60000f..60000f,
                            steps = 1199,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                        FilledTonalButton(
                            onClick = {
                                val newShift = (audioShiftMs + 100).coerceAtMost(60000)
                                audioShiftMs = newShift
                                coroutineScope.launch(Dispatchers.IO) {
                                    val request = Request.Builder().url("http://$tvIp:9000/command?action=set_audio_shift&value=$newShift").build()
                                    client.newCall(request).execute().close()
                                }
                            }
                        ) {
                            Text("+0.1s")
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = { sendCommand("toggle_continuous_sync") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isContinuousSyncEnabled) "Stop Continuous Sync" else "Start Continuous Sync")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAudioShiftDialog = false
                        needsAudioShiftChoice = false
                        coroutineScope.launch(Dispatchers.IO) {
                            val request = Request.Builder().url("http://$tvIp:9000/command?action=cancel_audio_shift").build()
                            client.newCall(request).execute().close()
                        }
                    }
                ) {
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
            shape = RoundedCornerShape(24.dp),
            title = { Text("Playback Speed", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    speeds.forEach { (label, value) ->
                        val isSelected = kotlin.math.abs(value - currentSpeed) < 0.01f
                        FilledTonalButton(
                            onClick = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    val request = Request.Builder().url("http://$tvIp:9000/command?action=set_speed&value=$value").build()
                                    client.newCall(request).execute().close()
                                }
                                showSpeedDialog = false
                                needsSpeedChoice = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (isSelected) ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ) else ButtonDefaults.filledTonalButtonColors()
                        ) {
                            Text(label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSpeedDialog = false
                    needsSpeedChoice = false
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
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
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
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
                                            if (option.action == "audio_track") {
                                                isRemoteAudioEnabled = !isRemoteAudioEnabled
                                            }
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
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(14.dp)
                                    .fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = option.icon,
                                        contentDescription = option.label,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = option.label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = option.description,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 12.sp,
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
                title = {
                    Text(
                        text = "TV Remote Controls",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // Now Playing Hero Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "NOW PLAYING ON TV",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            letterSpacing = 1.2.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = currentTitle ?: "No Media Playing",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        // Seek Slider
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
                                .padding(horizontal = 6.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f)
                            )
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Player Transport Controls
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (isLocked) 0.5f else 1f),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalIconButton(
                                onClick = { if (!isLocked) sendCommand("prev") },
                                modifier = Modifier
                                    .size(48.dp)
                                    .minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Track")
                            }

                            FilledTonalIconButton(
                                onClick = { if (!isLocked) sendCommand("seek&position=${maxOf(0, position - 5000)}") },
                                modifier = Modifier
                                    .size(48.dp)
                                    .minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Default.FastRewind, contentDescription = "Rewind 5 seconds")
                            }

                            IconButton(
                                onClick = { if (!isLocked) sendCommand(if (isPlaying) "pause" else "play") },
                                modifier = Modifier
                                    .size(60.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause video" else "Play video",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(34.dp)
                                )
                            }

                            FilledTonalIconButton(
                                onClick = { if (!isLocked) sendCommand("seek&position=${minOf(duration, position + 10000)}") },
                                modifier = Modifier
                                    .size(48.dp)
                                    .minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Default.FastForward, contentDescription = "Forward 10 seconds")
                            }

                            FilledTonalIconButton(
                                onClick = { if (!isLocked) sendCommand("next") },
                                modifier = Modifier
                                    .size(48.dp)
                                    .minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Default.SkipNext, contentDescription = "Next Track")
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { sendCommand("lock") },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                                    contentColor = if (isLocked) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondary
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "Lock controls"
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isLocked) "Unlock" else "Lock",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }

                            Button(
                                onClick = { if (!isLocked) showBottomSheet = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .alpha(if (isLocked) 0.5f else 1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More TV Options"
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Options",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "Cast Video to TV",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
            }

            // Video List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isLocked) 0.5f else 1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = !isLocked
            ) {
                items(videoList) { video ->
                    val isCurrent = video.id == currentVideoId
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (!isLocked) sendCommand("play_video", video.id) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        border = if (isCurrent) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(
                                        if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play video",
                                    tint = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = video.title,
                                    fontSize = 15.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isCurrent) "Currently Playing" else "Tap to Play on TV",
                                    fontSize = 11.sp,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
