package com.example.server

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import com.example.db.AppDatabase
import com.example.db.Playlist
import com.example.db.PlaylistItem
import org.json.JSONArray
import org.json.JSONObject

class LocalVideoServer(
    private val context: Context,
    private val port: Int = 8999
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val sharedFiles = ConcurrentHashMap<String, SharedVideo>()
    private val appDatabase: AppDatabase by lazy { AppDatabase.getDatabase(context) }

    data class SharedVideo(
        val id: String,
        val title: String,
        val uriString: String,
        val size: Long,
        val duration: String = "Unknown",
        val isLocal: Boolean,
        val folder: String,
        val thumbnailUrl: String = "",
        val watchedPosition: Long = 0L,
        val totalDuration: Long = 0L
    )

    init {
        // No presets loaded. Waiting for local files to be shared.
    }

    fun addLocalVideo(id: String, title: String, uri: Uri, size: Long, folder: String = "Local") {
        val thumbUrl = "http://127.0.0.1:$port/thumbnail/$id"
        sharedFiles[id] = SharedVideo(id, title, uri.toString(), size, "Local", isLocal = true, folder = folder, thumbnailUrl = thumbUrl)
    }

    fun getVideosList(): List<SharedVideo> {
        val prefs = context.getSharedPreferences("video_progress", Context.MODE_PRIVATE)
        return sharedFiles.values.map { video ->
            val watchedPosition = prefs.getLong("progress_${video.id}", 0L)
            val totalDuration = prefs.getLong("duration_${video.id}", 0L)
            video.copy(watchedPosition = watchedPosition, totalDuration = totalDuration)
        }
    }

    fun removeVideo(id: String) {
        if (sharedFiles[id]?.isLocal == true) {
            sharedFiles.remove(id)
        }
    }

    fun start(onStatusChange: (Boolean, String?) -> Unit) {
        if (isRunning) return
        isRunning = true
        Thread {
            try {
                serverSocket = ServerSocket(port)
                val localIp = getLocalIpAddress()
                onStatusChange(true, "$localIp:$port")
                Log.d("LocalVideoServer", "Server listening on $localIp:$port")

                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    Thread {
                        handleClient(socket)
                    }.start()
                }
            } catch (e: Exception) {
                Log.e("LocalVideoServer", "Server custom process encountered close/error", e)
                onStatusChange(false, e.localizedMessage)
            } finally {
                isRunning = false
                onStatusChange(false, null)
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e("LocalVideoServer", "Error closing server socket", e)
        }
        serverSocket = null
    }

    data class ConnectedClient(val ip: String, val name: String)
    private val connectedClientsMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun getConnectedClients(): List<ConnectedClient> = connectedClientsMap.map { ConnectedClient(it.key, it.value) }

    private fun handleClient(socket: Socket) {
        try {
            val rawClientIp = socket.inetAddress.hostAddress ?: "Unknown IP"
            val clientIp = rawClientIp.replaceFirst("::ffff:", "")
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val firstLine = reader.readLine() ?: return
            Log.d("LocalVideoServer", "Client Request: $firstLine")

            val parts = firstLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            // We can add client IP directly on any request, or verify it's the TV app asking.
            // Check if deviceName is in the path
            val query = path.substringAfter("?", "")
            val params = parseQueryString(query)
            val deviceName = params["deviceName"] ?: "Unknown Device"

            val isLoopback = clientIp == "127.0.0.1" || clientIp == "::1" || clientIp == "0:0:0:0:0:0:0:1"
            if (!isLoopback) {
                if (path.startsWith("/videos")) {
                    connectedClientsMap[clientIp] = deviceName
                } else if (!connectedClientsMap.containsKey(clientIp)) {
                    connectedClientsMap[clientIp] = deviceName
                }
            }

            if (method != "GET") {
                sendSimpleResponse(socket, "405 Method Not Allowed", "text/plain", "Only GET requests are supported")
                return
            }

            // Read range or connection headers
            var line: String?
            var rangeHeader: String? = null
            while (true) {
                line = reader.readLine()
                if (line.isNullOrEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter("Range:").trim()
                }
            }

            val pathWithoutQuery = path.substringBefore("?")
            when {
                pathWithoutQuery == "/videos" -> {
                    val json = buildVideosJson()
                    sendSimpleResponse(socket, "200 OK", "application/json", json)
                }
                pathWithoutQuery == "/playlists" -> {
                    try {
                        val playlists = appDatabase.playlistDao().getAllPlaylistsSync()
                        val jsonArray = JSONArray()
                        for (playlistWithItems in playlists) {
                            val pObj = JSONObject()
                            pObj.put("id", playlistWithItems.playlist.id)
                            pObj.put("name", playlistWithItems.playlist.name)
                            pObj.put("timestamp", playlistWithItems.playlist.timestamp)
                            val itemsArray = JSONArray()
                            for (item in playlistWithItems.items) {
                                val itemObj = JSONObject()
                                itemObj.put("id", item.id)
                                itemObj.put("videoId", item.videoId)
                                itemObj.put("displayOrder", item.displayOrder)
                                itemsArray.put(itemObj)
                            }
                            pObj.put("items", itemsArray)
                            jsonArray.put(pObj)
                        }
                        sendSimpleResponse(socket, "200 OK", "application/json", jsonArray.toString())
                    } catch (e: Exception) {
                        Log.e("LocalVideoServer", "Error getting playlists", e)
                        sendSimpleResponse(socket, "500 Internal Error", "text/plain", "Error getting playlists")
                    }
                }
                pathWithoutQuery == "/playlists/create" -> {
                    val params = parseQueryString(query)
                    val name = params["name"]
                    if (name != null) {
                        try {
                            appDatabase.playlistDao().insertPlaylistSync(Playlist(name = name))
                            sendSimpleResponse(socket, "200 OK", "application/json", "{\"status\":\"success\"}")
                        } catch(e: Exception) {
                            sendSimpleResponse(socket, "500 Internal Error", "text/plain", "DB Error")
                        }
                    } else {
                        sendSimpleResponse(socket, "400 Bad Request", "text/plain", "Missing name")
                    }
                }
                pathWithoutQuery == "/playlists/add" -> {
                    val params = parseQueryString(query)
                    val playlistIdStr = params["playlistId"]
                    val videoId = params["videoId"]
                    if (playlistIdStr != null && videoId != null) {
                        try {
                            val playlistId = playlistIdStr.toInt()
                            val maxOrder = appDatabase.playlistDao().getMaxDisplayOrderSync(playlistId)
                            appDatabase.playlistDao().insertPlaylistItemSync(PlaylistItem(playlistId = playlistId, videoId = videoId, displayOrder = maxOrder + 1))
                            sendSimpleResponse(socket, "200 OK", "application/json", "{\"status\":\"success\"}")
                        } catch(e: Exception) {
                            sendSimpleResponse(socket, "500 Internal Error", "text/plain", "DB Error")
                        }
                    } else {
                        sendSimpleResponse(socket, "400 Bad Request", "text/plain", "Missing params")
                    }
                }
                pathWithoutQuery == "/playlists/remove" -> {
                    val params = parseQueryString(query)
                    val playlistIdStr = params["playlistId"]
                    val videoId = params["videoId"]
                    if (playlistIdStr != null && videoId != null) {
                        try {
                            appDatabase.playlistDao().removePlaylistItemSync(playlistIdStr.toInt(), videoId)
                            sendSimpleResponse(socket, "200 OK", "application/json", "{\"status\":\"success\"}")
                        } catch(e: Exception) {
                            sendSimpleResponse(socket, "500 Internal Error", "text/plain", "DB Error")
                        }
                    } else {
                        sendSimpleResponse(socket, "400 Bad Request", "text/plain", "Missing params")
                    }
                }
                pathWithoutQuery == "/playlists/quickqueue" -> {
                    val params = parseQueryString(query)
                    val videoId = params["videoId"]
                    val queueName = params["name"] ?: "Up Next"
                    if (videoId != null) {
                        try {
                            val db = appDatabase.playlistDao()
                            var playlists = db.getAllPlaylistsSync()
                            var queuePlaylist = playlists.find { it.playlist.name == queueName }?.playlist
                            
                            if (queuePlaylist == null) {
                                val newId = db.insertPlaylistSync(Playlist(name = queueName)).toInt()
                                queuePlaylist = Playlist(id = newId, name = queueName)
                            }
                            
                            val maxOrder = db.getMaxDisplayOrderSync(queuePlaylist.id)
                            db.insertPlaylistItemSync(PlaylistItem(playlistId = queuePlaylist.id, videoId = videoId, displayOrder = maxOrder + 1))
                            
                            sendSimpleResponse(socket, "200 OK", "application/json", "{\"status\":\"success\"}")
                        } catch(e: Exception) {
                            sendSimpleResponse(socket, "500 Internal Error", "text/plain", "DB Error")
                        }
                    } else {
                        sendSimpleResponse(socket, "400 Bad Request", "text/plain", "Missing videoId")
                    }
                }
                pathWithoutQuery == "/update_progress" -> {
                    val query = path.substringAfter("?", "")
                    val params = parseQueryString(query)
                    val id = params["id"]
                    val positionStr = params["position"]
                    val durationStr = params["duration"]
                    
                    if (id != null && positionStr != null && durationStr != null) {
                        try {
                            val position = positionStr.toLong()
                            val duration = durationStr.toLong()
                            
                            val prefs = context.getSharedPreferences("video_progress", Context.MODE_PRIVATE)
                            prefs.edit()
                                .putLong("progress_$id", position)
                                .putLong("duration_$id", duration)
                                .apply()
                            
                            sendSimpleResponse(socket, "200 OK", "application/json", "{\"status\":\"success\"}")
                        } catch (e: Exception) {
                            sendSimpleResponse(socket, "400 Bad Request", "text/plain", "Invalid values")
                        }
                    } else {
                        sendSimpleResponse(socket, "400 Bad Request", "text/plain", "Missing parameters")
                    }
                }
                path.startsWith("/stream/") -> {
                    val videoId = path.substringAfter("/stream/")
                    val video = sharedFiles[videoId]
                    if (video == null) {
                        sendSimpleResponse(socket, "404 Not Found", "text/plain", "Shared Video ID not discovered")
                        return
                    }
                    if (video.isLocal) {
                        streamLocalFile(socket, Uri.parse(video.uriString), rangeHeader)
                    } else {
                        // Direct stream redirection
                        sendRedirect(socket, video.uriString)
                    }
                }
                path.startsWith("/thumbnail/") -> {
                    val videoId = path.substringAfter("/thumbnail/")
                    val video = sharedFiles[videoId]
                    if (video == null) {
                        sendSimpleResponse(socket, "404 Not Found", "text/plain", "Shared Video ID not discovered")
                        return
                    }
                    if (video.isLocal) {
                        streamLocalThumbnail(socket, videoId, Uri.parse(video.uriString))
                    } else {
                        sendRedirect(socket, "https://storage.googleapis.com/gtv-videos-bucket/sample/images/BigBuckBunny.jpg")
                    }
                }
                else -> {
                    sendSimpleResponse(socket, "404 Not Found", "text/plain", "Unknown api endpoint")
                }
            }
        } catch (e: Exception) {
            Log.e("LocalVideoServer", "Exception inside request loop", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // ignore close errors
            }
        }
    }

    private fun streamLocalThumbnail(socket: Socket, videoId: String, uri: Uri) {
        val out = BufferedOutputStream(socket.getOutputStream())
        
        val cacheDir = java.io.File(context.cacheDir, "thumbnails")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val cacheFile = java.io.File(cacheDir, "thumb_$videoId.jpg")
        
        if (cacheFile.exists()) {
            try {
                val bytes = cacheFile.readBytes()
                val response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
                out.write(response.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.flush()
            } catch (e: Exception) {
                Log.e("LocalVideoServer", "Error sending cached thumbnail", e)
            }
            return
        }

        var tempBitmap: android.graphics.Bitmap? = null
        try {
            val futureTarget = com.bumptech.glide.Glide.with(context)
                .asBitmap()
                .load(uri)
                .submit()
            tempBitmap = futureTarget.get()
            // Try not to recycle immediately as Glide might manage it, 
            // but we can clear the target when we're done
            com.bumptech.glide.Glide.with(context).clear(futureTarget)
        } catch (e: Exception) {
            Log.e("LocalVideoServer", "Error generating thumb with Glide", e)
        }

        if (tempBitmap == null) {
            sendSimpleResponse(socket, "404 Not Found", "text/plain", "Thumbnail failed")
            return
        }

        try {
            val bos = java.io.ByteArrayOutputStream()
            tempBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos)
            val bytes = bos.toByteArray()
            
            try {
                cacheFile.writeBytes(bytes)
            } catch (e: Exception) {
                Log.e("LocalVideoServer", "Error caching thumbnail to disk", e)
            }

            val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            out.write(response.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
             Log.e("LocalVideoServer", "Error sending thumbnail", e)
        }
    }

    private fun sendRedirect(socket: Socket, url: String) {
        val out = BufferedOutputStream(socket.getOutputStream())
        val response = "HTTP/1.1 302 Found\r\n" +
                "Location: $url\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        out.write(response.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun streamLocalFile(socket: Socket, uri: Uri, rangeHeader: String?) {
        val out = BufferedOutputStream(socket.getOutputStream())
        var tempInputStream: java.io.InputStream? = null
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd == null) {
                sendSimpleResponse(socket, "500 Internal Server Error", "text/plain", "Could not acquire file descriptor")
                return
            }
            val totalLength = pfd.statSize
            pfd.close()

            tempInputStream = context.contentResolver.openInputStream(uri)
            if (tempInputStream == null) {
                sendSimpleResponse(socket, "500 Internal Server Error", "text/plain", "Could not acquire input stream")
                return
            }

            var startByte = 0L
            var endByte = totalLength - 1

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val rangeValue = rangeHeader.substringAfter("bytes=")
                val rangeParts = rangeValue.split("-")
                try {
                    if (rangeParts[0].isNotEmpty()) {
                        startByte = rangeParts[0].toLong()
                    }
                    if (rangeParts.size > 1 && rangeParts[1].isNotEmpty()) {
                        endByte = rangeParts[1].toLong()
                    }
                } catch (e: NumberFormatException) {
                    // fallbacks
                }
            }

            if (startByte < 0) startByte = 0L
            if (endByte >= totalLength) endByte = totalLength - 1
            if (startByte > endByte) startByte = endByte

            val rangeLength = endByte - startByte + 1

            // Native seek/skip
            var skipped = 0L
            while (skipped < startByte) {
                val skipStep = tempInputStream.skip(startByte - skipped)
                if (skipStep <= 0) break
                skipped += skipStep
            }

            val headers = if (rangeHeader != null) {
                "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: video/mp4\r\n" +
                        "Content-Length: $rangeLength\r\n" +
                        "Content-Range: bytes $startByte-$endByte/$totalLength\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: keep-alive\r\n" +
                        "\r\n"
            } else {
                "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: video/mp4\r\n" +
                        "Content-Length: $totalLength\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: keep-alive\r\n" +
                        "\r\n"
            }

            out.write(headers.toByteArray(Charsets.UTF_8))
            out.flush()

            val buffer = ByteArray(64 * 1024)
            var bytesToWriteNeeded = rangeLength
            while (bytesToWriteNeeded > 0) {
                val maxRead = Math.min(buffer.size.toLong(), bytesToWriteNeeded).toInt()
                val read = tempInputStream.read(buffer, 0, maxRead)
                if (read == -1) break
                out.write(buffer, 0, read)
                out.flush()
                bytesToWriteNeeded -= read
            }
        } catch (e: Exception) {
            Log.e("LocalVideoServer", "Error writing binary stream chunks to stream", e)
        } finally {
            tempInputStream?.close()
        }
    }

    private fun sendSimpleResponse(socket: Socket, status: String, contentType: String, content: String) {
        try {
            val out = BufferedOutputStream(socket.getOutputStream())
            val contentBytes = content.toByteArray(Charsets.UTF_8)
            val response = "HTTP/1.1 $status\r\n" +
                    "Content-Type: $contentType; charset=utf-8\r\n" +
                    "Content-Length: ${contentBytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            out.write(response.toByteArray(Charsets.UTF_8))
            out.write(contentBytes)
            out.flush()
        } catch (e: Exception) {
            Log.e("LocalVideoServer", "Error writing basic reply back", e)
        }
    }

    private fun parseQueryString(query: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        if (query.isEmpty()) return params
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0 && idx < pair.length - 1) {
                val key = pair.substring(0, idx)
                val value = java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                params[key] = value
            }
        }
        return params
    }

    private fun buildVideosJson(): String {
        val serverIp = getLocalIpAddress()
        val prefs = context.getSharedPreferences("video_progress", Context.MODE_PRIVATE)
        val builder = java.lang.StringBuilder()
        builder.append("[")
        val videos = sharedFiles.values.toList()
        for (i in videos.indices) {
            val video = videos[i]
            val streamUrl = "http://$serverIp:$port/stream/${video.id}"
            val thumbUrl = if (video.isLocal) "http://$serverIp:$port/thumbnail/${video.id}" else video.thumbnailUrl
            
            val watchedPosition = prefs.getLong("progress_${video.id}", 0L)
            val totalDuration = prefs.getLong("duration_${video.id}", 0L)
            
            builder.append("{")
            builder.append("\"id\":\"").append(video.id).append("\",")
            builder.append("\"title\":\"").append(escapeJson(video.title)).append("\",")
            builder.append("\"url\":\"").append(streamUrl).append("\",")
            builder.append("\"duration\":\"").append(video.duration).append("\",")
            builder.append("\"isLocal\":").append(video.isLocal).append(",")
            builder.append("\"folder\":\"").append(escapeJson(video.folder)).append("\",")
            builder.append("\"thumbnailUrl\":\"").append(thumbUrl).append("\",")
            builder.append("\"watchedPosition\":").append(watchedPosition).append(",")
            builder.append("\"totalDuration\":").append(totalDuration)
            builder.append("}")
            if (i < videos.size - 1) {
                builder.append(",")
            }
        }
        builder.append("]")
        return builder.toString()
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    }

    fun getLocalIpAddress(): String {
        try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) {
                        return inetAddress.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e("LocalVideoServer", "Error getting device WiFi IP address", ex)
        }
        return "127.0.0.1"
    }
}
