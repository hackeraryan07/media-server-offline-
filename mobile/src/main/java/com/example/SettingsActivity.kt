package com.example

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        setContent {
            MyApplicationTheme {
                var apiKey by remember { mutableStateOf(sharedPrefs.getString("gemini_api_key", "") ?: "") }
                var modelName by remember { mutableStateOf(sharedPrefs.getString("gemini_model_name", "gemini-2.5-flash") ?: "gemini-2.5-flash") }
                var expanded by remember { mutableStateOf(false) }
                var availableModels by remember { mutableStateOf(listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-1.5-flash")) }
                var isFetchingModels by remember { mutableStateOf(false) }
                var isTesting by remember { mutableStateOf(false) }
                var testResult by remember { mutableStateOf<String?>(null) }
                var fetchError by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()

                LaunchedEffect(apiKey) {
                    if (apiKey.isNotBlank()) {
                        isFetchingModels = true
                        fetchError = null
                        try {
                            delay(800)
                            val models = AiHelper.fetchAvailableModels(apiKey)
                            if (models.isNotEmpty()) {
                                availableModels = models
                                if (!models.contains(modelName)) {
                                    modelName = models.first()
                                    sharedPrefs.edit().putString("gemini_model_name", modelName).apply()
                                }
                            }
                        } catch (e: Exception) {
                            fetchError = e.message
                        } finally {
                            isFetchingModels = false
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = "Settings",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.titleLarge
                                )
                            },
                            navigationIcon = {
                                IconButton(
                                    onClick = { finish() },
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
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .widthIn(max = 640.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Psychology,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "AI Integration (Gemini)",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Text(
                                    text = "Configure API access for automated smart video sorting and natural language playlist generation.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                OutlinedTextField(
                                    value = apiKey,
                                    onValueChange = {
                                        apiKey = it
                                        sharedPrefs.edit().putString("gemini_api_key", it).apply()
                                    },
                                    label = { Text("Gemini API Key") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Key,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp)
                                )

                                ExposedDropdownMenuBox(
                                    expanded = expanded,
                                    onExpandedChange = { expanded = it }
                                ) {
                                    OutlinedTextField(
                                        value = modelName,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Gemini Model") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    ExposedDropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false }
                                    ) {
                                        availableModels.forEach { selectionOption ->
                                            DropdownMenuItem(
                                                text = { Text(selectionOption) },
                                                onClick = {
                                                    modelName = selectionOption
                                                    sharedPrefs.edit().putString("gemini_model_name", selectionOption).apply()
                                                    expanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                if (isFetchingModels) {
                                    Text(
                                        text = "Fetching live models from Gemini API...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (fetchError != null) {
                                    Text(
                                        text = "Fetch error: $fetchError",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            isTesting = true
                                            testResult = null
                                            try {
                                                testResult = AiHelper.testApiKey(apiKey, modelName)
                                            } catch (e: Exception) {
                                                testResult = e.message ?: "Connection test failed."
                                            } finally {
                                                isTesting = false
                                            }
                                        }
                                    },
                                    enabled = apiKey.isNotBlank() && !isTesting,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(24.dp)
                                ) {
                                    if (isTesting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("Testing Connection...")
                                    } else {
                                        Text("Test API Connection", fontWeight = FontWeight.Medium)
                                    }
                                }

                                if (testResult != null) {
                                    val isSuccess = testResult?.startsWith("Success") == true
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        if (isSuccess) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Success",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Text(
                                            text = testResult ?: "",
                                            color = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSuccess) FontWeight.SemiBold else FontWeight.Normal
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
}
