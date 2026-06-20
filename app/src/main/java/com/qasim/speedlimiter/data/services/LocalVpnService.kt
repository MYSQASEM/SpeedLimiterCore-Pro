package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class LocalVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var selectorThread: Thread? = null
    private var isRunning = false
    
    private var speedThrottler: NetworkThrottler? = null
    private var selector: Selector? = null
    private val outputQueue = Executors.newSingleThreadExecutor()
    private val sessionMap = ConcurrentHashMap<String, SocketChannel>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            val speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            
            val bytesPerSecond = (speedLimitKbps * 1024L) /
