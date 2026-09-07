package com.example.tv

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

object TvRemoteServer {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var context: Context? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    interface PlayerController {
        fun play()
        fun pause()
        fun next()
        fun prev()
        fun playVideo(id: String)
        fun seekTo(positionMs: Long)
        fun getState(): JSONObject
        fun handleResumeChoice(choice: String)
        fun handleSpeedChoice(speed: Float?)
        fun handleAudioShiftChoice(shiftMs: Long?)
        fun triggerAction(action: String)
        fun requestAudioShiftDialog()
    }

    var playerController: PlayerController? = null

    fun start(ctx: Context) {
        if (isRunning) return
        this.context = ctx.applicationContext
        isRunning = true
        thread {
            try {
                serverSocket = ServerSocket(9000)
                Log.d("TvRemoteServer", "Started on 9000")
                
                // Register NSD
                nsdManager = context?.getSystemService(Context.NSD_SERVICE) as? NsdManager
                val serviceInfo = NsdServiceInfo().apply {
                    serviceName = "TvClient"
                    serviceType = "_tvremote._tcp"
                    port = 9000
                }
                registrationListener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(info: NsdServiceInfo) {
                        Log.d("TvRemoteServer", "NSD Registered: ${info.serviceName}")
                    }
                    override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceUnregistered(info: NsdServiceInfo) {}
                    override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
                }
                nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)

                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    handleClient(socket)
                }
            } catch (e: Exception) {
                // ignore
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {}
        serverSocket?.close()
    }


    private fun handleClient(socket: Socket) {
        thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val out = socket.getOutputStream()
                val line = reader.readLine() ?: return@thread
                val parts = line.split(" ")
                if (parts.size >= 2) {
                    val path = parts[1]
                    if (path.startsWith("/state")) {
                        val state = playerController?.getState() ?: JSONObject()
                        sendResponse(out, state.toString())
                    } else if (path.startsWith("/command")) {
                        val query = path.substringAfter("?", "")
                        val params = parseQueryString(query)
                        val action = params["action"]
                        val id = params["id"]

                        when (action) {
                            "play" -> playerController?.play()
                            "pause" -> playerController?.pause()
                            "next" -> playerController?.next()
                            "prev" -> playerController?.prev()
                            "play_video" -> {
                                if (playerController != null) {
                                    id?.let { playerController?.playVideo(it) }
                                } else {
                                    id?.let {
                                        val video = TvDataStore.playlist.find { v -> v.id == it }
                                        if (video != null && context != null) {
                                            val intent = Intent(context, PlayerActivity::class.java).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                putExtra("video", video)
                                                putExtra("playlist", ArrayList(TvDataStore.playlist))
                                                putExtra("currentIndex", TvDataStore.playlist.indexOf(video))
                                            }
                                            context?.startActivity(intent)
                                        }
                                    }
                                }
                            }
                            "seek" -> {
                                val posStr = params["position"]
                                if (posStr != null) {
                                    playerController?.seekTo(posStr.toLongOrNull() ?: 0L)
                                }
                            }
                            "resume_choice" -> {
                                params["choice"]?.let { choice ->
                                    playerController?.handleResumeChoice(choice)
                                }
                            }
                            "set_speed" -> {
                                params["value"]?.toFloatOrNull()?.let { speed ->
                                    playerController?.handleSpeedChoice(speed)
                                }
                            }
                            "cancel_speed" -> {
                                playerController?.handleSpeedChoice(null)
                            }
                            "set_audio_shift" -> {
                                params["value"]?.toLongOrNull()?.let { shift ->
                                    playerController?.handleAudioShiftChoice(shift)
                                }
                            }
                            "cancel_audio_shift" -> {
                                playerController?.handleAudioShiftChoice(null)
                            }
                            "request_audio_shift_dialog" -> {
                                playerController?.requestAudioShiftDialog()
                            }
                            else -> {
                                if (action != null) {
                                    playerController?.triggerAction(action)
                                }
                            }
                        }
                        sendResponse(out, "{\"status\":\"ok\"}")
                    } else {
                        sendResponse(out, "404 Not Found")
                    }
                }
            } catch (e: Exception) {
                Log.e("TvRemoteServer", "Error handling client", e)
            } finally {
                socket.close()
            }
        }
    }

    private fun sendResponse(out: OutputStream, content: String) {
        val bytes = content.toByteArray()
        val res = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        out.write(res.toByteArray())
        out.write(bytes)
        out.flush()
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
}
