package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.qasim.speedlimiter.utils.TokenBucket
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class LocalVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var isRunning = false
    private var speedLimitKbps: Int = 1024
    
    // تعريف الـ TokenBucket كمتغير مرجعي لعصب التقييد
    private var tokenBucket: TokenBucket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            
            // حساب السعة ومعدل الامتلاء لكل ملي ثانية بناءً على اختيار المستخدم
            // مثلاً: 1024 كيلوبت في الثانية = 128,000 بايت في الثانية
            val bytesPerSecond = (speedLimitKbps * 1024L) / 8
            val refillRatePerMs = bytesPerSecond / 1000
            
            // تهيئة السلة بالقيم الديناميكية الجديدة
            tokenBucket = TokenBucket(bytesPerSecond, maxOf(1, refillRatePerMs))
            
            if (!isRunning) {
                isRunning = true
                vpnThread = Thread(this, "SpeedVpnThread")
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
            builder.setSession("SpeedLimiterPass")
                   .addAddress("10.0.0.2", 32) // عنوان IP داخلي فريد للنفق
                   .addRoute("0.0.0.0", 0)     // توجيه كل حزم الهاتف للنفق
                   .addDnsServer("8.8.8.8")

            val targetApps = listOf("com.android.chrome", "com.google.android.youtube")
            for (app in targetApps) {
                try { builder.addAllowedApplication(app) } catch (e: Exception) {}
            }

            vpnInterface = builder.establish() ?: return

            val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteArray(16384)

            while (isRunning) {
                val readBytes = inputStream.read(buffer)
                if (readBytes > 0) {
                    
                    // ==========================================
                    // عصب التقييد: استدعاء السلة لحجز وتخنيق السرعة هنا
                    // ==========================================
                    tokenBucket?.consume(readBytes.toLong())

                    // ملاحظة هامة جداً للمستقبل: 
                    // هنا يجب أن يتم تمرير الـ buffer إلى لـ Local Socket محمي بدالة protect() 
                    // يرسلها للإنترنت الفعلي، ومن ثم يستقبل الرد ويكتبه في الـ outputStream.
                    
                    // الكود الحالي يقوم بعمل محاكاة رجعية (Loopback) للاختبار:
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
