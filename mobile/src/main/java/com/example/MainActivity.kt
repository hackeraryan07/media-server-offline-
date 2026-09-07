package com.example

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.bumptech.glide.integration.compose.GlideImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.server.LocalVideoServer
import com.example.server.NsdServerPublisher
import com.example.server.ServerManager
import com.example.server.ServerService
import android.content.Intent
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.Tv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ServerManager.initialize(applicationContext)

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    ServerDashboardScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDashboardScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isServerRunning by remember { mutableStateOf(ServerManager.isServerRunning) }
    var serverAddress by remember { mutableStateOf(ServerManager.serverAddress) }
    var errorMessage by remember { mutableStateOf(ServerManager.errorMessage) }
    var videoList by remember { mutableStateOf(ServerManager.localVideoServer?.getVideosList() ?: emptyList()) }
    var currentPage by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var connectedClients by remember { mutableStateOf(ServerManager.localVideoServer?.getConnectedClients() ?: emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var showDevicePopupForVideo by remember { mutableStateOf<LocalVideoServer.SharedVideo?>(null) }
    var selectedTvIp by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            isServerRunning = ServerManager.isServerRunning
            serverAddress = ServerManager.serverAddress
            errorMessage = ServerManager.errorMessage
            if (isServerRunning) {
                connectedClients = ServerManager.localVideoServer?.getConnectedClients() ?: emptyList()
            }
            videoList = ServerManager.localVideoServer?.getVideosList() ?: emptyList()
        }
    }

    val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            ServerManager.localVideoServer?.let { scanLocalMedia(context, it) }
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, permissionToRequest) == PackageManager.PERMISSION_GRANTED) {
            ServerManager.localVideoServer?.let { scanLocalMedia(context, it) }
        } else {
            permissionLauncher.launch(permissionToRequest)
        }
    }

    // Visual media picker contract (completely permission-free for media retrieval!)
    val pickMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = getFileName(context, uri) ?: "Selected Mobile Stream"
            val size = getFileSize(context, uri)
            val randomId = "local_" + System.currentTimeMillis()
            ServerManager.localVideoServer?.addLocalVideo(randomId, name, uri, size)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF7F2FA)) // ThemeBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp) // Leave elegant room for Bottom Navigation simulation
        ) {
            // Top Custom Premium App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(onClick = {}) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu icon",
                            tint = Color(0xFF1C1B1F)
                        )
                    }
                    Text(
                        text = "StreamServer",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1C1B1F)
                    )
                }

                IconButton(onClick = {
                    context.startActivity(android.content.Intent(context, SettingsActivity::class.java))
                }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings icon",
                        tint = Color(0xFF1C1B1F)
                    )
                }
            }

            // Scrollable Content

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (currentPage == 0) {
                        // SERVER TAB CONTENT
                    // Server Active/Offline Control Card (Central sections)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 20.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isServerRunning) Color(0xFFEADDFF) else Color(0xFFF3EDF7)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Stack/Server circle badge
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(
                                    color = if (isServerRunning) Color(0xFF21005D) else Color(0xFF49454F),
                                    shape = RoundedCornerShape(32.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Server Network Icon",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (isServerRunning && serverAddress != null) {
                            Text(
                                text = "Local Server Active",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = Color(0xFF21005D)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "http://$serverAddress",
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF49454F)
                            )
                            Text(
                                text = "Local Discovery Name: MobileStreamServer",
                                fontSize = 11.sp,
                                color = Color(0xFF49454F).copy(alpha = 0.8f)
                            )
                        } else {
                            Text(
                                text = "Server Offline",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = Color(0xFF49454F)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap button below to host Wi-Fi streams",
                                fontSize = 13.sp,
                                color = Color(0xFF49454F).copy(alpha = 0.9f)
                            )
                        }

                        if (errorMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Note: $errorMessage",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = {
                                val intent = Intent(context, ServerService::class.java).apply {
                                    action = if (ServerManager.isServerRunning) ServerService.ACTION_STOP else ServerService.ACTION_START
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ServerManager.isServerRunning) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("server_toggle_button"),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isServerRunning) Color(0xFF6750A4) else Color(0xFF21005D),
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = if (isServerRunning) "Stop Server" else "Start Server",
                                fontWeight = FontWeight.Medium,
                                fontSize = 15.sp
                            )
                        }
                    }
                }

                // Discovered TV Units Section (Adding outstanding visual fidelity matching HTML mock!)
                    Text(
                        text = "Connected TV Units",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF49454F),
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                    )

                    if (connectedClients.isEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, Color(0xFFCAC4D0))
                        ) {
                            Text(
                                text = "No units currently streaming",
                                modifier = Modifier.padding(16.dp),
                                fontSize = 14.sp,
                                color = Color(0xFF49454F)
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        ) {
                            items(connectedClients) { client ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        selectedTvIp = client.ip
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    border = BorderStroke(1.dp, Color(0xFFCAC4D0))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .background(Color(0xFFF3EDF7), shape = RoundedCornerShape(12.dp)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PlayArrow,
                                                    contentDescription = "TV",
                                                    tint = Color(0xFF6750A4)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(14.dp))
                                            Column {
                                                Text(
                                                    text = "${client.name} (${client.ip})",
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 14.sp,
                                                    color = Color(0xFF1C1B1F)
                                                )
                                                Text(
                                                    text = "Streaming Active",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    color = Color(0xFF22C55E)
                                                )
                                            }
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(Color(0xFF22C55E), shape = RoundedCornerShape(4.dp))
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (currentPage == 1) { // LIBRARY TAB CONTENT
                    // Elegant Outlined Search Bar at the top of Library
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .testTag("library_search_input"),
                        placeholder = { Text("Search shared media...", color = Color(0xFF49454F).copy(alpha = 0.7f)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search icon",
                                tint = Color(0xFF6750A4)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear search",
                                        tint = Color(0xFF49454F)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6750A4),
                            unfocusedBorderColor = Color(0xFFCAC4D0),
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White
                        )
                    )

                    if (searchQuery.isNotEmpty()) {
                        // Global search filtering across all folders
                        val searchResults = videoList.filter { it.title.contains(searchQuery, ignoreCase = true) }
                        
                        Text(
                            text = "Search Results (${searchResults.size})",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1C1B1F),
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        if (searchResults.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "No results",
                                        tint = Color(0xFF49454F).copy(alpha = 0.5f),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "No matching videos found.",
                                        color = Color(0xFF49454F),
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(searchResults) { video ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().clickable { showDevicePopupForVideo = video },
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color.White),
                                        border = BorderStroke(1.dp, Color(0xFFCAC4D0))
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(50.dp)
                                                    .background(
                                                        color = if (video.isLocal) Color(0xFFEADDFF) else Color(0xFFD3E3FD),
                                                        shape = RoundedCornerShape(8.dp)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (video.thumbnailUrl.isNotEmpty()) {
                                                    @OptIn(com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi::class)
                                                    GlideImage(
                                                        model = video.thumbnailUrl,
                                                        contentDescription = "Thumbnail",
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = "Play icon",
                                                        tint = if (video.isLocal) Color(0xFF21005D) else Color(0xFF001D35)
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.width(14.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = video.title,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 14.sp,
                                                    color = Color(0xFF1C1B1F),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = if (video.isLocal) {
                                                        "Local file shared • size: ${formatBytes(video.size)}"
                                                    } else {
                                                        "Cloud video • Sync: ${video.duration}"
                                                    },
                                                    fontSize = 11.sp,
                                                    color = Color(0xFF49454F)
                                                )
                                                if (video.totalDuration > 0L && video.watchedPosition > 0L) {
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    LinearProgressIndicator(
                                                        progress = (video.watchedPosition.toFloat() / video.totalDuration.toFloat()).coerceIn(0f, 1f),
                                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                                        color = Color(0xFFE11D48),
                                                        trackColor = Color(0xFFFFD1D1)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Regular flow: folder view or standard folder-specific video list
                        if (selectedFolder == null) {
                            Text(
                                text = "Library Folders",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1C1B1F),
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            val folderGroups = videoList.groupBy { it.folder ?: "Videos" }
                            val allFolders = listOf("All Videos") + folderGroups.keys.toList()

                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                items(allFolders) { folderName ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clickable { selectedFolder = folderName },
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFEADDFF)),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Folder,
                                                contentDescription = "Folder",
                                                tint = Color(0xFF21005D),
                                                modifier = Modifier.size(48.dp)
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                text = folderName,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = Color(0xFF21005D)
                                            )
                                            val count = if (folderName == "All Videos") videoList.size else folderGroups[folderName]?.size ?: 0
                                            Text(
                                                text = "$count videos",
                                                fontSize = 12.sp,
                                                color = Color(0xFF21005D).copy(alpha = 0.8f)
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { selectedFolder = null }) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = selectedFolder ?: "",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1C1B1F)
                                    )
                                }
                            }

                            val displayedVideos = if (selectedFolder == "All Videos") {
                                videoList
                            } else {
                                videoList.filter { (it.folder ?: "Videos") == selectedFolder }
                            }

                            if (displayedVideos.isEmpty()) {
                                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                    Text("No videos in this folder.", color = Color(0xFF49454F))
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(displayedVideos) { video ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth().clickable { showDevicePopupForVideo = video },
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.cardColors(containerColor = Color.White),
                                            border = BorderStroke(1.dp, Color(0xFFCAC4D0))
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(50.dp)
                                                        .background(
                                                            color = if (video.isLocal) Color(0xFFEADDFF) else Color(0xFFD3E3FD),
                                                            shape = RoundedCornerShape(8.dp)
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (video.thumbnailUrl.isNotEmpty()) {
                                                        @OptIn(com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi::class)
                                                        GlideImage(
                                                            model = video.thumbnailUrl,
                                                            contentDescription = "Thumbnail",
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    } else {
                                                        Icon(
                                                            imageVector = Icons.Default.PlayArrow,
                                                            contentDescription = "Play icon",
                                                            tint = if (video.isLocal) Color(0xFF21005D) else Color(0xFF001D35)
                                                        )
                                                    }
                                                }

                                                Spacer(modifier = Modifier.width(14.dp))

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = video.title,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontSize = 14.sp,
                                                        color = Color(0xFF1C1B1F),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = if (video.isLocal) {
                                                            "Local file shared • size: ${formatBytes(video.size)}"
                                                        } else {
                                                            "Cloud video • Sync: ${video.duration}"
                                                        },
                                                        fontSize = 11.sp,
                                                        color = Color(0xFF49454F)
                                                    )
                                                    if (video.totalDuration > 0L && video.watchedPosition > 0L) {
                                                        Spacer(modifier = Modifier.height(6.dp))
                                                        LinearProgressIndicator(
                                                            progress = (video.watchedPosition.toFloat() / video.totalDuration.toFloat()).coerceIn(0f, 1f),
                                                            modifier = Modifier.fillMaxWidth().height(4.dp),
                                                            color = Color(0xFFE11D48),
                                                            trackColor = Color(0xFFFFD1D1)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (currentPage == 2) {
                    val db = remember { com.example.db.AppDatabase.getDatabase(context) }
                    val playlists by remember { db.playlistDao().getAllPlaylistsWithItemsFlow() }.collectAsState(initial = emptyList())
                    var newPlaylistName by remember { mutableStateOf("") }
                    
                    var showAiDialog by remember { mutableStateOf(false) }
                    
                    Text(
                        text = "Playlists & Queues",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1C1B1F),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newPlaylistName,
                            onValueChange = { newPlaylistName = it },
                            placeholder = { Text("New Playlist Name") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF6750A4),
                                unfocusedBorderColor = Color(0xFFCAC4D0),
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newPlaylistName.isNotBlank()) {
                                    val name = newPlaylistName
                                    newPlaylistName = ""
                                    CoroutineScope(Dispatchers.IO).launch {
                                        db.playlistDao().insertPlaylistSync(com.example.db.Playlist(name = name))
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Create")
                        }
                    }
                    
                    Button(
                        onClick = { showAiDialog = true },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21005D)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Star, contentDescription = "AI", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generate Playlist with AI")
                    }
                    
                    if (showAiDialog) {
                        var aiPrompt by remember { mutableStateOf("") }
                        var aiLoading by remember { mutableStateOf(false) }
                        var aiError by remember { mutableStateOf<String?>(null) }
                        val coroutineScope = rememberCoroutineScope()
                        
                        AlertDialog(
                            onDismissRequest = { if (!aiLoading) showAiDialog = false },
                            title = { Text("Generate Playlist with AI", fontWeight = FontWeight.Bold) },
                            text = {
                                Column {
                                    Text("Describe what you want (e.g. 'all taarak mehta episodes in ascending order').")
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = aiPrompt,
                                        onValueChange = { aiPrompt = it },
                                        placeholder = { Text("Your prompt...") },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !aiLoading
                                    )
                                    if (aiError != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(aiError!!, color = Color.Red, fontSize = 12.sp)
                                    }
                                    if (aiLoading) {
                                        Spacer(modifier = Modifier.height(16.dp))
                                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        if (aiPrompt.isNotBlank()) {
                                            aiLoading = true
                                            aiError = null
                                            coroutineScope.launch {
                                                try {
                                                    val aiResult = AiHelper.generatePlaylist(context, aiPrompt)
                                                    if (aiResult.ids.isEmpty()) {
                                                        aiError = "No matching videos found."
                                                        aiLoading = false
                                                    } else {
                                                        val db = com.example.db.AppDatabase.getDatabase(context)
                                                        val newId = db.playlistDao().insertPlaylistSync(com.example.db.Playlist(name = aiResult.name)).toInt()
                                                        
                                                        // Insert items
                                                        aiResult.ids.forEachIndexed { index, vId ->
                                                            db.playlistDao().insertPlaylistItemSync(
                                                                com.example.db.PlaylistItem(
                                                                    playlistId = newId,
                                                                    videoId = vId,
                                                                    displayOrder = index
                                                                )
                                                            )
                                                        }
                                                        
                                                        showAiDialog = false
                                                    }
                                                } catch (e: Exception) {
                                                    aiError = e.message ?: "An error occurred."
                                                    aiLoading = false
                                                }
                                            }
                                        }
                                    },
                                    enabled = !aiLoading
                                ) {
                                    Text("Generate & Save")
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { showAiDialog = false },
                                    enabled = !aiLoading
                                ) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                    
                    if (playlists.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text("No playlists yet.", color = Color(0xFF49454F))
                        }
                    } else {
                        var playlistToDelete by remember { mutableStateOf<com.example.db.Playlist?>(null) }

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(playlists) { playlistInfo ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    border = BorderStroke(1.dp, Color(0xFFCAC4D0))
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(playlistInfo.playlist.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF1C1B1F))
                                            Text("${playlistInfo.items.size} videos", fontSize = 12.sp, color = Color(0xFF49454F))
                                        }
                                        IconButton(onClick = {
                                            playlistToDelete = playlistInfo.playlist
                                        }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                                        }
                                    }
                                }
                            }
                        }

                        if (playlistToDelete != null) {
                            AlertDialog(
                                onDismissRequest = { playlistToDelete = null },
                                title = { Text("Delete Playlist") },
                                text = { Text("Are you sure you want to delete '${playlistToDelete?.name}'?") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        playlistToDelete?.let { p ->
                                            CoroutineScope(Dispatchers.IO).launch {
                                                db.playlistDao().deletePlaylistSync(p.id)
                                            }
                                        }
                                        playlistToDelete = null
                                    }) {
                                        Text("Delete", color = Color.Red)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { playlistToDelete = null }) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        }



        // Bottom Navigation Bar simulation (Material 3 premium feel)
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(80.dp),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Server (Active)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            currentPage = 0
                        }
                        .padding(vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (currentPage == 0) Color(0xFFEADDFF) else Color.Transparent,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Server tab",
                            tint = if (currentPage == 0) Color(0xFF21005D) else Color(0xFF49454F),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Server",
                        fontSize = 11.sp,
                        fontWeight = if (currentPage == 0) FontWeight.Bold else FontWeight.Medium,
                        color = if (currentPage == 0) Color(0xFF21005D) else Color(0xFF49454F)
                    )
                }

                // Library
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            currentPage = 1
                        }
                        .padding(vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (currentPage == 1) Color(0xFFEADDFF) else Color.Transparent,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Library tab",
                            tint = if (currentPage == 1) Color(0xFF21005D) else Color(0xFF49454F),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Library",
                        fontSize = 11.sp,
                        fontWeight = if (currentPage == 1) FontWeight.Bold else FontWeight.Medium,
                        color = if (currentPage == 1) Color(0xFF21005D) else Color(0xFF49454F)
                    )
                }

                // Analytics
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            currentPage = 2
                        }
                        .padding(vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (currentPage == 2) Color(0xFFEADDFF) else Color.Transparent,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Playlists tab",
                            tint = if (currentPage == 2) Color(0xFF21005D) else Color(0xFF49454F),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Playlists",
                        fontSize = 11.sp,
                        fontWeight = if (currentPage == 2) FontWeight.Bold else FontWeight.Medium,
                        color = if (currentPage == 2) Color(0xFF21005D) else Color(0xFF49454F)
                    )
                }
            }
        }
    }

    if (showDevicePopupForVideo != null) {
        val video = showDevicePopupForVideo!!
        val db = remember { com.example.db.AppDatabase.getDatabase(context) }
        val playlists by remember { db.playlistDao().getAllPlaylistsFlow() }.collectAsState(initial = emptyList())
        
        AlertDialog(
            onDismissRequest = { showDevicePopupForVideo = null },
            title = { Text(text = "Play or Queue", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Play on TV", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    if (connectedClients.isEmpty()) {
                        Text("No connected devices found.", color = Color(0xFF49454F))
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                            items(connectedClients) { client ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        CoroutineScope(Dispatchers.IO).launch {
                                            try {
                                                val url = "http://${client.ip}:9000/command?action=play_video&id=${video.id}"
                                                val request = Request.Builder().url(url).build()
                                                OkHttpClient().newCall(request).execute().close()
                                            } catch (e: Exception) {
                                                Log.e("Popup", "Command failed: play_video", e)
                                            }
                                        }
                                        showDevicePopupForVideo = null
                                    }.padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    border = BorderStroke(1.dp, Color(0xFFCAC4D0))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Tv, contentDescription = "TV", tint = Color(0xFF6750A4))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(text = client.name, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Add to Playlist", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    if (playlists.isEmpty()) {
                        Text("No playlists available.", color = Color(0xFF49454F))
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                            items(playlists) { playlist ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        CoroutineScope(Dispatchers.IO).launch {
                                            val maxOrder = db.playlistDao().getMaxDisplayOrderSync(playlist.id)
                                            db.playlistDao().insertPlaylistItemSync(
                                                com.example.db.PlaylistItem(
                                                    playlistId = playlist.id,
                                                    videoId = video.id,
                                                    displayOrder = maxOrder + 1
                                                )
                                            )
                                        }
                                        showDevicePopupForVideo = null
                                    }.padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    border = BorderStroke(1.dp, Color(0xFFCAC4D0))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Menu, contentDescription = "Playlist", tint = Color(0xFF6750A4))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(text = playlist.name, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDevicePopupForVideo = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (selectedTvIp != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedTvIp = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFFF7F2FA),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                RemoteScreen(tvIp = selectedTvIp!!, onBack = { selectedTvIp = null })
            }
        }
    }
}

private fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = cursor.getString(index)
                }
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/')
        if (cut != null && cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result
}

private fun getFileSize(context: Context, uri: Uri): Long {
    var size: Long = 0L
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index != -1) {
                    size = cursor.getLong(index)
                }
            }
        } finally {
            cursor?.close()
        }
    }
    return size
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

private fun scanLocalMedia(context: Context, localVideoServer: LocalVideoServer) {
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME
    )

    try {
        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "Unknown Video"
                val size = cursor.getLong(sizeColumn)
                val folder = cursor.getString(bucketColumn) ?: "Internal Storage"

                val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                val strId = "local_media_$id"
                // Check if already exist to prevent dupes if rescanned
                if (localVideoServer.getVideosList().none { it.id == strId }) {
                    localVideoServer.addLocalVideo(strId, name, contentUri, size, folder)
                }
            }
        }
    } catch (e: Exception) {
        Log.e("MainActivity", "Error scanning local media", e)
    }
}
