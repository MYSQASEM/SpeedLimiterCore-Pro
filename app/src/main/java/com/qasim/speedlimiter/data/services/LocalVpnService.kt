package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class LocalVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var isRunning = false
    private var speedThrottler: NetworkThrottler? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            val speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            
            // تحويل الكيلوبت في الثانية إلى بايتات في الثانية
            val bytesPerSecond = (speedLimitKbps * 1024L) / 8L
            
            if (speedThrottler == null) {
                speedThrottler = NetworkThrottler(bytesPerSecond, bytesPerSecond)
            } else {
                speedThrottler?.updateSpeed(bytesPerSecond, bytesPerSecond)
            }
            
            if (!isRunning) {
                isRunning = true
                vpnThread = Thread(this, "ThrottledVpnThread")
                vpnThread?.start()
            }
        } else if (action == "STOP") {
            stopVpn()
        }
        return START_STICKY
    }

    override fun run() {
        try {
            val builder = Builder()
            builder.setSession("SpeedLimiterEngine")
                   .addAddress("10.0.0.2", 24)
                   .addRoute("0.0.0.0", 0)
                   .addDnsServer("8.8.8.8")
                   .setMtu(1500)

            vpnInterface = builder.establish() ?: return

            val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteArray(16384)

            while (isRunning && !Thread.interrupted()) {
                val readBytes = inputStream.read(buffer)
                if (readBytes > 0) {
                    // 🚀 استخدام سر الفرملة الذكية المأخوذة من تطبيقك الناجح
                    speedThrottler?.limit(readBytes.toLong())
                    
                    // كتابة الحزمة بعد أن أخذت وقتها الصحيح في الانتظار المبرمج
                    outputStream.write(buffer, 0, readBytes)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            stopVpn()
        }
    }

    private fun stopVpn() {
        isRunning = false
        vpnThread?.interrupt()
        vpnThread = null
        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}
