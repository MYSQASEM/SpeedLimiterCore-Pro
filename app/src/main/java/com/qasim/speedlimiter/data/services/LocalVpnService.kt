package com.qasim.speedlimiter

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
            Log.d("LocalVpnService", "تم تحديث سقف السرعة ديناميكياً إلى: $speedLimitKbps KB/s")
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        isRunning = true

        // إعداد النفق مع السماح بمرور التطبيقات وتحديد الـ DNS الافتراضي لمنع انقطاع التصفح
        val builder = Builder()
            .setSession("SpeedLimiterCore")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0) // توجيه كامل حركة المرور
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            
        // استثناء التطبيق نفسه من النفق لمنع الحلقة اللانهائية (Infinite Loop) التي تسبب خنق الإنترنت
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

            // متغيرات التحكم بالسرعة (Token Bucket)
            var lastCheckTime = System.nanoTime()
            var allowedBytesChunk = 0L

            while (isRunning) {
                try {
                    val length = input.read(buffer)
                    if (length > 0) {
                        val currentTime = System.nanoTime()
                        val elapsedTimeMs = (currentTime - lastCheckTime) / 1_000_000
                        
                        // تحديث الرصيد المسموح به من البيانات بناءً على الوقت المنقضي والسرعة المحددة
                        if (elapsedTimeMs > 0) {
                            // كمية البايتات المسموح بها في هذه اللحظة القصيرة
                            allowedBytesChunk += (speedLimitKbps * 1024 * elapsedTimeMs) / 1000
                            lastCheckTime = currentTime
                        }

                        // إذا تجاوزت الحزمة السقف المسموح به حالياً، نقوم بتهدئة البث (تطبيق الفرملة)
                        if (allowedBytesChunk < length) {
                            val sleepTime = ((length - allowedBytesChunk) * 1000) / (speedLimitKbps * 1024)
                            if (sleepTime > 0) {
                                Thread.sleep(sleepTime.coerceAtMost(50)) // نوم خفيف للحفاظ على التصفح دون انقطاع
                            }
                            // إعادة حساب الوقت بعد التهدئة
                            lastCheckTime = System.nanoTime()
                            allowedBytesChunk = 0
                        } else {
                            allowedBytesChunk -= length
                        }

                        // استدعاء ملف h.java من كودك الأصلي لحساب الـ Checksum للحزم المارة
                        h.a(buffer, length)
                        
                        // تمرير الحزمة إلى الشبكة
                        output.write(buffer, 0, length)
                    }
                } catch (e: Exception) {
                    Log.e("LocalVpnService", "خطأ أثناء تمرير البيانات عبر النفق", e)
                    break
                }
            }
        }
    }

    // إصلاح مشكلة بقاء الـ VPN متصلاً بعد إيقافه
    override fun onDestroy() {
        isRunning = false
        vpnThread?.interrupt()
        vpnThread = null
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e("LocalVpnService", "خطأ أثناء إغلاق واجهة الـ VPN", e)
        }
        vpnInterface = null
        stopSelf() // إنهاء الخدمة تماماً من الخلفية
        super.onDestroy()
    }
}
