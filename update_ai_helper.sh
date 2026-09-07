sed -i '/suspend fun generatePlaylist/i \
    suspend fun fetchAvailableModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {\
        if (apiKey.isBlank()) throw Exception("API Key is empty")\
        val url = "https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey}"\
        val request = Request.Builder().url(url).get().build()\
        val response = client.newCall(request).execute()\
        if (!response.isSuccessful) {\
            throw Exception("Failed to fetch models: ${response.code}")\
        }\
        val body = response.body?.string() ?: throw Exception("Empty body")\
        val json = JSONObject(body)\
        val modelsArray = json.optJSONArray("models") ?: JSONArray()\
        val result = mutableListOf<String>()\
        for (i in 0 until modelsArray.length()) {\
            val modelObj = modelsArray.optJSONObject(i)\
            val name = modelObj?.optString("name") ?: continue\
            if (name.startsWith("models/")) {\
                result.add(name.substring(7))\
            }\
        }\
        result.filter { it.contains("gemini") }\
    }\
\
    suspend fun testApiKey(apiKey: String, model: String): String = withContext(Dispatchers.IO) {\
        if (apiKey.isBlank()) throw Exception("API Key is empty")\
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${apiKey}"\
        val jsonRequest = JSONObject().apply {\
            put("contents", JSONArray().apply {\
                put(JSONObject().apply {\
                    put("parts", JSONArray().apply {\
                        put(JSONObject().apply { put("text", "Hello, are you working?") })\
                    })\
                })\
            })\
        }\
        val requestBody = jsonRequest.toString().toRequestBody("application/json".toMediaType())\
        val request = Request.Builder().url(url).post(requestBody).build()\
        val response = client.newCall(request).execute()\
        if (!response.isSuccessful) {\
            val errorBody = response.body?.string() ?: ""\
            throw Exception("Test failed: ${response.code} $errorBody")\
        }\
        "Success! Model is responding."\
    }\
' mobile/src/main/java/com/example/AiHelper.kt
