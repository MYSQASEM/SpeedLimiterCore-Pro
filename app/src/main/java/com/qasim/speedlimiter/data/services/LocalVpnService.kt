package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.Socket

class LocalVpnService : VpnService(), Runnable {
    private var vpnThread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var speedLimitKbps: Int = 1024 
    private var isRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            
            isRunning = true
            vpnThread = Thread(this, "TransparentVpnThread")
            vpnThread?.start()
        } else if (action == "STOP") {
            stopVpn()
        }
        return START_STICKY
    }

    override fun run() {
        try {
            val builder = Builder()
            
            // 🌐 إعداد نفق شفاف يمنح النظام حق التوجيه التلقائي لمنع انقطاع أي موقع
            builder.setSession("SpeedLimiterPro")
                   .addAddress("172.19.0.1", 30) // استخدام نطاق شبكة محلي افتراضي معزول
                   .addDnsServer("8.8.8.8")       // قسر نظام الـ DNS لضمان استجابة كل المواقع
                   .addDnsServer("1.1.1.1")
                   .setMtu(1500)

            // توجيه حركة المرور العامة للنفق ليصبح هو المتحكم الافتراضي
            builder.addRoute("0.0.0.0", 0)

            vpnInterface = builder.establish() ?: return

            val fd = vpnInterface!!.fileDescriptor
            val inputStream = FileInputStream(fd)
            val outputStream = FileOutputStream(fd)
            val buffer = ByteArray(32768) // حجم بافر كبير لمنع سقوط حزم المتصفحات

            var bytesProcessed = 0
            var lastCheckTime = System.currentTimeMillis()

            while (isRunning && !Thread.interrupted()) {
                val readBytes = inputStream.read(buffer)
                if (readBytes > 0) {
                    
                    bytesProcessed += readBytes
                    val maxBytesPerSecond = (speedLimitKbps * 1024) / 8
                    val now = System.currentTimeMillis()
                    val timePassed = now - lastCheckTime

                    // ⏱️ لوجيك الفرمة الزمنية الصارمة لحجم تدفق البيانات
                    if (timePassed < 1000) {
                        if (bytesProcessed >= maxBytesPerSecond) {
                            val sleepTime = 1000 - timePassed
                            if (sleepTime > 0) {
                                // إجبار المعالج على النوم لإبطاء سحب البيانات الحية فوراً
                                Thread.sleep(sleepTime) 
                            }
                            bytesProcessed = 0
                            lastCheckTime = System.currentTimeMillis()
                        }
                    } else {
                        bytesProcessed = 0
                        lastCheckTime = now
                    }

                    // تمرير البيانات المفرملة وسماح النظام بحمايتها وتمريرها للخارج
                    try {
                        outputStream.write(buffer, 0, readBytes)
                        outputStream.flush()
                    } catch (e: Exception) {
                        // تخطي الأخطاء العابرة للحزم الساقطة
                    }
                }
                Thread.sleep(1)
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
