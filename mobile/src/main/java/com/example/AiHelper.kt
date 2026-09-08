package com.example

import android.content.Context
import android.util.Log
import com.example.server.ServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AiHelper {
    data class AiPlaylistResult(val name: String, val ids: List<String>)

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1)) // Force HTTP/1.1 for better compatibility on older Android versions (like Android 9)
        .build()

    suspend fun fetchAvailableModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw Exception("API Key is empty")
        val url = "https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey}"
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Failed to fetch models: ${response.code}")
        }
        val body = response.body?.string() ?: throw Exception("Empty body")
        val json = JSONObject(body)
        val modelsArray = json.optJSONArray("models") ?: JSONArray()
        val result = mutableListOf<String>()
        for (i in 0 until modelsArray.length()) {
            val modelObj = modelsArray.optJSONObject(i)
            val name = modelObj?.optString("name") ?: continue
            if (name.startsWith("models/")) {
                result.add(name.substring(7))
            }
        }
        result.filter { it.contains("gemini") }
    }

    suspend fun testApiKey(apiKey: String, model: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw Exception("API Key is empty")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${apiKey}"
        val jsonRequest = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", "Hello, are you working?") })
                    })
                })
            })
        }
        val requestBody = jsonRequest.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(requestBody).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            throw Exception("Test failed: ${response.code} $errorBody")
        }
        "Success! Model is responding."
    }

    suspend fun generatePlaylist(context: Context, prompt: String): AiPlaylistResult = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        var apiKey = prefs.getString("gemini_api_key", "") ?: ""
        if (apiKey.isBlank()) {
            apiKey = BuildConfig.GEMINI_API_KEY
        }
        val model = prefs.getString("gemini_model_name", "gemini-3.5-flash") ?: "gemini-3.5-flash"

        if (apiKey.isBlank()) {
            throw Exception("API Key is missing. Please add it in Settings.")
        }

        // Get video list
        val videos = ServerManager.localVideoServer?.getVideosList() ?: emptyList<com.example.server.LocalVideoServer.SharedVideo>()
        if (videos.isEmpty()) {
            throw Exception("No videos available to create a playlist.")
        }

        val videoListJson = JSONArray()
        videos.forEach { video ->
            val obj = JSONObject()
            obj.put("id", video.id)
            obj.put("name", video.title)
            videoListJson.put(obj)
        }

        val systemPrompt = """
            You are an expert entertainment curator and playlist naming director for a video streaming application.
            Your role is to analyze the user's request, examine the available video library, and:

            1. INDEPENDENTLY DECIDE AND INVENT AN ORIGINAL PLAYLIST NAME:
               - You MUST autonomously decide a creative, appealing, and thematic title for the playlist.
               - DO NOT copy, echo, or repeat the user's prompt as the playlist name.
               - DO NOT use user command words or phrases in the playlist name (e.g. NEVER include words like "all", "give me", "find", "make", "create", "play", "videos", "episodes", "in ascending order", "chronological", "playlist of").
               - Examples of good AI-decided titles:
                 * User prompt "all taarak mehta episodes in ascending order" -> Title: "Gokuldham Chronicles" or "Taarak Mehta Marathon" (NEVER "all taarak mehta episodes in ascending order").
                 * User prompt "funny comedy videos" -> Title: "Laughter Therapy" or "Comedy Club Hits".
                 * User prompt "action movie scenes" -> Title: "Adrenaline Rush" or "Blockbuster Action".
               - The title should be concise (2 to 5 words), in Title Case, resembling an official curated playlist on Netflix, Spotify, or HBO Max.

            2. SELECT & ORDER VIDEOS:
               - Select video IDs from the available catalog that match the theme or request.
               - Arrange the video IDs in the best viewing order (e.g. chronological episode order if requested, or logical narrative flow).

            Available video catalog (JSON array of objects with 'id' and 'name'):
            $videoListJson

            Response format requirement:
            Return ONLY a valid JSON object with NO markdown backticks:
            {
              "playlistName": "Creative AI Decided Title Here",
              "videoIds": ["id1", "id2"]
            }
        """.trimIndent()

        val userMessage = """
            User Request: $prompt

            Instructions:
            1. Autonomously DECIDE an original, catchy, and creative playlist title for these videos. DO NOT use or repeat the user's request text as the title.
            2. Select the matching video IDs from the catalog in the desired playback order.
        """.trimIndent()

        val jsonRequest = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", userMessage) })
                    })
                })
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemPrompt) })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.7)
            })
        }
        
        val requestBody = jsonRequest.toString().toRequestBody("application/json".toMediaType())
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${apiKey}"
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
            
        val response = try {
            client.newCall(request).execute()
        } catch (e: java.net.UnknownHostException) {
            throw Exception("Network Error: Unable to resolve host. Please check your internet connection. (${e.message})")
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            throw Exception("SSL Error: Handshake failed. The device might not support the required TLS version. (${e.message})")
        } catch (e: java.net.SocketTimeoutException) {
            throw Exception("Timeout Error: The request took too long to complete. (${e.message})")
        } catch (e: java.io.IOException) {
            throw Exception("Network Error (${e.javaClass.simpleName}): ${e.message}")
        } catch (e: Exception) {
            throw Exception("Unexpected Error (${e.javaClass.simpleName}): ${e.message}")
        }
        
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            Log.e("AiHelper", "API Error body: $errorBody")
            if (response.code == 400) {
                throw Exception("Error 400: Bad Request. The prompt might be invalid.\nDetails: $errorBody")
            }
            if (response.code == 403) {
                throw Exception("Error 403: Forbidden. API Key invalid/restricted.\nDetails: $errorBody")
            }
            if (response.code == 429) {
                throw Exception("Error 429: Too Many Requests. You have exceeded your API quota.\nDetails: $errorBody")
            }
            if (response.code == 500) {
                throw Exception("Error 500: Internal Server Error. The Gemini API encountered an issue.\nDetails: $errorBody")
            }
            if (response.code == 503) {
                throw Exception("Error 503: Service Unavailable. The server is temporarily overloaded or down.\nDetails: $errorBody")
            }
            throw Exception("API Error: ${response.code} ${response.message}\n$errorBody")
        }
        
        val responseBodyString = response.body?.string() ?: throw Exception("Empty response from API")
        val jsonResponse = JSONObject(responseBodyString)
        val candidates = jsonResponse.optJSONArray("candidates")
        var text = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
            ?: throw Exception("Invalid response format from Gemini")

        text = text.trim()
        if (text.startsWith("```json")) {
            text = text.removePrefix("```json")
        } else if (text.startsWith("```")) {
            text = text.removePrefix("```")
        }
        if (text.endsWith("```")) {
            text = text.removeSuffix("```")
        }
        text = text.trim()
            
        var rawName = ""
        val resultIds = mutableListOf<String>()
        try {
            val jsonObject = JSONObject(text)
            rawName = when {
                jsonObject.has("playlistName") -> jsonObject.optString("playlistName")
                jsonObject.has("playlist_name") -> jsonObject.optString("playlist_name")
                jsonObject.has("title") -> jsonObject.optString("title")
                jsonObject.has("name") -> jsonObject.optString("name")
                else -> ""
            }
            val jsonArray = jsonObject.optJSONArray("videoIds")
                ?: jsonObject.optJSONArray("video_ids")
                ?: jsonObject.optJSONArray("videos")
                ?: jsonObject.optJSONArray("ids")
                ?: JSONArray()

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.opt(i)
                if (item is JSONObject) {
                    val id = item.optString("id")
                    if (id.isNotBlank()) resultIds.add(id)
                } else if (item is String) {
                    if (item.isNotBlank()) resultIds.add(item)
                }
            }
        } catch (e: Exception) {
            Log.e("AiHelper", "Error parsing response: $text", e)
            throw Exception("AI did not return a valid format.")
        }
        
        val matchedVideos = videos.filter { it.id in resultIds }
        val finalName = cleanAndValidatePlaylistName(rawName, prompt, matchedVideos)

        AiPlaylistResult(finalName, resultIds)
    }

    fun cleanAndValidatePlaylistName(
        rawName: String,
        prompt: String,
        matchedVideos: List<com.example.server.LocalVideoServer.SharedVideo>
    ): String {
        var clean = rawName.trim().trim('"', '\'', '`')

        val trimmedPrompt = prompt.trim()
        val isGeneric = clean.isBlank() ||
                clean.equals("AI Playlist", ignoreCase = true) ||
                clean.equals("Playlist", ignoreCase = true) ||
                clean.equals("Untitled Playlist", ignoreCase = true)

        val isPromptEcho = clean.equals(trimmedPrompt, ignoreCase = true) ||
                clean.lowercase().startsWith("all ") ||
                clean.lowercase().startsWith("give me ") ||
                clean.lowercase().startsWith("show me ") ||
                clean.lowercase().startsWith("create ") ||
                clean.lowercase().startsWith("make ") ||
                clean.lowercase().startsWith("play ") ||
                clean.lowercase().startsWith("find ") ||
                clean.lowercase().startsWith("playlist of ") ||
                clean.lowercase().endsWith(" in ascending order") ||
                clean.lowercase().endsWith(" in descending order") ||
                clean.lowercase().endsWith(" in chronological order")

        if (isGeneric || isPromptEcho) {
            // Clean noise from prompt
            var transformed = if (isGeneric) trimmedPrompt else clean

            // Remove prompt command prefixes
            val prefixRegex = Regex("(?i)^(please\\s+)?(create|make|generate|build|give me|show me|find|get|play|search for)?\\s*(a\\s+)?(playlist|queue|list)?\\s*(of|for|with)?\\s*")
            transformed = transformed.replace(prefixRegex, "").trim()

            val allPrefixRegex = Regex("(?i)^(all\\s+)")
            transformed = transformed.replace(allPrefixRegex, "").trim()

            // Remove order / sort suffixes
            val orderSuffixRegex = Regex("(?i)\\s+(in\\s+(ascending|descending|chronological)\\s+order|chronologically|by\\s+date|by\\s+name|by\\s+episode|in\\s+order)$")
            transformed = transformed.replace(orderSuffixRegex, "").trim()

            // If we have matched videos, see if they share a common series/show title
            if (matchedVideos.isNotEmpty()) {
                val commonSeriesName = findCommonSeriesName(matchedVideos.map { it.title })
                if (commonSeriesName.isNotBlank() && commonSeriesName.length >= 3) {
                    return toTitleCase(commonSeriesName) + " Marathon"
                }
            }

            // Convert transformed text to Title Case
            if (transformed.isNotBlank()) {
                val titleCased = toTitleCase(transformed)
                return if (!titleCased.contains("Collection", ignoreCase = true) &&
                    !titleCased.contains("Marathon", ignoreCase = true) &&
                    !titleCased.contains("Chronicles", ignoreCase = true) &&
                    !titleCased.contains("Hits", ignoreCase = true) &&
                    !titleCased.contains("Mix", ignoreCase = true) &&
                    !titleCased.contains("Club", ignoreCase = true)
                ) {
                    if (titleCased.length < 18) "$titleCased Collection" else titleCased
                } else {
                    titleCased
                }
            }

            return if (matchedVideos.isNotEmpty()) {
                val firstTitle = matchedVideos.first().title
                if (firstTitle.length > 25) "${firstTitle.take(22)}... Mix" else "$firstTitle Collection"
            } else {
                "Curated Collection"
            }
        }

        return toTitleCase(clean)
    }

    private fun findCommonSeriesName(titles: List<String>): String {
        if (titles.isEmpty()) return ""
        val first = titles.first()
        val basePrefix = first.substringBefore(" - ").substringBefore(":").substringBefore(" Episode").substringBefore(" Ep").trim()
        if (basePrefix.length >= 3 && titles.all { it.contains(basePrefix, ignoreCase = true) }) {
            return basePrefix
        }
        return ""
    }

    private fun toTitleCase(str: String): String {
        return str.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }
}
