package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

class LocalVpnService : VpnService(), Runnable {
    private var vpnThread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var speedLimitKbps: Int = 1024 

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
        try { vpnInterface?.close() } catch (e: Exception) {}
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

            // التطبيقات المستهدفة بالخنق
            val targetPackages = listOf(
                "com.android.chrome",
                "com.google.android.youtube",
                "com.instagram.android",
                "com.zhiliaoapp.musically",
                "com.facebook.katana"
            )

            for (pkg in targetPackages) {
                try { builder.addAllowedApplication(pkg) } catch (e: Exception) {}
            }

            vpnInterface = builder.establish() ?: return
            
            val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteArray(16384)

            // عدادات منفصلة للتنزيل والتحميل
            var downloadBytes = 0
            var uploadBytes = 0
            var lastResetTime = System.currentTimeMillis()

            val maxBytesAllowed = (speedLimitKbps * 1024) / 8

            while (!Thread.interrupted()) {
                val readBytes = inputStream.read(buffer)
                if (readBytes > 0) {
                    
                    // برمجياً: الحزم الخارجة من الهاتف تعتبر (Upload) والحزم العائدة تعتبر (Download)
                    // نقوم بمحاكاة تصنيف الحزم وخنقها بناءً على السقف الإجمالي
                    uploadBytes += readBytes
                    downloadBytes += (readBytes * 0.8).toInt() // نسبة تقريبية لحزم التنزيل العائدة

                    val now = System.currentTimeMillis()
                    if (now - lastResetTime < 1000) {
                        if (downloadBytes >= maxBytesAllowed || uploadBytes >= maxBytesAllowed) {
                            val delay = 1000 - (now - lastResetTime)
                            if (delay > 0) {
                                Thread.sleep(delay) // خنق إجباري صارم للنفق لإجبار بروتوكول TCP على خفض السرعة
                            }
                            downloadBytes = 0
                            uploadBytes = 0
                            lastResetTime = System.currentTimeMillis()
                        }
                    } else {
                        downloadBytes = 0
                        uploadBytes = 0
                        lastResetTime = now
                    }

                    outputStream.write(buffer, 0, readBytes)
                }
                Thread.sleep(1)
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
