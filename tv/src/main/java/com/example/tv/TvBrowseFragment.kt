package com.example.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException

class TvBrowseFragment : BrowseSupportFragment() {

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private val client = OkHttpClient()
    private var serverIp: String = "127.0.0.1"

    companion object {
        fun newInstance(serverIp: String): TvBrowseFragment {
            val fragment = TvBrowseFragment()
            val args = Bundle().apply {
                putString("server_ip", serverIp)
            }
            fragment.arguments = args
            return fragment
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        serverIp = arguments?.getString("server_ip") ?: "127.0.0.1"

        setupUIElements()
        loadRows()
        setupEventListeners()
    }

    private fun setupUIElements() {
        title = "Wi-Fi Video Streamer Client"
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true

        // Use custom styling matching TV slate palette colors
        brandColor = ContextCompat.getColor(requireContext(), R.color.fastlane_background)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.fastlane_background)

        setOnSearchClickedListener {
            val intent = Intent(requireActivity(), SearchActivity::class.java).apply {
                putExtra("server_ip", serverIp)
            }
            startActivity(intent)
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
        fetchVideos()
    }

    private fun loadRows() {
        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        adapter = rowsAdapter
    }

    private fun fetchVideos() {
        val deviceName = java.net.URLEncoder.encode(android.os.Build.MODEL ?: "AndroidTV", "UTF-8")
        val formattedIp = if (serverIp.contains(":") && !serverIp.startsWith("[")) "[$serverIp]" else serverIp
        val url = "http://$formattedIp:8999/videos?deviceName=$deviceName"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread {
                    showErrorRow("Network failure: Could not reach streaming server ($serverIp:8999). Keep server active and retry.")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        val jsonArray = JSONArray(body)
                        val videosList = mutableListOf<TvVideo>()
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            videosList.add(
                                TvVideo(
                                    id = obj.getString("id"),
                                    title = obj.getString("title"),
                                    url = obj.getString("url"),
                                    duration = obj.getString("duration"),
                                    isLocal = obj.getBoolean("isLocal"),
                                    thumbnailUrl = obj.optString("thumbnailUrl", ""),
                                    folder = obj.optString("folder", "Uncategorized"),
                                    watchedPosition = obj.optLong("watchedPosition", 0L),
                                    totalDuration = obj.optLong("totalDuration", 0L)
                                )
                            )
                        }
                        
                        TvDataStore.playlist.clear()
                        TvDataStore.playlist.addAll(videosList)

                        activity?.runOnUiThread {
                            buildRows(videosList)
                            fetchPlaylists()
                        }
                    } catch (e: Exception) {
                        activity?.runOnUiThread {
                            showErrorRow("Payload error: Failed to parse share structure.")
                        }
                    }
                } else {
                    activity?.runOnUiThread {
                        showErrorRow("Server rejected query with code: ${response.code}")
                    }
                }
            }
        })
    }
    
    private fun fetchPlaylists() {
        val formattedIp = if (serverIp.contains(":") && !serverIp.startsWith("[")) "[$serverIp]" else serverIp
        val url = "http://$formattedIp:8999/playlists"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        val jsonArray = JSONArray(body)
                        val playlistRows = mutableListOf<ListRow>()
                        val cardPresenter = CardPresenter()
                        var headerId = 100L

                        for (i in 0 until jsonArray.length()) {
                            val pObj = jsonArray.getJSONObject(i)
                            val pName = pObj.getString("name")
                            val itemsArray = pObj.getJSONArray("items")
                            val videosInPlaylist = mutableListOf<TvVideo>()

                            for (j in 0 until itemsArray.length()) {
                                val itemObj = itemsArray.getJSONObject(j)
                                val vId = itemObj.getString("videoId")
                                TvDataStore.playlist.find { it.id == vId }?.let {
                                    videosInPlaylist.add(it)
                                }
                            }

                            if (videosInPlaylist.isNotEmpty()) {
                                val header = HeaderItem(headerId++, "Playlist: $pName")
                                val rowAdapter = ArrayObjectAdapter(cardPresenter)
                                rowAdapter.addAll(0, videosInPlaylist)
                                playlistRows.add(ListRow(header, rowAdapter))
                            }
                        }

                        activity?.runOnUiThread {
                            appendPlaylistRows(playlistRows)
                        }
                    } catch (e: Exception) {}
                }
            }
        })
    }

    private fun appendPlaylistRows(playlistRows: List<ListRow>) {
        if (rowsAdapter.size() == 0) return
        
        // Find existing playlist rows
        val existingPlaylists = mutableListOf<Pair<Int, ListRow>>()
        for (i in 0 until rowsAdapter.size()) {
            val row = rowsAdapter.get(i) as? ListRow
            if (row != null && row.headerItem?.name?.startsWith("Playlist:") == true) {
                existingPlaylists.add(Pair(i, row))
            }
        }
        
        var rebuildPlaylists = existingPlaylists.size != playlistRows.size
        if (!rebuildPlaylists) {
            for (i in existingPlaylists.indices) {
                if (existingPlaylists[i].second.headerItem.name != playlistRows[i].headerItem.name) {
                    rebuildPlaylists = true
                    break
                }
            }
        }
        
        if (rebuildPlaylists) {
            // Remove existing playlist rows and settings row
            for (i in rowsAdapter.size() - 1 downTo 0) {
                val row = rowsAdapter.get(i) as? ListRow
                if (row != null && (row.headerItem?.name?.startsWith("Playlist:") == true || row.headerItem?.name == "Settings")) {
                    rowsAdapter.removeItems(i, 1)
                }
            }
            // Add new ones
            for (row in playlistRows) {
                rowsAdapter.add(row)
            }
            
            // Add settings row
            val settingsHeader = HeaderItem(9999, "Settings")
            val cardPresenter = CardPresenter()
            val rowAdapter = ArrayObjectAdapter(cardPresenter)
            rowAdapter.add(TvVideo("settings_btn", "App Settings", "", "", false))
            rowsAdapter.add(ListRow(settingsHeader, rowAdapter))
        } else {
            // Update items in place to avoid focus lose
            for (i in existingPlaylists.indices) {
                val existingRow = existingPlaylists[i].second
                val newRow = playlistRows[i]
                
                val existingRowAdapter = existingRow.adapter as ArrayObjectAdapter
                val newItems = mutableListOf<TvVideo>()
                for (j in 0 until newRow.adapter.size()) {
                    newItems.add(newRow.adapter.get(j) as TvVideo)
                }
                existingRowAdapter.setItems(newItems, videoDiffCallback)
            }
            
            // Ensure settings row exists
            var hasSettings = false
            for (i in 0 until rowsAdapter.size()) {
                val row = rowsAdapter.get(i) as? ListRow
                if (row?.headerItem?.name == "Settings") {
                    hasSettings = true
                    break
                }
            }
            if (!hasSettings) {
                val settingsHeader = HeaderItem(9999, "Settings")
                val cardPresenter = CardPresenter()
                val rowAdapter = ArrayObjectAdapter(cardPresenter)
                rowAdapter.add(TvVideo("settings_btn", "App Settings", "", "", false))
                rowsAdapter.add(ListRow(settingsHeader, rowAdapter))
            }
        }
    }

    private fun showErrorRow(msg: String) {
        rowsAdapter.clear()
        val header = HeaderItem(0, "System Connection Alerts")
        val gridPresenter = CardPresenter()
        val listRowAdapter = ArrayObjectAdapter(gridPresenter)
        
        listRowAdapter.add(TvVideo("err", msg, "", "", false))
        rowsAdapter.add(ListRow(header, listRowAdapter))
    }

    private val videoDiffCallback = object : androidx.leanback.widget.DiffCallback<TvVideo>() {
        override fun areItemsTheSame(oldItem: TvVideo, newItem: TvVideo): Boolean {
            return oldItem.id == newItem.id
        }
        override fun areContentsTheSame(oldItem: TvVideo, newItem: TvVideo): Boolean {
            return oldItem == newItem
        }
    }

    private fun buildRows(videos: List<TvVideo>) {
        val localVideos = videos.filter { it.isLocal }
        val cardPresenter = CardPresenter()

        val groupedByFolder = localVideos.groupBy { it.folder ?: "Videos" }
        val expectedHeaders = mutableListOf<String>()
        if (localVideos.isNotEmpty()) {
            expectedHeaders.add("All Videos")
            expectedHeaders.addAll(groupedByFolder.keys)
        }

        val currentLocalRows = mutableListOf<ListRow>()
        for (i in 0 until rowsAdapter.size()) {
            val row = rowsAdapter.get(i) as? ListRow
            if (row != null && row.headerItem?.name?.startsWith("Playlist:") != true && row.headerItem?.name != "Settings") {
                currentLocalRows.add(row)
            }
        }

        var rebuild = currentLocalRows.size != expectedHeaders.size
        if (!rebuild) {
             for (i in 0 until currentLocalRows.size) {
                 if (currentLocalRows[i].headerItem.name != expectedHeaders[i]) {
                     rebuild = true
                     break
                 }
             }
        }

        if (rebuild) {
            rowsAdapter.clear()
            if (localVideos.isNotEmpty()) {
                val allHeader = HeaderItem(1, "All Videos")
                val allRowAdapter = ArrayObjectAdapter(cardPresenter)
                allRowAdapter.addAll(0, localVideos)
                rowsAdapter.add(ListRow(allHeader, allRowAdapter))

                var headerId = 2L
                for ((folderName, folderVideos) in groupedByFolder) {
                    val header = HeaderItem(headerId++, folderName)
                    val rowAdapter = ArrayObjectAdapter(cardPresenter)
                    rowAdapter.addAll(0, folderVideos)
                    rowsAdapter.add(ListRow(header, rowAdapter))
                }
            }
        } else {
            if (localVideos.isNotEmpty()) {
                var rowIndex = 0
                val allRow = rowsAdapter.get(rowIndex++) as ListRow
                val allRowAdapter = allRow.adapter as ArrayObjectAdapter
                allRowAdapter.setItems(localVideos, videoDiffCallback)

                for ((folderName, folderVideos) in groupedByFolder) {
                    val folderRow = rowsAdapter.get(rowIndex++) as ListRow
                    val folderRowAdapter = folderRow.adapter as ArrayObjectAdapter
                    folderRowAdapter.setItems(folderVideos, videoDiffCallback)
                }
            }
        }
    }

    private fun setupEventListeners() {
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row ->
            if (item is TvVideo) {
                if (item.id == "settings_btn") {
                    startActivity(Intent(requireContext(), TvSettingsActivity::class.java))
                    return@OnItemViewClickedListener
                }
                
                if (item.id != "err" && item.url.isNotEmpty()) {
                    val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
                        putExtra("video", item)
                        
                        if (row is androidx.leanback.widget.ListRow) {
                            val adapter = row.adapter
                            if (adapter is androidx.leanback.widget.ArrayObjectAdapter) {
                                val playlist = ArrayList<TvVideo>()
                                var currentIndex = 0
                                for (i in 0 until adapter.size()) {
                                    val rowItem = adapter.get(i) as? TvVideo
                                    if (rowItem != null) {
                                        playlist.add(rowItem)
                                        if (rowItem.id == item.id) {
                                            currentIndex = i
                                        }
                                    }
                                }
                                putExtra("playlist", playlist)
                                putExtra("currentIndex", currentIndex)
                            }
                        }
                    }
                    startActivity(intent)
                }
            }
        }
    }
}
