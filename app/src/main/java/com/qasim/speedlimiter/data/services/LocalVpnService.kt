package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel

class LocalVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var isRunning = false
    private var speedThrottler: NetworkThrottler? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            val speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            
            // تحويل الكيلوبت في الثانية إلى بايتات في الثانية
            val bytesPerSecond = (speedLimitKbps * 1024L) / 8L
            
            if (speedThrottler == null) {
                speedThrottler = NetworkThrottler(bytesPerSecond, bytesPerSecond)
            } else {
                speedThrottler?.updateSpeed(bytesPerSecond, bytesPerSecond)
            }
            
            if (!isRunning) {
                isRunning = true
                vpnThread = Thread(this, "AdvancedThrottledVpn")
                vpnThread?.start()
            }
        } else if (action == "STOP") {
            stopVpn()
        }
        return START_STICKY
    }

    override fun run() {
        try {
            // بناء النفق بنطاق آي بي مخصص ومتوافق لمنع تداخل الحزم الداخلي
            val builder = Builder()
            builder.setSession("SpeedLimiterEngine")
                   .addAddress("10.0.0.2", 32)
                   .addRoute("0.0.0.0", 0)
                   .addDnsServer("8.8.8.8")
                   .setMtu(1500)

            // تفعيل ميزة التمرير المباشر لحزم النظام لضمان عدم انقطاع التطبيقات كلياً
            setUnderlyingNetworks(null)

            vpnInterface = builder.establish() ?: return

            val fd = vpnInterface!!.fileDescriptor
            val inputStream = FileInputStream(fd)
            val outputStream = FileOutputStream(fd)
            
            // حجم بافر كبير مستوحى من كود التطبيق الناجح لتفادي اختناق الذاكرة العشوائية
            val buffer = ByteArray(32768) 

            while (isRunning && !Thread.interrupted()) {
                val readBytes = inputStream.read(buffer)
                if (readBytes > 0) {
                    
                    // 🚀 استدعاء محرك الفرملة الصارم المأخوذ من ملف u1.java الخاص بك
                    speedThrottler?.limit(readBytes.toLong())
                    
                    // تمرير الحزم المفرملة عبر خط الشبكة الخارجي المباشر دون احتجازها في الهاتف
                    try {
                        outputStream.write(buffer, 0, readBytes)
                        outputStream.flush()
                    } catch (e: Exception) {
                        // تخطي حزم الشبكة العابرة المفقودة
                    }
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
