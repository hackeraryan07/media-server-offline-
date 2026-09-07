package com.example.tv

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.TextUtils
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException

class TvSearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private var allVideos: List<TvVideo> = emptyList()
    private val client = OkHttpClient()
    private var serverIp: String = "127.0.0.1"
    private var lastQuery: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        setSearchResultProvider(this)

        serverIp = activity?.intent?.getStringExtra("server_ip") ?: "127.0.0.1"

        setOnItemViewClickedListener { _, item, _, row ->
            if (item is TvVideo) {
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
        
        // Request audio permission for speech recognition
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val requestFocusRunnable = Runnable {
            val searchEditText = findSearchEditText(view)
            searchEditText?.let { et ->
                if (!et.hasFocus()) {
                    et.requestFocus()
                }
            }
        }
        view.post(requestFocusRunnable)
        view.postDelayed(requestFocusRunnable, 150)
        view.postDelayed(requestFocusRunnable, 400)
        view.postDelayed(requestFocusRunnable, 800)
    }

    override fun onResume() {
        super.onResume()
        fetchVideos(refreshSearch = true)
    }

    private fun findSearchEditText(view: android.view.View): android.view.View? {
        if (view is androidx.leanback.widget.SearchEditText || view.javaClass.simpleName == "SearchEditText") {
            return view
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findSearchEditText(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun fetchVideos(refreshSearch: Boolean = false) {
        val deviceName = java.net.URLEncoder.encode(android.os.Build.MODEL ?: "AndroidTV", "UTF-8")
        val formattedIp = if (serverIp.contains(":") && !serverIp.startsWith("[")) "[$serverIp]" else serverIp
        val url = "http://$formattedIp:8999/videos?deviceName=$deviceName"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
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
                        
                        allVideos = videosList
                        if (refreshSearch && !lastQuery.isNullOrEmpty()) {
                            activity?.runOnUiThread {
                                loadQuery(lastQuery)
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        })
    }

    override fun getResultsAdapter(): ObjectAdapter {
        return rowsAdapter
    }

    override fun onQueryTextChange(newQuery: String?): Boolean {
        loadQuery(newQuery)
        return true
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        loadQuery(query)
        return true
    }

    private fun loadQuery(query: String?) {
        lastQuery = query
        rowsAdapter.clear()
        if (!TextUtils.isEmpty(query)) {
            val results = allVideos.filter { it.title.contains(query!!, ignoreCase = true) }
            if (results.isNotEmpty()) {
                val listRowAdapter = ArrayObjectAdapter(CardPresenter())
                for (video in results) {
                    listRowAdapter.add(video)
                }
                val header = HeaderItem("Search Results")
                rowsAdapter.add(ListRow(header, listRowAdapter))
            } else {
                val listRowAdapter = ArrayObjectAdapter(CardPresenter())
                listRowAdapter.add(TvVideo("err", "No results found for '$query'", "", "", false))
                val header = HeaderItem("Search Results")
                rowsAdapter.add(ListRow(header, listRowAdapter))
            }
        }
    }
}
