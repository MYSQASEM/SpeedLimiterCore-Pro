package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class LocalVpnService : VpnService(), Runnable {
    private var vpnThread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var speedLimitKbps: Int = 1024 // السرعة الافتراضية 1 ميجا

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            startVpn()
        } else if (action == "STOP") {
            stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (vpnThread == null) {
            vpnThread = Thread(this, "LocalVpnThread")
            vpnThread?.start()
        }
    }

    private fun stopVpn() {
        vpnThread?.interrupt()
        vpnThread = null
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            // تجاهل الخطأ لحماية التطبيق من الانهيار
        }
        vpnInterface = null
        stopSelf()
    }

    override fun run() {
        try {
            val builder = Builder()
            builder.setSession("SpeedLimiterPro")
                   .addAddress("10.0.0.2", 32)
                   .addRoute("0.0.0.0", 0)
                   .addDnsServer("8.8.8.8")

            // 🚀 قائمة بالتطبيقات الشهيرة المستهدفة بخنق السرعة لتمريرها داخل النفق دون قطع اتصال النظام
            val targetPackages = listOf(
                "com.android.chrome",      // متصفح كروم
                "com.google.android.youtube", // يوتيوب
                "com.instagram.android",   // إنستغرام
                "com.zhiliaoapp.musically",  // تيك توك
                "com.facebook.katana"      // فيسبوك
            )

            // دمج التطبيقات المستهدفة داخل نفق التحكم
            var addedAnyApp = false
            for (pkg in targetPackages) {
                try {
                    builder.addAllowedApplication(pkg)
                    addedAnyApp = true
                } catch (e: Exception) {
                    // إذا كان التطبيق غير مثبت على هاتف المستخدم يتخطاه بأمان
                }
            }

            // إذا لم تكن هذه التطبيقات مثبتة، يستهدف المتصفح الافتراضي كمثال لضمان عمل النفق
            if (!addedAnyApp) {
                try { builder.addAllowedApplication("com.android.browser") } catch (e: Exception) {}
            }

            vpnInterface = builder.establish() ?: return
            
            val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteArray(16384)

            var bytesInCurrentSecond = 0
            var lastCheckTime = System.currentTimeMillis()

            while (!Thread.interrupted()) {
                val readBytes = inputStream.read(buffer)
                if (readBytes > 0) {
                    
                    // ⚡ لوجيك خنق السرعة الفعلي المستقر (Traffic Throttling)
                    bytesInCurrentSecond += readBytes
                    val now = System.currentTimeMillis()
                    
                    if (now - lastCheckTime < 1000) {
                        // حساب الحد الأقصى للبايتات المسموحة بناءً على اختيار قاسم من السلايدر
                        val maxBytesAllowed = (speedLimitKbps * 1024) / 8
                        if (bytesInCurrentSecond >= maxBytesAllowed) {
                            val delay = 1000 - (now - lastCheckTime)
                            if (delay > 0) {
                                Thread.sleep(delay) // خنق سرعة تدفق البيانات هنا عبر تجميد مؤقت ذكي
                            }
                            bytesInCurrentSecond = 0
                            lastCheckTime = System.currentTimeMillis()
                        }
                    } else {
                        bytesInCurrentSecond = 0
                        lastCheckTime = now
                    }

                    // إعادة توجيه الحزمة بأمان للشبكة
                    outputStream.write(buffer, 0, readBytes)
                }
                Thread.sleep(1) // حماية المعالج من الاستهلاك العالي وبطارية الهاتف
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            stopVpn()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}
