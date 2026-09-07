package com.example.tv

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {

    private lateinit var discoverer: NsdClientDiscoverer
    private lateinit var connectionContainer: View
    private lateinit var browseFragmentContainer: View
    private lateinit var statusText: TextView
    private lateinit var loader: ProgressBar
    private lateinit var ipInput: EditText
    private lateinit var connectBtn: Button
    private lateinit var retryBtn: Button
    private var multicastLock: WifiManager.MulticastLock? = null
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectionContainer = findViewById(R.id.connection_container)
        browseFragmentContainer = findViewById(R.id.browse_fragment_container)
        statusText = findViewById(R.id.status_message)
        loader = findViewById(R.id.discovery_progress)
        ipInput = findViewById(R.id.ip_input)
        connectBtn = findViewById(R.id.btn_connect)
        retryBtn = findViewById(R.id.btn_retry)

        // Acquire Wi-Fi Multicast Lock on Android TV client so discovery packets arrive safely
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("tv_client_discovery_lock").apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d("MainActivity", "Acquired TV Client Wi-Fi Multicast Lock")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error acquiring multicast lock for TV client", e)
        }

        // Start scanning
        startScanning()

        // Setup manual buttons
        connectBtn.setOnClickListener {
            val ip = ipInput.text.toString().trim()
            if (ip.isNotEmpty()) {
                connectToServer(ip)
            }
        }

        retryBtn.setOnClickListener {
            retryBtn.visibility = View.GONE
            loader.visibility = View.VISIBLE
            statusText.text = getString(R.string.searching_servers)
            startScanning()
        }

        // Keyboard action
        ipInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val ip = ipInput.text.toString().trim()
                if (ip.isNotEmpty()) {
                    connectToServer(ip)
                }
                // Hide virtual keyboard
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(ipInput.windowToken, 0)
                true
            } else {
                false
            }
        }
    }

    private fun startScanning() {
        discoverer = NsdClientDiscoverer(applicationContext) { hostIp, port ->
            runOnUiThread {
                if (!isConnected) {
                    statusText.text = "Connected to Server at http://$hostIp:$port"
                    connectToServer(hostIp)
                }
            }
        }
        discoverer.startDiscovery()
    }

    private fun connectToServer(ip: String) {
        if (isConnected) return
        isConnected = true
        
        try {
            discoverer.stopDiscovery()
        } catch (e: Exception) {
            // ignore
        }

        runOnUiThread {
            TvRemoteServer.start(applicationContext)
            loader.visibility = View.GONE
            connectionContainer.visibility = View.GONE
            browseFragmentContainer.visibility = View.VISIBLE

            // Insert Leanback Fragment
            val manager = supportFragmentManager
            val transaction = manager.beginTransaction()
            val fragment = TvBrowseFragment.newInstance(ip)
            transaction.replace(R.id.browse_fragment_container, fragment)
            transaction.commit()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            TvRemoteServer.stop()
            discoverer.stopDiscovery()
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d("MainActivity", "Released TV Client Wi-Fi Multicast Lock")
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error closing resources in TV onDestroy", e)
        }
    }
}
