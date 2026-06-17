package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.qasim.speedlimiter.AppConfig
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class LocalVpnService : VpnService(), Runnable {
    private var vpnThread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null

    // جلب السرعة المحددة من الإعدادات (مثلاً بالكيلوبايت في الثانية)
    private var speedLimitKbps: Int = 1024 // القيمة الافتراضية 1 ميجا

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            // جلب السرعة المحددة التي اختارها المستخدم قبل التشغيل
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
            // تجنب توقف التطبيق في حال كان مغلقاً بالفعل
        }
        vpnInterface = null
        stopSelf()
    }

    override fun run() {
        try {
            val builder = Builder()
            builder.setSession("SpeedLimiterVpn")
                   .addAddress("10.0.0.2", 32)
                   // لتجنب قطع الإنترنت بالكامل، التطبيقات الاحترافية توجه الحزم وتستثني قنوات النظام أو تمررها لخادم محلي
                   .addRoute("0.0.0.0", 0) 
                   .addDnsServer("8.8.8.8")

            // ميزة احترافية: يمكنك منع الـ VPN من قطع الإنترنت عن تطبيقك نفسه لكي يستطيع الاتصال بالشبكة الخارجية
            builder.addDisallowedApplication(packageName)

            vpnInterface = builder.establish()
            
            val inputStream = FileInputStream(vpnInterface?.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface?.fileDescriptor)
            val buffer = ByteArray(16384)

            // متغيرات لحساب السرعة والتحكم بها (Throttling)
            var bytesReadInCurrentSecond = 0
            var startTime = System.currentTimeMillis()

            while (!Thread.interrupted()) {
                val readBytes = inputStream.read(buffer)
                if (readBytes > 0) {
                    
                    // ⚡ [منطقة التحكم بالسرعة - Speed Limiter Core]
                    bytesReadInCurrentSecond += readBytes
                    val currentTime = System.currentTimeMillis()
                    
                    // إذا مرّت ثانية أو جزء منها وتجاوزنا الحد المسموح، نجبر الخيط على النوم (Delay)
                    if (currentTime - startTime < 1000) {
                        val maxBytesAllowed = (speedLimitKbps * 1024) / 8 // تحويل من بت إلى بايت
                        if (bytesReadInCurrentSecond >= maxBytesAllowed) {
                            val sleepTime = 1000 - (currentTime - startTime)
                            if (sleepTime > 0) {
                                Thread.sleep(sleepTime) // خنق السرعة هنا!
                            }
                            // إعادة تصوير العدادات للثانية القادمة
                            bytesReadInCurrentSecond = 0
                            startTime = System.currentTimeMillis()
                        }
                    } else {
                        // مرّت ثانية كاملة دون تجاوز الحد، نُصفر العداد
                        bytesReadInCurrentSecond = 0
                        startTime = currentTime
                    }

                    // تمرير البيانات للشبكة الحقيقية (تعديل مسار الحزم)
                    // ملاحظة: لجعل الإنترنت يعمل بكفاءة 100%، نقوم بنقل الحزم وإعادتها عبر كود حماية النظام (protect)
                    outputStream.write(buffer, 0, readBytes)
                }
                
                Thread.sleep(1) // تقليل الاستهلاك المفرط للمعالج لتطبيقه في سوق بلاي
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
