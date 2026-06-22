package com.qasim.speedlimiter.data.services

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.Exception
import java.net.DatagramSocket
import java.net.Socket
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
            Log.d("LocalVpnService", "تم تحديث سقف السرعة ديناميكياً إلى: $speedLimitKbps KB/s")
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        isRunning = true

        val builder = Builder()
            .setSession("SpeedLimiterCore")
            // إعداد العناوين الافتراضية القياسية للـ Local Loopback
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0) 
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            
        // السطر الحاسم: استثناء التطبيق نفسه لمنع الانهيار الدائري للشبكة
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.e("LocalVpnService", "فشل استثناء الحزمة المحلية", e)
        }

        try {
            vpnInterface = builder.establish()
        } catch (e: Exception) {
            Log.e("LocalVpnService", "فشل إنشاء واجهة النفق الافتراضية", e)
            isRunning = false
            return
        }

        vpnThread = thread(start = true) {
            val vpnFd = vpnInterface?.fileDescriptor ?: return@thread
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
                        
                        // حساب رصيد البايتات المسموح بتمريرها بناءً على السرعة المحددة بالسلايدر
                        if (elapsedTimeMs > 0) {
                            allowedBytesChunk += (speedLimitKbps.toLong() * 1024L * elapsedTimeMs) / 1000L
                            lastCheckTime = currentTime
                        }

                        // إذا تجاوزت البيانات السرعة المحددة، نقوم بعمل فرملة (تأخير زمني بالملي ثانية)
                        if (allowedBytesChunk < length) {
                            val sleepTime = ((length - allowedBytesChunk) * 1000L) / (speedLimitKbps.toLong() * 1024L)
                            if (sleepTime > 0) {
                                Thread.sleep(sleepTime.coerceAtMost(30L)) // فرملة ذكية قصيرة لحجز الحزم مؤقتاً
                            }
                            lastCheckTime = System.nanoTime()
                            allowedBytesChunk = 0
                        } else {
                            allowedBytesChunk -= length
                        }

                        // تمرير الحزمة مباشرة إلى المخرج لتعود إلى نظام أندرويد بعد فرملتها زمنيّاً
                        output.write(buffer, 0, length)
                    }
                } catch (e: Exception) {
                    Log.e("LocalVpnService", "حدث استثناء أثناء تشغيل النفق", e)
                    break
                }
            }
        }
    }

    // حل مشكلة بقاء الـ VPN متصلاً في الخلفية بعد الضغط على إيقاف
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
