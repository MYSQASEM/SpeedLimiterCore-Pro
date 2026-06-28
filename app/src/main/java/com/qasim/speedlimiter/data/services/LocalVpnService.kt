package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.qasim.speedlimiter.utils.TokenBucket
import com.qasim.speedlimiter.utils.VpnSessionManager

class LocalVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    @Volatile private var isRunning = false
    private var speedLimitKbps: Int = 1024
    
    private val sessionManager = VpnSessionManager()

    companion object {
        // محرك السرعة الذكي والمتاح على مستوى الخدمة لربطه مع الـ Session Manager
        // القيمة الافتراضية الابتدائية (تُحسب بالبايت: كيلوبايت * 1024)
        val downloadBucket = TokenBucket(1024 * 1024L, 1024 * 1024L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            val inputLimit = sharedPrefs.getInt("speed_limit", 1024)
            
            speedLimitKbps = inputLimit.coerceIn(100, 30000)
            
            // تحديث فوري للمحرك الرياضي لتطبيق السرعة الجديدة وإيقاظ خيوط التوقف الفوري
            val limitInBytes = speedLimitKbps * 1024L
            downloadBucket.updateRate(limitInBytes, limitInBytes)
            
            if (isRunning) {
                sessionManager.setRateLimit(speedLimitKbps)
            } else {
                isRunning = true
                vpnThread = Thread(this, "SpeedVpnThread")
                vpnThread?.start()
            }
        } else if (action == "STOP") {
            stopVpn()
        }
        return START_STICKY
    }

    private fun buildTunnel() {
        try { vpnInterface?.close() } catch (e: Exception) {}

        val builder = Builder()
        builder.setSession("SpeedLimiterCorePro")
               .addAddress("10.0.0.2", 24) 
               .addRoute("0.0.0.0", 0)     
               .addDnsServer("8.8.8.8")
               .setMtu(1500)

        val targetApps = listOf(
            "com.android.chrome", 
            "com.google.android.youtube", 
            "com.facebook.katana",
            "org.zwanoo.android.speedtest"
        )
        for (app in targetApps) {
            try { builder.addAllowedApplication(app) } catch (e: Exception) {}
        }

        vpnInterface = builder.establish()
        
        if (vpnInterface != null) {
            // تشغيل الجلسة وتمرير النفق إلى الموزع المطور
            sessionManager.startSession(vpnInterface!!.fileDescriptor, speedLimitKbps, this)
            Log.d("LocalVpnService", "تم إنشاء واجهة الـ VPN وتمرير الجلسة للمحرك بنجاح.")
        } else {
            Log.e("LocalVpnService", "فشل في إنشاء واجهة الـ VPN")
        }
    }

    override fun run() {
        try {
            buildTunnel()
            while (isRunning) {
                Thread.sleep(1000)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            stopVpn()
        }
    }

    private fun stopVpn() {
        isRunning = false
        sessionManager.stopSession()
        
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
