package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class LocalVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var proxyServer: ServerSocket? = null
    private var isRunning = false
    private var speedLimitKbps: Int = 1024 

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            startVpnAndProxy()
        } else if (action == "STOP") {
            stopVpnAndProxy()
        }
        return START_STICKY
    }

    private fun startVpnAndProxy() {
        if (isRunning) return
        isRunning = true

        try {
            val builder = Builder()
            builder.setSession("SpeedLimiterPro")
                   .addAddress("10.0.0.2", 32)
                   .addRoute("0.0.0.0", 0) 
                   .addDnsServer("8.8.8.8")
                   .setMtu(1500)
            
            vpnInterface = builder.establish()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        thread(start = true, name = "ProxyServerThread") {
            try {
                proxyServer = ServerSocket(0) 
                while (isRunning) {
                    val clientSocket = proxyServer?.accept() ?: break
                    thread {
                        handleClientTraffic(clientSocket)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleClientTraffic(clientSocket: Socket) {
        try {
            protect(clientSocket)

            val clientInput = clientSocket.getInputStream()
            val clientOutput = clientSocket.getOutputStream()

            val buffer = ByteArray(32768)
            var bytesRead = 0 // ✅ تم حل المشكلة: إعطاء قيمة أولية للمتغير لمنع فشل البناء
            
            var totalBytesSent = 0
            var startTime = System.currentTimeMillis()

            val maxBytesPerSecond = (speedLimitKbps * 1024) / 8

            // صياغة آمنة ومتوافقة تماماً مع معايير كوتلن لقراءة حزم البيانات
            while (isRunning) {
                bytesRead = clientInput.read(buffer)
                if (bytesRead == -1) break
                
                if (bytesRead > 0) {
                    totalBytesSent += bytesRead
                    val currentTime = System.currentTimeMillis()
                    val elapsedTime = currentTime - startTime

                    if (elapsedTime < 1000) {
                        if (totalBytesSent >= maxBytesPerSecond) {
                            val sleepTime = 1000 - elapsedTime
                            if (sleepTime > 0) {
                                Thread.sleep(sleepTime) 
                            }
                            totalBytesSent = 0
                            startTime = System.currentTimeMillis()
                        }
                    } else {
                        totalBytesSent = 0
                        startTime = currentTime
                    }

                    clientOutput.write(buffer, 0, bytesRead)
                    clientOutput.flush()
                }
            }
        } catch (e: Exception) {
            // معالجة هادئة لإغلاق المقابس المفتوحة
        } finally {
            try { clientSocket.close() } catch (e: Exception) {}
        }
    }

    private fun stopVpnAndProxy() {
        isRunning = false
        try { proxyServer?.close() } catch (e: Exception) {}
        try { vpnInterface?.close() } catch (e: Exception) {}
        proxyServer = null
        vpnInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpnAndProxy()
    }
}
