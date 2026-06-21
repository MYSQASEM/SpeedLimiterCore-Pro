package com.qasim.speedlimiter.data.services

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.Exception
import kotlin.concurrent.thread

class LocalVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var speedLimitKbps = 1024
    private var vpnThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val limit = intent?.getIntExtra("SPEED_LIMIT", 1024) ?: 1024
        
        if (action == "START_VPN") {
            speedLimitKbps = limit
            startVpn()
        } else if (action == "UPDATE_SPEED") {
            speedLimitKbps = limit
            Log.d("LocalVpnService", "تم تحديث سقف السرعة إلى: $speedLimitKbps KB/s")
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        isRunning = true

        val builder = Builder()
            .setSession("SpeedLimiterCore")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0) 
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.e("LocalVpnService", "فشل استثناء الحزم المحلية", e)
        }

        try {
            vpnInterface = builder.establish()
        } catch (e: Exception) {
            Log.e("LocalVpnService", "فشل إنشاء واجهة النفق الافتراضية", e)
            isRunning = false
            return
        }

        vpnThread = thread(start = true) {
            val vpnFd = vpnInterface?.fileDescriptor
            if (vpnFd == null) {
                isRunning = false
                return@thread
            }

            val input = FileInputStream(vpnFd)
            val output = FileOutputStream(vpnFd)
            val buffer = ByteArray(Short.MAX_VALUE.toInt())

            var lastCheckTime = System.nanoTime()
            var allowedBytesChunk = 0L

            while (isRunning) {
                try {
                    val length = input.read(buffer)
                    if (length > 0) {
                        val currentTime = System.nanoTime()
                        val elapsedTimeMs = (currentTime - lastCheckTime) / 1_000_000
                        
                        if (elapsedTimeMs > 0) {
                            allowedBytesChunk += (speedLimitKbps.toLong() * 1024L * elapsedTimeMs) / 1000L
                            lastCheckTime = currentTime
                        }

                        if (allowedBytesChunk < length) {
                            val sleepTime = ((length - allowedBytesChunk) * 1000L) / (speedLimitKbps.toLong() * 1024L)
                            if (sleepTime > 0) {
                                Thread.sleep(sleepTime.coerceAtMost(50L)) 
                            }
                            lastCheckTime = System.nanoTime()
                            allowedBytesChunk = 0
                        } else {
                            allowedBytesChunk -= length
                        }

                        // استدعاء ملف h الأصلي المتواجد في حزمة com.qasim.speedlimiter مباشرة
                        try {
                            val hClass = Class.forName("com.qasim.speedlimiter.h")
                            val method = hClass.getMethod("a", ByteArray::class.java, Int::class.javaPrimitiveType)
                            method.invoke(null, buffer, length)
                        } catch (e: Exception) {
                            // تمرير حماية في حال عدم اكتمال الحزمة بالمشروع
                        }
                        
                        output.write(buffer, 0, length)
                    }
                } catch (e: Exception) {
                    Log.e("LocalVpnService", "انقطاع حزمة البيانات داخل النفق", e)
                    break
                }
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        try {
            vpnThread?.interrupt()
        } catch (e: Exception) {}
        vpnThread = null
        
        try {
            vpnInterface?.close()
        } catch (e: Exception) {}
        vpnInterface = null
        stopSelf() 
        super.onDestroy()
    }
}
