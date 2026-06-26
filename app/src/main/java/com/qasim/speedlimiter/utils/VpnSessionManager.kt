package com.qasim.speedlimiter.utils

import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.concurrent.thread

class VpnSessionManager {
    private var isSessionActive = false
    private var workerThread: Thread? = null
    
    // استخدام كلاس TokenBucket الخاص بك كمرجع رئيسي للتحكم
    @Volatile private var tokenBucket: TokenBucket? = null

    fun startSession(vpnFileDescriptor: FileDescriptor, speedLimitKbps: Int) {
        if (isSessionActive) return
        isSessionActive = true

        // تحويل الكيلوبت في الثانية إلى بايتات في الملي ثانية لتتوافق مع خوارزميتك
        // الحساب: (السرعة * 1000 بت) / 8 لتحويلها لبايت / 1000 لملي ثانية = (speedLimitKbps / 8)
        val refillRatePerMs = (speedLimitKbps / 8L).coerceAtLeast(1L)
        val maxCapacity = refillRatePerMs * 1000L // سعة السلة تكفي لثانية واحدة كحد أقصى

        tokenBucket = TokenBucket(maxCapacity, refillRatePerMs)

        workerThread = thread(start = true, name = "VpnTrafficShaper") {
            val inputStream = FileInputStream(vpnFileDescriptor)
            val outputStream = FileOutputStream(vpnFileDescriptor)
            val buffer = ByteArray(16384) // بافر متزن لمعالجة الحزم دون تجميد الهاتف

            try {
                Log.d("SpeedLimiterCore", "بدء تشغيل نفق التوجيه والتحديد الذكي.. السقف: $speedLimitKbps Kbps")
                
                while (isSessionActive) {
                    val readBytes = inputStream.read(buffer)
                    if (readBytes > 0) {
                        
                        // 📊 استدعاء خوارزميتك الخاصة: خنق الحزمة وجعل الخيط ينام لو تجاوز السقف
                        tokenBucket?.consume(readBytes.toLong())

                        // تمرير الحزمة بأمان بعد اقتطاع النقاط الخاصة بها
                        outputStream.write(buffer, 0, readBytes)
                    }
                }
            } catch (e: Exception) {
                Log.e("SpeedLimiterCore", "انتهت جلسة النفق أو حدث توقف مفاجئ: ${e.message}")
            } finally {
                try { inputStream.close() } catch (e: Exception) {}
                try { outputStream.close() } catch (e: Exception) {}
            }
        }
    }

    fun setRateLimit(speedLimitKbps: Int) {
        // إعادة تهيئة السلة ديناميكياً عند سحب السلايدر في الواجهة دون إعادة تشغيل الـ VPN
        val refillRatePerMs = (speedLimitKbps / 8L).coerceAtLeast(1L)
        val maxCapacity = refillRatePerMs * 1000L
        tokenBucket = TokenBucket(maxCapacity, refillRatePerMs)
        Log.d("SpeedLimiterCore", "تمت إعادة مزامنة الـ TokenBucket على السرعة الجديدة: $speedLimitKbps Kbps")
    }

    fun stopSession() {
        isSessionActive = false
        workerThread?.interrupt()
        workerThread = null
        tokenBucket = null
        Log.d("SpeedLimiterCore", "تم إغلاق الجلسة بنجاح وتصفير المحرك.")
    }

    fun isSessionRunning(): Boolean {
        return isSessionActive
    }
}
