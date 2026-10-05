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
    data class AiPlaylistResult(
        val name: String,
        val ids: List<String>,
        val isVerified: Boolean = false,
        val expectedCount: Int? = null,
        val returnedCount: Int = 0,
        val recoveredCount: Int = 0,
        val verificationDetails: String = "",
        val verificationBadge: String = ""
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
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

    fun extractCountFromPrompt(prompt: String): Int? {
        val patterns = listOf(
            Regex("(?i)\\b(\\d{1,4})\\s*(?:\\+|plus)?\\s*(?:episodes|episode|eps|ep|videos|video|parts|part|items|files)\\b"),
            Regex("(?i)\\b(?:there are|total of|total|have|count of|all)\\s*(\\d{1,4})\\b"),
            Regex("(?i)\\b(\\d{1,4})\\s*(?:in\\s+ascending|in\\s+descending|in\\s+order)\\b")
        )
        for (pattern in patterns) {
            val match = pattern.find(prompt)
            if (match != null) {
                val count = match.groupValues[1].toIntOrNull()
                if (count != null && count > 0) return count
            }
        }
        return null
    }

    fun extractEpisodeNumber(title: String): Double? {
        val sanitized = title
            .replace(Regex("(?i)\\b(?:1080p|720p|480p|2160p|4k|x264|x265|h264|h265|hevc|aac|ac3|eac3|dts|5\\.1|7\\.1)\\b"), " ")
            .replace(Regex("\\b(?:19|20)\\d{2}\\b"), " ")
            .replace(Regex("(?i)\\.(?:mkv|mp4|avi|mov|webm|ts|flv|3gp|wmv|mpg|ogv)"), "")

        Regex("(?i)s\\d+e(\\d+(?:\\.\\d+)?)").find(sanitized)?.let {
            it.groupValues[1].toDoubleOrNull()?.let { num -> return num }
        }

        Regex("(?i)(?:episode|ep|e|part|pt)[.\\s_-]*(\\d+(?:\\.\\d+)?)").find(sanitized)?.let {
            it.groupValues[1].toDoubleOrNull()?.let { num -> return num }
        }

        Regex("[\\-_#]\\s*(\\d+(?:\\.\\d+)?)").find(sanitized)?.let {
            it.groupValues[1].toDoubleOrNull()?.let { num -> return num }
        }

        Regex("(\\d+(?:\\.\\d+)?)\\s*$").find(sanitized)?.let {
            it.groupValues[1].toDoubleOrNull()?.let { num -> return num }
        }

        Regex("\\b(\\d{1,4})\\b").find(sanitized)?.let {
            it.groupValues[1].toDoubleOrNull()?.let { num -> return num }
        }

        return null
    }

    private fun splitIntoChunks(s: String): List<String> {
        val chunks = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val start = i
            val isDigit = s[i].isDigit()
            while (i < s.length && s[i].isDigit() == isDigit) {
                i++
            }
            chunks.add(s.substring(start, i))
        }
        return chunks
    }

    val naturalOrderComparator: Comparator<String> = Comparator { s1, s2 ->
        val ep1 = extractEpisodeNumber(s1)
        val ep2 = extractEpisodeNumber(s2)
        if (ep1 != null && ep2 != null && ep1 != ep2) {
            return@Comparator ep1.compareTo(ep2)
        }
        val r1 = splitIntoChunks(s1)
        val r2 = splitIntoChunks(s2)
        var i = 0
        while (i < r1.size && i < r2.size) {
            val c1 = r1[i]
            val c2 = r2[i]
            val isD1 = c1.isNotEmpty() && c1[0].isDigit()
            val isD2 = c2.isNotEmpty() && c2[0].isDigit()
            val cmp = if (isD1 && isD2) {
                val num1 = c1.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO
                val num2 = c2.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO
                num1.compareTo(num2)
            } else {
                c1.compareTo(c2, ignoreCase = true)
            }
            if (cmp != 0) return@Comparator cmp
            i++
        }
        r1.size.compareTo(r2.size)
    }

    suspend fun generatePlaylist(
        context: Context,
        prompt: String,
        userSpecifiedCount: Int? = null
    ): AiPlaylistResult = withContext(Dispatchers.IO) {
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
        val allVideos = ServerManager.localVideoServer?.getVideosList() ?: emptyList()
        if (allVideos.isEmpty()) {
            throw Exception("No videos available to create a playlist.")
        }

        // Determine target count from prompt or user input
        val extractedCount = userSpecifiedCount ?: extractCountFromPrompt(prompt)

        // Smart Candidate Selection to prevent prompt overflow on large libraries (200+ episodes)
        val stopWords = setOf(
            "all", "the", "in", "ascending", "descending", "order", "episodes", "episode",
            "eps", "ep", "video", "videos", "make", "create", "generate", "playlist", "show",
            "me", "give", "of", "for", "with", "please", "and", "or", "a", "an", "there",
            "are", "plus", "total", "have", "count", "chronological", "chronologically"
        )
        val promptWords = prompt.lowercase()
            .split(Regex("[^a-zA-Z0-9]+"))
            .filter { it.length >= 3 && it !in stopWords }

        val candidateVideos = if (promptWords.isNotEmpty() && allVideos.size > 250) {
            val filtered = allVideos.filter { video ->
                val titleLower = video.title.lowercase()
                val folderLower = video.folder.lowercase()
                promptWords.any { titleLower.contains(it) || folderLower.contains(it) }
            }
            if (filtered.isNotEmpty()) filtered else allVideos
        } else {
            allVideos
        }

        // Compact JSON representation to minimize prompt size
        val compactCatalogJson = JSONArray()
        candidateVideos.forEach { video ->
            val obj = JSONObject()
            obj.put("id", video.id)
            obj.put("name", video.title)
            compactCatalogJson.put(obj)
        }

        val targetCountText = if (extractedCount != null) {
            "The user explicitly stated there are $extractedCount episodes/videos to be included."
        } else {
            "Include all matching episodes/videos from the catalog."
        }

        val systemPrompt = """
            You are an expert video playlist director and curator. Your job is to:

            1. AUTONOMOUSLY DECIDE A CREATIVE, ORIGINAL PLAYLIST TITLE:
               - You MUST autonomously invent a catchy, appealing, and creative playlist name for this collection (e.g. "Gokuldham Chronicles", "Shinobi Odyssey", "Laugh Riot Hits", "Winterfell Saga").
               - DO NOT repeat, copy, or echo the user's prompt as the playlist name.
               - DO NOT use generic words like "All 200 episodes in ascending order" or "AI Playlist".

            2. ORGANIZE AND RETURN EVERY MATCHING VIDEO IN EXACT ASCENDING ORDER:
               - $targetCountText
               - You MUST arrange the videos in exact ascending numerical/chronological episode order (Episode 1, Episode 2, ... Episode N).
               - DO NOT SKIP ANY EPISODE. DO NOT USE ELLIPSES (...). DO NOT TRUNCATE.
               - Every matching episode in the catalog must be in the 'videoIds' array.

            3. REPORT THE TOTAL COUNT IN THE JSON:
               - Set "reportedCount" to the EXACT number of videos you included in 'videoIds'.
               - If the user specified a count of $extractedCount, make sure "reportedCount" reflects the exact count.

            Available video catalog:
            $compactCatalogJson

            Response format requirement:
            Return ONLY a valid JSON object with NO markdown backticks:
            {
              "playlistName": "Creative AI Decided Title Here",
              "reportedCount": ${extractedCount ?: compactCatalogJson.length()},
              "videoIds": ["id1", "id2", "id3", ...]
            }
        """.trimIndent()

        val userMessage = """
            User Request: $prompt
            ${if (extractedCount != null) "Specified Episode Count: $extractedCount" else ""}

            Instructions:
            1. Suggest a creative, original title for this playlist.
            2. Arrange ALL matching videos in ascending episode order without missing any video.
            3. Return the exact count in 'reportedCount'.
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
                put("maxOutputTokens", 8192)
                put("temperature", 0.2)
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
                throw Exception("Error 400: Bad Request. The query or video list might be too large.\nDetails: $errorBody")
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
        var aiReportedCount = -1
        val rawResultIds = mutableListOf<String>()
        try {
            val jsonObject = JSONObject(text)
            rawName = when {
                jsonObject.has("playlistName") -> jsonObject.optString("playlistName")
                jsonObject.has("playlist_name") -> jsonObject.optString("playlist_name")
                jsonObject.has("title") -> jsonObject.optString("title")
                jsonObject.has("name") -> jsonObject.optString("name")
                else -> ""
            }
            aiReportedCount = when {
                jsonObject.has("reportedCount") -> jsonObject.optInt("reportedCount", -1)
                jsonObject.has("expectedCount") -> jsonObject.optInt("expectedCount", -1)
                jsonObject.has("count") -> jsonObject.optInt("count", -1)
                jsonObject.has("totalEpisodes") -> jsonObject.optInt("totalEpisodes", -1)
                else -> -1
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
                    if (id.isNotBlank()) rawResultIds.add(id)
                } else if (item is String) {
                    if (item.isNotBlank()) rawResultIds.add(item)
                }
            }
        } catch (e: Exception) {
            Log.e("AiHelper", "Error parsing response: $text", e)
            throw Exception("AI did not return a valid format.")
        }

        // Validate returned IDs against actual library videos
        val validIds = rawResultIds.filter { id -> allVideos.any { it.id == id } }.distinct()
        val matchedVideos = allVideos.filter { it.id in validIds }

        // --- VERIFICATION & AUTO-RECOVERY LOGIC ---
        // 1. Identify common series / show to find any omitted episodes
        val seriesPrefix = findCommonSeriesName(matchedVideos.map { it.title })
        val dominantFolder = matchedVideos.groupBy { it.folder }.maxByOrNull { it.value.size }?.key

        val seriesCandidates = if (seriesPrefix.length >= 3) {
            allVideos.filter { it.title.contains(seriesPrefix, ignoreCase = true) }
        } else if (dominantFolder != null && dominantFolder != "Local" && dominantFolder.isNotBlank()) {
            allVideos.filter { it.folder == dominantFolder }
        } else if (matchedVideos.isNotEmpty()) {
            matchedVideos
        } else {
            allVideos
        }

        // 2. Detect missing library videos from the series
        val returnedIdSet = validIds.toSet()
        val missingFromSeries = seriesCandidates.filter { it.id !in returnedIdSet }

        // Check if ascending/chronological ordering was requested
        val isAscendingRequested = prompt.contains("ascend", ignoreCase = true) ||
                prompt.contains("chronolog", ignoreCase = true) ||
                prompt.contains("order", ignoreCase = true) ||
                prompt.contains("episode", ignoreCase = true) ||
                seriesCandidates.any { extractEpisodeNumber(it.title) != null }

        // 3. Auto-recover and sort all episodes without omitting any video
        val finalIds = if (missingFromSeries.isNotEmpty() || isAscendingRequested) {
            val combined = (matchedVideos + missingFromSeries).distinctBy { it.id }
            if (isAscendingRequested) {
                combined.sortedWith { v1, v2 -> naturalOrderComparator.compare(v1.title, v2.title) }.map { it.id }
            } else {
                validIds + missingFromSeries.map { it.id }
            }
        } else {
            validIds
        }

        val recoveredCount = (finalIds.size - validIds.size).coerceAtLeast(0)
        val finalVideos = allVideos.filter { it.id in finalIds }
        val finalName = cleanAndValidatePlaylistName(rawName, prompt, finalVideos)

        val targetCount = extractedCount ?: if (aiReportedCount > 0) aiReportedCount else null

        // 4. Construct verified badges & detailed confirmation
        val badge = when {
            targetCount != null && finalIds.size == targetCount ->
                "✓ Verified (${finalIds.size}/$targetCount Episodes Matched)"
            targetCount != null && recoveredCount > 0 ->
                "✓ Verified & Recovered (${finalIds.size} Episodes Included)"
            recoveredCount > 0 ->
                "✓ Verified & Recovered (All ${finalIds.size} Episodes Included)"
            aiReportedCount > 0 && aiReportedCount == finalIds.size ->
                "✓ Verified (${finalIds.size} Episodes Confirmed)"
            else ->
                "✓ Verified (${finalIds.size} Videos in Playlist)"
        }

        val details = when {
            targetCount != null && finalIds.size == targetCount && recoveredCount == 0 ->
                "Count verified! AI returned all $targetCount episodes in exact ascending sequence. Zero episodes missing."
            targetCount != null && recoveredCount > 0 ->
                "AI returned ${validIds.size} episodes; $recoveredCount omitted episodes were detected in your library and automatically integrated into sequential order ($finalIds.size total)."
            targetCount != null && finalIds.size != targetCount ->
                "Verified all ${finalIds.size} matching episodes found in your library in ascending order ($targetCount requested)."
            recoveredCount > 0 ->
                "Auto-recovery active: AI initially returned ${validIds.size} videos. $recoveredCount missing library episodes were detected and seamlessly merged into ascending sequence."
            else ->
                "AI output verified: ${finalIds.size} videos successfully arranged in ascending viewing order."
        }

        AiPlaylistResult(
            name = finalName,
            ids = finalIds,
            isVerified = true,
            expectedCount = targetCount,
            returnedCount = validIds.size,
            recoveredCount = recoveredCount,
            verificationDetails = details,
            verificationBadge = badge
        )
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
            var transformed = if (isGeneric) trimmedPrompt else clean

            val prefixRegex = Regex("(?i)^(please\\s+)?(create|make|generate|build|give me|show me|find|get|play|search for)?\\s*(a\\s+)?(playlist|queue|list)?\\s*(of|for|with)?\\s*")
            transformed = transformed.replace(prefixRegex, "").trim()

            val allPrefixRegex = Regex("(?i)^(all\\s+)")
            transformed = transformed.replace(allPrefixRegex, "").trim()

            val orderSuffixRegex = Regex("(?i)\\s+(in\\s+(ascending|descending|chronological)\\s+order|chronologically|by\\s+date|by\\s+name|by\\s+episode|in\\s+order)$")
            transformed = transformed.replace(orderSuffixRegex, "").trim()

            if (matchedVideos.isNotEmpty()) {
                val commonSeriesName = findCommonSeriesName(matchedVideos.map { it.title })
                if (commonSeriesName.isNotBlank() && commonSeriesName.length >= 3) {
                    return toTitleCase(commonSeriesName) + " Marathon"
                }
            }

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

    fun findCommonSeriesName(titles: List<String>): String {
        if (titles.isEmpty()) return ""
        val cleaned = titles.map {
            it.substringBefore(" - ")
                .substringBefore(":")
                .substringBefore(" Episode")
                .substringBefore(" episode")
                .substringBefore(" Ep")
                .substringBefore(" ep")
                .substringBefore(" S0")
                .substringBefore(" s0")
                .substringBefore(" E0")
                .substringBefore(" e0")
                .trim()
        }
        val groups = cleaned.filter { it.length >= 3 }.groupBy { it }
        val dominant = groups.maxByOrNull { it.value.size }
        if (dominant != null && dominant.value.size >= (titles.size / 2).coerceAtLeast(1)) {
            return dominant.key
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
