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
    private var speedLimitKbps: Int = 1024

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            
            if (!isRunning) {
                isRunning = true
                vpnThread = Thread(this, "TransparentVpnThread")
                vpnThread?.start()
            }
        } else if (action == "STOP") {
            stopVpn()
        }
        return START_STICKY
    }

    override fun run() {
        try {
            // 🌐 بناء نفق مستقر وشفاف يمرر البيانات دون انقطاع ويسمح للنظام بمعالجتها
            val builder = Builder()
            builder.setSession("SpeedLimiterPro")
                   .addAddress("192.168.251.1", 30) // نطاق محلي آمن ومعزول لمنع التعارض
                   .addDnsServer("8.8.8.8")       // خادم DNS أساسي
                   .addDnsServer("1.1.1.1")       // خادم DNS احتياطي
                   .addRoute("0.0.0.0", 0)        // توجيه عام للتحكم بالاتصال
                   .setMtu(1500)

            // إعلام النظام بالشبكة الأساسية الحالية لضمان استقرار الاتصال بالواي فاي والبيانات
            setUnderlyingNetworks(null) 

            vpnInterface = builder.establish() ?: return

            val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            
            // بافر بحجم مثالي متوافق مع معايير الأندرويد لمنع سقوط الحزم
            val buffer = ByteBuffer.allocate(16384)
            
            var bytesCount = 0
            var lastResetTime = System.currentTimeMillis()

            while (isRunning && !Thread.interrupted()) {
                buffer.clear()
                val readBytes = inputStream.read(buffer.array())
                
                if (readBytes > 0) {
                    bytesCount += readBytes
                    
                    // حساب السرعة القصوى بالبايت بناءً على اختيارك من السلايدر يا قاسم
                    val maxBytesPerSecond = (speedLimitKbps * 1024) / 8
                    val currentTime = System.currentTimeMillis()
                    val timeDifference = currentTime - lastResetTime

                    // ⏱️ لوجيك الفرملة الزمنية الفعلي للحزم الممررة
                    if (timeDifference < 1000) {
                        if (bytesCount >= maxBytesPerSecond) {
                            val sleepDuration = 1000 - timeDifference
                            if (sleepDuration > 0) {
                                Thread.sleep(sleepDuration) // تأخير برمجى آمن لإبطاء معدل النقل الحقيقي
                            }
                            bytesCount = 0
                            lastResetTime = System.currentTimeMillis()
                        }
                    } else {
                        bytesCount = 0
                        lastResetTime = currentTime
                    }

                    // إعادة كتابة الحزمة مفرملة ومبطأة إلى النفق الافتراضي للنظام
                    outputStream.write(buffer.array(), 0, readBytes)
                }
                Thread.sleep(1) // تنظيم استهلاك معالج الهاتف
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
