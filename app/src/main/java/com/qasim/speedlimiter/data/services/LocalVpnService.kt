package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.File

class LocalVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var speedLimitKbps: Int = 1024

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            
            startVpnEngine()
        } else if (action == "STOP") {
            stopVpnEngine()
        }
        return START_STICKY
    }

    private fun startVpnEngine() {
        if (isRunning) return
        isRunning = true

        try {
            // 1. بناء النفق الرسمي للنظام
            val builder = Builder()
            builder.setSession("SpeedLimiterCore")
                   .addAddress("10.0.0.1", 24)
                   .addRoute("0.0.0.0", 0) // توجيه حركة مرور الجهاز بالكامل للمحرك
                   .addDnsServer("8.8.8.8")
                   .setMtu(1500)

            vpnInterface = builder.establish() ?: return

            // 2. تشغيل محرك Tun2Socks وتمرير حد السرعة الفعلي له (إدخال وإخراج)
            val fd = vpnInterface!!.detachFd()
            
            // تحويل الكيلوبت إلى بايت في الثانية لتمريره للمحرك الفعلي
            val maxBytesPerSecond = (speedLimitKbps * 1024) / 8

            // 🚀 استدعاء المحرك المدمج للقيام بالفرملة الحقيقية والتمرير للإنترنت الحقيقي
            sdk.tun2socks.Tun2socks.start(
                fd,
                1500,
                "127.0.0.1:0", // منفذ العبور التلقائي للشبكة الحية
                "",
                maxBytesPerSecond.toLong(), // ⚡ خنق سرعة التحميل (Download)
                maxBytesPerSecond.toLong()  // ⚡ خنق سرعة الرفع (Upload)
            )

        } catch (e: Exception) {
            e.printStackTrace()
            stopVpnEngine()
        }
    }

    private fun stopVpnEngine() {
        isRunning = false
        try {
            sdk.tun2socks.Tun2socks.stop()
        } catch (e: Exception) {}
        try {
            vpnInterface?.close()
        } catch (e: Exception) {}
        vpnInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpnEngine()
    }
}
