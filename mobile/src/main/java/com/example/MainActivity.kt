package com.example

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.example.db.AppDatabase
import com.example.db.Playlist
import com.example.db.PlaylistItem
import com.example.server.LocalVideoServer
import com.example.server.ServerManager
import com.example.server.ServerService
import com.example.ui.theme.GreenSuccess
import com.example.ui.theme.MyApplicationTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
                ServerDashboardScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun ServerDashboardScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val isServerRunning by ServerManager.isServerRunningFlow.collectAsStateWithLifecycle()
    val serverAddress by ServerManager.serverAddressFlow.collectAsStateWithLifecycle()
    val errorMessage by ServerManager.errorMessageFlow.collectAsStateWithLifecycle()
    val videoList by ServerManager.videosFlow.collectAsStateWithLifecycle()
    val connectedClients by ServerManager.connectedClientsFlow.collectAsStateWithLifecycle()

    var currentPage by remember { mutableIntStateOf(0) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showDevicePopupForVideo by remember { mutableStateOf<LocalVideoServer.SharedVideo?>(null) }
    var selectedTvIp by remember { mutableStateOf<String?>(null) }

    // Predictable hierarchical Back navigation
    BackHandler(enabled = selectedTvIp != null) {
        selectedTvIp = null
    }
    BackHandler(enabled = selectedTvIp == null && showDevicePopupForVideo != null) {
        showDevicePopupForVideo = null
    }
    BackHandler(enabled = selectedTvIp == null && showDevicePopupForVideo == null && selectedFolder != null) {
        selectedFolder = null
    }
    BackHandler(enabled = selectedTvIp == null && showDevicePopupForVideo == null && selectedFolder == null && searchQuery.isNotBlank()) {
        searchQuery = ""
    }
    BackHandler(enabled = selectedTvIp == null && showDevicePopupForVideo == null && selectedFolder == null && searchQuery.isBlank() && currentPage != 0) {
        currentPage = 0
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
            coroutineScope.launch {
                ServerManager.localVideoServer?.let { scanLocalMedia(context, it) }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, permissionToRequest) == PackageManager.PERMISSION_GRANTED) {
            ServerManager.localVideoServer?.let { scanLocalMedia(context, it) }
        } else {
            permissionLauncher.launch(permissionToRequest)
        }
    }

    val pickMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val name = getFileName(context, uri) ?: "Selected Mobile Stream"
                val size = getFileSize(context, uri)
                val randomId = "local_" + System.currentTimeMillis()
                ServerManager.localVideoServer?.addLocalVideo(randomId, name, uri, size)
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
                                    imageVector = Icons.Default.Sensors,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Text(
                            text = "StreamServer",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Open Settings",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                windowInsets = WindowInsets.navigationBars
            ) {
                NavigationBarItem(
                    selected = currentPage == 0,
                    onClick = { currentPage = 0 },
                    icon = {
                        Icon(
                            imageVector = if (currentPage == 0) Icons.Default.Dns else Icons.Outlined.Dns,
                            contentDescription = "Server tab"
                        )
                    },
                    label = { Text("Server") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                NavigationBarItem(
                    selected = currentPage == 1,
                    onClick = { currentPage = 1 },
                    icon = {
                        Icon(
                            imageVector = if (currentPage == 1) Icons.Default.VideoLibrary else Icons.Outlined.VideoLibrary,
                            contentDescription = "Library tab"
                        )
                    },
                    label = { Text("Library") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                NavigationBarItem(
                    selected = currentPage == 2,
                    onClick = { currentPage = 2 },
                    icon = {
                        Icon(
                            imageVector = if (currentPage == 2) Icons.Default.PlaylistPlay else Icons.Outlined.PlaylistPlay,
                            contentDescription = "Playlists tab"
                        )
                    },
                    label = { Text("Playlists") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        },
        floatingActionButton = {
            if (currentPage == 1) {
                ExtendedFloatingActionButton(
                    onClick = {
                        pickMediaLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = "Add video") },
                    text = { Text("Add Video") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                AnimatedContent(
                    targetState = currentPage,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
                    },
                    label = "TabTransition"
                ) { targetPage ->
                    when (targetPage) {
                        0 -> {
                            // SERVER TAB
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Server Active/Offline Control Card
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(28.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isServerRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(64.dp)
                                                .background(
                                                    color = if (isServerRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                    shape = CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (isServerRunning) Icons.Default.Wifi else Icons.Default.WifiOff,
                                                contentDescription = "Server Network Icon",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))

                                        if (isServerRunning && serverAddress != null) {
                                            Text(
                                                text = "Local Server Active",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleLarge,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "http://$serverAddress",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "Local Discovery: MobileStreamServer",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                            )
                                        } else {
                                            Text(
                                                text = "Server Offline",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleLarge,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "Tap the button below to stream videos to TV",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        if (errorMessage != null) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "Note: $errorMessage",
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall,
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
                                                containerColor = if (isServerRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
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

                                // Connected TV Units Section
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "CONNECTED TV UNITS",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        letterSpacing = 1.2.sp
                                    )
                                    if (connectedClients.isNotEmpty()) {
                                        Text(
                                            text = "${connectedClients.size} active",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = GreenSuccess
                                        )
                                    }
                                }

                                if (connectedClients.isEmpty()) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(20.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        ),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .padding(20.dp)
                                                .fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Tv,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                modifier = Modifier.size(32.dp)
                                            )
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Column {
                                                Text(
                                                    text = "No TV clients connected",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = "Launch TV client on same Wi-Fi to start streaming",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.fillMaxWidth().weight(1f)
                                    ) {
                                        items(items = connectedClients, key = { it.ip }) { client ->
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { selectedTvIp = client.ip },
                                                shape = RoundedCornerShape(18.dp),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                                ),
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(16.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                                                contentDescription = "TV Unit",
                                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                                            )
                                                        }
                                                        Spacer(modifier = Modifier.width(14.dp))
                                                        Column {
                                                            Text(
                                                                text = "${client.name} (${client.ip})",
                                                                fontWeight = FontWeight.SemiBold,
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                color = MaterialTheme.colorScheme.onSurface
                                                            )
                                                            Text(
                                                                text = "Streaming Active • Tap for Remote",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                fontWeight = FontWeight.Medium,
                                                                color = GreenSuccess
                                                            )
                                                        }
                                                    }
                                                    Box(
                                                        modifier = Modifier
                                                            .size(10.dp)
                                                            .background(GreenSuccess, CircleShape)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        1 -> {
                            // LIBRARY TAB
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("library_search_input"),
                                    placeholder = { Text("Search shared media...") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = "Search icon",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(
                                                onClick = { searchQuery = "" },
                                                modifier = Modifier.minimumInteractiveComponentSize()
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Clear,
                                                    contentDescription = "Clear search",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(28.dp)
                                )

                                if (searchQuery.isNotEmpty()) {
                                    val searchResults = remember(videoList, searchQuery) {
                                        videoList.filter { it.title.contains(searchQuery, ignoreCase = true) }
                                    }
                                    Text(
                                        text = "Search Results (${searchResults.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    if (searchResults.isEmpty()) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().weight(1f),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(
                                                    imageVector = Icons.Default.SearchOff,
                                                    contentDescription = "No results",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                    modifier = Modifier.size(48.dp)
                                                )
                                                Spacer(modifier = Modifier.height(12.dp))
                                                Text(
                                                    text = "No matching videos found.",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                        }
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            items(
                                                items = searchResults,
                                                key = { it.id },
                                                contentType = { "video_item" }
                                            ) { video ->
                                                VideoItemRow(
                                                    video = video,
                                                    onClick = { showDevicePopupForVideo = video }
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    // Folder view or video list
                                    if (selectedFolder == null) {
                                        Text(
                                            text = "Media Folders",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )

                                        val folderGroups = remember(videoList) {
                                            videoList.groupBy { it.folder ?: "Videos" }
                                        }
                                        val allFolders = remember(folderGroups) {
                                            listOf("All Videos") + folderGroups.keys.toList()
                                        }

                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(2),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            items(items = allFolders, key = { it }) { folderName ->
                                                Card(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .aspectRatio(1.1f)
                                                        .clickable { selectedFolder = folderName },
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                                    ),
                                                    shape = RoundedCornerShape(20.dp),
                                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                                ) {
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        verticalArrangement = Arrangement.Center,
                                                        modifier = Modifier.fillMaxSize().padding(12.dp)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(48.dp)
                                                                .background(
                                                                    MaterialTheme.colorScheme.primaryContainer,
                                                                    CircleShape
                                                                ),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Folder,
                                                                contentDescription = "Folder",
                                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                                modifier = Modifier.size(26.dp)
                                                            )
                                                        }
                                                        Spacer(modifier = Modifier.height(10.dp))
                                                        Text(
                                                            text = folderName,
                                                            fontWeight = FontWeight.Bold,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        val count = if (folderName == "All Videos") videoList.size else folderGroups[folderName]?.size ?: 0
                                                        Text(
                                                            text = "$count videos",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { selectedFolder = null }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "Back to folders",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = selectedFolder ?: "",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }

                                        val displayedVideos = remember(videoList, selectedFolder) {
                                            if (selectedFolder == "All Videos") {
                                                videoList
                                            } else {
                                                videoList.filter { (it.folder ?: "Videos") == selectedFolder }
                                            }
                                        }

                                        if (displayedVideos.isEmpty()) {
                                            Box(
                                                modifier = Modifier.fillMaxWidth().weight(1f),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "No videos in this folder.",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        } else {
                                            LazyColumn(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                items(
                                                    items = displayedVideos,
                                                    key = { it.id },
                                                    contentType = { "video_item" }
                                                ) { video ->
                                                    VideoItemRow(
                                                        video = video,
                                                        onClick = { showDevicePopupForVideo = video }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        2 -> {
                            // PLAYLISTS TAB
                            val db = remember { AppDatabase.getDatabase(context) }
                            val playlists by remember { db.playlistDao().getAllPlaylistsWithItemsFlow() }.collectAsState(initial = emptyList())
                            var newPlaylistName by remember { mutableStateOf("") }
                            var showAiDialog by remember { mutableStateOf(false) }

                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "Playlists & Queues",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedTextField(
                                        value = newPlaylistName,
                                        onValueChange = { newPlaylistName = it },
                                        placeholder = { Text("New Playlist Name") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    Button(
                                        onClick = {
                                            if (newPlaylistName.isNotBlank()) {
                                                val name = newPlaylistName.trim()
                                                newPlaylistName = ""
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    db.playlistDao().insertPlaylistSync(Playlist(name = name))
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.height(56.dp)
                                    ) {
                                        Text("Create")
                                    }
                                }

                                FilledTonalButton(
                                    onClick = { showAiDialog = true },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = "AI Generation",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Smart AI Playlist Generator", fontWeight = FontWeight.Bold)
                                }

                                if (showAiDialog) {
                                    var aiPrompt by remember { mutableStateOf("") }
                                    var aiExpectedCount by remember { mutableStateOf("") }
                                    var aiLoading by remember { mutableStateOf(false) }
                                    var aiError by remember { mutableStateOf<String?>(null) }
                                    var generatedResult by remember { mutableStateOf<AiHelper.AiPlaylistResult?>(null) }
                                    var decidedPlaylistName by remember { mutableStateOf("") }

                                    AlertDialog(
                                        onDismissRequest = { if (!aiLoading) showAiDialog = false },
                                        shape = RoundedCornerShape(24.dp),
                                        title = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.AutoAwesome,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text(
                                                    text = if (generatedResult == null) "AI Playlist Creator" else "AI Generated Playlist",
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.titleMedium
                                                )
                                            }
                                        },
                                        text = {
                                            Column(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                if (generatedResult == null) {
                                                    Text(
                                                        text = "Describe your desired show or episodes (e.g. 'Science series episode 1 to 200 in ascending order'). AI will arrange all videos chronologically and suggest a playlist name.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    OutlinedTextField(
                                                        value = aiPrompt,
                                                        onValueChange = { aiPrompt = it },
                                                        label = { Text("What should AI create?") },
                                                        placeholder = { Text("E.g. all 200 episodes in order") },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        enabled = !aiLoading,
                                                        shape = RoundedCornerShape(12.dp)
                                                    )
                                                    OutlinedTextField(
                                                        value = aiExpectedCount,
                                                        onValueChange = { if (it.all { char -> char.isDigit() }) aiExpectedCount = it },
                                                        label = { Text("Episode / Video Count (Optional)") },
                                                        placeholder = { Text("E.g. 200") },
                                                        singleLine = true,
                                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                        modifier = Modifier.fillMaxWidth(),
                                                        enabled = !aiLoading,
                                                        shape = RoundedCornerShape(12.dp)
                                                    )
                                                    Text(
                                                        text = "💡 Tip: Giving the episode count verifies that zero videos are missed.",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                } else {
                                                    Text(
                                                        text = "AI Suggested Title:",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    OutlinedTextField(
                                                        value = decidedPlaylistName,
                                                        onValueChange = { decidedPlaylistName = it },
                                                        label = { Text("Playlist Name") },
                                                        singleLine = true,
                                                        modifier = Modifier.fillMaxWidth(),
                                                        shape = RoundedCornerShape(12.dp)
                                                    )
                                                    Card(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = if (generatedResult!!.isVerified) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                                        ),
                                                        shape = RoundedCornerShape(12.dp)
                                                    ) {
                                                        Column(modifier = Modifier.padding(12.dp)) {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Icon(
                                                                    imageVector = Icons.Default.CheckCircle,
                                                                    contentDescription = "Verified",
                                                                    tint = if (generatedResult!!.isVerified) GreenSuccess else MaterialTheme.colorScheme.primary,
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                                Spacer(modifier = Modifier.width(6.dp))
                                                                Text(
                                                                    text = generatedResult!!.verificationBadge,
                                                                    fontWeight = FontWeight.Bold,
                                                                    style = MaterialTheme.typography.bodyMedium
                                                                )
                                                            }
                                                            if (generatedResult!!.verificationDetails.isNotBlank()) {
                                                                Spacer(modifier = Modifier.height(4.dp))
                                                                Text(
                                                                    text = generatedResult!!.verificationDetails,
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                            }
                                                        }
                                                    }
                                                    Text(
                                                        text = "Ordered Episodes (${generatedResult!!.ids.size} videos):",
                                                        fontWeight = FontWeight.SemiBold,
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                    Card(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                                        ),
                                                        shape = RoundedCornerShape(10.dp)
                                                    ) {
                                                        val matched = generatedResult!!.ids.mapIndexed { idx, id ->
                                                            val v = videoList.find { it.id == id }
                                                            val title = v?.title ?: "Video $id"
                                                            "#${idx + 1}  $title"
                                                        }
                                                        LazyColumn(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .heightIn(max = 140.dp)
                                                                .padding(8.dp),
                                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                                        ) {
                                                            items(matched) { itemText ->
                                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.PlayArrow,
                                                                        contentDescription = null,
                                                                        modifier = Modifier.size(14.dp),
                                                                        tint = MaterialTheme.colorScheme.primary
                                                                    )
                                                                    Spacer(modifier = Modifier.width(6.dp))
                                                                    Text(
                                                                        text = itemText,
                                                                        style = MaterialTheme.typography.bodySmall,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }

                                                if (aiError != null) {
                                                    Text(
                                                        text = aiError!!,
                                                        color = MaterialTheme.colorScheme.error,
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                                if (aiLoading) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier
                                                            .align(Alignment.CenterHorizontally)
                                                            .size(32.dp),
                                                        strokeWidth = 3.dp
                                                    )
                                                }
                                            }
                                        },
                                        confirmButton = {
                                            if (generatedResult == null) {
                                                Button(
                                                    onClick = {
                                                        if (aiPrompt.isNotBlank()) {
                                                            aiLoading = true
                                                            aiError = null
                                                            coroutineScope.launch {
                                                                try {
                                                                    val userCount = aiExpectedCount.toIntOrNull()
                                                                    val aiResult = AiHelper.generatePlaylist(context, aiPrompt, userCount)
                                                                    if (aiResult.ids.isEmpty()) {
                                                                        aiError = "No matching videos found in your library."
                                                                        aiLoading = false
                                                                    } else {
                                                                        generatedResult = aiResult
                                                                        decidedPlaylistName = aiResult.name
                                                                        aiLoading = false
                                                                    }
                                                                } catch (e: Exception) {
                                                                    aiError = e.message ?: "An error occurred."
                                                                    aiLoading = false
                                                                }
                                                            }
                                                        }
                                                    },
                                                    enabled = !aiLoading && aiPrompt.isNotBlank()
                                                ) {
                                                    Text("Generate")
                                                }
                                            } else {
                                                Button(
                                                    onClick = {
                                                        coroutineScope.launch(Dispatchers.IO) {
                                                            try {
                                                                val finalTitle = decidedPlaylistName.trim().ifBlank { "AI Playlist" }
                                                                val newId = db.playlistDao().insertPlaylistSync(Playlist(name = finalTitle)).toInt()

                                                                generatedResult!!.ids.forEachIndexed { index, vId ->
                                                                    db.playlistDao().insertPlaylistItemSync(
                                                                        PlaylistItem(
                                                                            playlistId = newId,
                                                                            videoId = vId,
                                                                            displayOrder = index
                                                                        )
                                                                    )
                                                                }
                                                                showAiDialog = false
                                                            } catch (e: Exception) {
                                                                aiError = e.message ?: "Failed to save playlist."
                                                            }
                                                        }
                                                    },
                                                    enabled = !aiLoading
                                                ) {
                                                    Text("Save Playlist")
                                                }
                                            }
                                        },
                                        dismissButton = {
                                            if (generatedResult != null) {
                                                TextButton(
                                                    onClick = {
                                                        generatedResult = null
                                                        aiError = null
                                                    },
                                                    enabled = !aiLoading
                                                ) {
                                                    Text("Change Prompt")
                                                }
                                            } else {
                                                TextButton(
                                                    onClick = { if (!aiLoading) showAiDialog = false },
                                                    enabled = !aiLoading
                                                ) {
                                                    Text("Cancel")
                                                }
                                            }
                                        }
                                    )
                                }

                                if (playlists.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().weight(1f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                imageVector = Icons.Default.PlaylistPlay,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                modifier = Modifier.size(52.dp)
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Text(
                                                text = "No playlists created yet",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                } else {
                                    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }

                                    LazyColumn(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(items = playlists, key = { it.playlist.id }) { playlistInfo ->
                                            Card(
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(16.dp),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                                ),
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(16.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = playlistInfo.playlist.name,
                                                            fontWeight = FontWeight.Bold,
                                                            style = MaterialTheme.typography.bodyLarge,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        Text(
                                                            text = "${playlistInfo.items.size} videos",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = { playlistToDelete = playlistInfo.playlist },
                                                        modifier = Modifier.minimumInteractiveComponentSize()
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Delete,
                                                            contentDescription = "Delete Playlist",
                                                            tint = MaterialTheme.colorScheme.error
                                                        )
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
                                                        coroutineScope.launch(Dispatchers.IO) {
                                                            db.playlistDao().deletePlaylistSync(p.id)
                                                        }
                                                    }
                                                    playlistToDelete = null
                                                }) {
                                                    Text("Delete", color = MaterialTheme.colorScheme.error)
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
            }
        }
    }

    if (showDevicePopupForVideo != null) {
        val video = showDevicePopupForVideo!!
        val db = remember { AppDatabase.getDatabase(context) }
        val playlists by remember { db.playlistDao().getAllPlaylistsFlow() }.collectAsState(initial = emptyList())

        AlertDialog(
            onDismissRequest = { showDevicePopupForVideo = null },
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = "Play or Queue",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Play on TV",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    if (connectedClients.isEmpty()) {
                        Text(
                            text = "No connected TV clients found.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                            items(items = connectedClients, key = { it.ip }) { client ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                try {
                                                    val url = "http://${client.ip}:9000/command?action=play_video&id=${video.id}"
                                                    val request = Request.Builder().url(url).build()
                                                    OkHttpClient().newCall(request).execute().close()
                                                } catch (e: Exception) {
                                                    Log.e("Popup", "Command failed: play_video", e)
                                                }
                                            }
                                            showDevicePopupForVideo = null
                                        }
                                        .padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Tv,
                                            contentDescription = "TV",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(text = client.name, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Add to Playlist",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    if (playlists.isEmpty()) {
                        Text(
                            text = "No playlists available.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                            items(items = playlists, key = { it.id }) { playlist ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                val maxOrder = db.playlistDao().getMaxDisplayOrderSync(playlist.id)
                                                db.playlistDao().insertPlaylistItemSync(
                                                    PlaylistItem(
                                                        playlistId = playlist.id,
                                                        videoId = video.id,
                                                        displayOrder = maxOrder + 1
                                                    )
                                                )
                                            }
                                            showDevicePopupForVideo = null
                                        }
                                        .padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlaylistPlay,
                                            contentDescription = "Playlist",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
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
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                RemoteScreen(tvIp = selectedTvIp!!, onBack = { selectedTvIp = null })
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun VideoItemRow(
    video: LocalVideoServer.SharedVideo,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(
                        color = if (video.isLocal) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                val imageModel: Any? = if (video.isLocal && video.uriString.isNotEmpty()) {
                    Uri.parse(video.uriString)
                } else if (video.thumbnailUrl.isNotEmpty()) {
                    video.thumbnailUrl
                } else null

                if (imageModel != null) {
                    GlideImage(
                        model = imageModel,
                        contentDescription = "Thumbnail",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    ) { requestBuilder ->
                        requestBuilder
                            .override(128, 128)
                            .centerCrop()
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.AUTOMATIC)
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play icon",
                        tint = if (video.isLocal) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (video.isLocal) {
                        "Local file • ${formatBytes(video.size)}"
                    } else {
                        "Cloud video • ${video.duration}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (video.totalDuration > 0L && video.watchedPosition > 0L) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (video.watchedPosition.toFloat() / video.totalDuration.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                }
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
    var size = 0L
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
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format("%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

private suspend fun scanLocalMedia(context: Context, localVideoServer: LocalVideoServer) = kotlinx.coroutines.withContext(Dispatchers.IO) {
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
        val existingIds = localVideoServer.getExistingVideoIds()
        val newVideos = mutableListOf<LocalVideoServer.SharedVideo>()

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
                val strId = "local_media_$id"
                if (strId !in existingIds) {
                    val name = cursor.getString(nameColumn) ?: "Unknown Video"
                    val size = cursor.getLong(sizeColumn)
                    val folder = cursor.getString(bucketColumn) ?: "Internal Storage"

                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    val thumbUrl = "http://127.0.0.1:8999/thumbnail/$strId"
                    newVideos.add(
                        LocalVideoServer.SharedVideo(
                            id = strId,
                            title = name,
                            uriString = contentUri.toString(),
                            size = size,
                            duration = "Local",
                            isLocal = true,
                            folder = folder,
                            thumbnailUrl = thumbUrl
                        )
                    )
                }
            }
        }
        if (newVideos.isNotEmpty()) {
            localVideoServer.addLocalVideosBatch(newVideos)
        }
    } catch (e: Exception) {
        Log.e("MainActivity", "Error scanning local media", e)
    }
}
