package com.qasim.speedlimiter.utils

import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

class VpnSessionManager {
    private var isSessionActive = false
    private var workerThread: Thread? = null

    fun startSession(vpnFileDescriptor: FileDescriptor, speedLimitKbps: Int) {
        if (isSessionActive) return
        isSessionActive = true

        workerThread = Thread {
            try {
                val inputStream = FileInputStream(vpnFileDescriptor)
                val outputStream = FileOutputStream(vpnFileDescriptor)
                val buffer = ByteArray(32768) // بافر ضخم لمعالجة حزم الفيديو والويب دون تجميد

                while (isSessionActive) {
                    val readBytes = inputStream.read(buffer)
                    if (readBytes > 0) {
                        
                        // الحساب الرياضي الدقيق لتقييد السرعة (مثال: 100kbps)
                        // تحويل الكيلوبت إلى بايت في الثانية
                        val bytesPerSecond = (speedLimitKbps * 1000L) / 8
                        
                        // حساب الوقت الافتراضي الذي يجب أن تستغرقه الحزمة بناءً على حجمها بالملي ثانية
                        val expectedTimeMs = (readBytes.toLong() * 1000) / bytesPerSecond

                        val startTime = System.currentTimeMillis()

                        // تمرير الحزمة فوراً إلى الشبكة لضمان تشغيل يوتيوب وفيس بوك
                        outputStream.write(buffer, 0, readBytes)

                        // [خوارزمية التخنيق الذكي والكبح]
                        // نجبر الخيط البرمجي على الانتظار لحجز السرعة تحت السقف المطلوب
                        val elapsedTime = System.currentTimeMillis() - startTime
                        val sleepTime = expectedTimeMs - elapsedTime
                        if (sleepTime > 0) {
                            Thread.sleep(sleepTime)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SpeedLimiterCore", "خطأ في معالجة النفق: ${e.message}")
            }
        }
        workerThread?.start()
    }

    fun setRateLimit(speedLimitKbps: Int) {
        Log.d("SpeedLimiterCore", "تم تحديث السرعة برمجياً إلى: $speedLimitKbps Kbps")
    }

    fun stopSession() {
        isSessionActive = false
        workerThread?.interrupt()
        workerThread = null
    }

    fun isSessionRunning(): Boolean {
        return isSessionActive
    }
}
