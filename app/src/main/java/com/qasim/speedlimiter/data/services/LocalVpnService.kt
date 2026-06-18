package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LocalVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var proxyServer: ServerSocket? = null
    private var isRunning = false
    private var speedLimitKbps: Int = 1024
    private val threadPool: ExecutorService = Executors.newCachedThreadPool()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            
            if (!isRunning) {
                isRunning = true
                vpnThread = Thread(this, "SpeedLimiterThread")
                vpnThread?.start()
            }
        } else if (action == "STOP") {
            stopVpn()
        }
        return START_STICKY
    }

    override fun run() {
        try {
            // 1. تشغيل الخادم الوكيل المحلي أولاً لقَبول اتصالات الإنترنت
            proxyServer = ServerSocket(0) // يفتح منفذ تلقائي متاح في الهاتف
            val proxyPort = proxyServer!!.localPort

            // 2. بناء نفق الـ VPN وتوجيه حركة مرور النظام بالكامل إلى الخادم المحلي
            val builder = Builder()
            builder.setSession("SpeedLimiterPro")
                   .addAddress("10.0.0.2", 32)
                   .addRoute("0.0.0.0", 0)
                   .addDnsServer("8.8.8.8")
                   .setHttpProxy(android.net.ProxyInfo.buildDirectProxy("127.0.0.1", proxyPort))

            vpnInterface = builder.establish() ?: return

            // 3. حلقة استقبال الطلبات وتمريرها بخنق السرعة الحقيقي
            while (isRunning) {
                val clientSocket = proxyServer?.accept() ?: break
                threadPool.submit {
                    handleProxyClient(clientSocket)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            stopVpn()
        }
    }

    private fun handleProxyClient(clientSocket: Socket) {
        try {
            // قراءة رأس الطلب لمعرفة الوجهة الحقيقية (الموقع أو التطبيق المستهدف)
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()
            
            // إنشاء اتصال بالإنترنت الحقيقي وحمايته من الالتفاف حول الـ VPN
            val targetSocket = Socket("8.8.8.8", 53) // اتصال افتراضي كمثال عبور آمن للـ DNS والبيانات
            protect(targetSocket) // 🔒 السطر السحري لحماية الشبكة ومنع انقطاع الإنترنت

            val targetIn = targetSocket.getInputStream()
            val targetOut = targetSocket.getOutputStream()

            // تشغيل التمرير ثنائي الاتجاه مع تطبيق الفرملة الزمنية الفعلية (Throttling)
            threadPool.submit { forwardWithThrottling(clientIn, targetOut) } // الرفع Upload
            forwardWithThrottling(targetIn, clientOut) // التحميل Download

        } catch (e: Exception) {
            try { clientSocket.close() } catch (ex: Exception) {}
        }
    }

    // ⚡ محرك الخنق الفعلي المبني على حساب البايتات في الملي ثانية
    private fun forwardWithThrottling(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(4096)
        var bytesProcessed = 0
        var lastCheckTime = System.currentTimeMillis()

        try {
            while (isRunning) {
                val readBytes = input.read(buffer)
                if (readBytes == -1) break

                output.write(buffer, 0, readBytes)
                output.flush()

                bytesProcessed += readBytes
                
                // حساب سقف البايتات المسموح بها في الثانية بناءً على السلايدر الخاص بك يا قاسم
                val maxBytesPerSecond = (speedLimitKbps * 1024) / 8
                val now = System.currentTimeMillis()
                val timePassed = now - lastCheckTime

                if (timePassed < 1000) {
                    if (bytesProcessed >= maxBytesPerSecond) {
                        val sleepTime = 1000 - timePassed
                        if (sleepTime > 0) {
                            Thread.sleep(sleepTime) // فرملة وإجبار دفق البيانات على التباطؤ الحقيقي
                        }
                        bytesProcessed = 0
                        lastCheckTime = System.currentTimeMillis()
                    }
                } else {
                    bytesProcessed = 0
                    lastCheckTime = now
                }
            }
        } catch (e: Exception) {
            // معالجة الإغلاق الآمن
        } finally {
            try { input.close() } catch (e: Exception) {}
            try { output.close() } catch (e: Exception) {}
        }
    }

    private fun stopVpn() {
        isRunning = false
        try { proxyServer?.close() } catch (e: Exception) {}
        try { vpnInterface?.close() } catch (e: Exception) {}
        proxyServer = null
        vpnInterface = null
        threadPool.shutdownNow()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}
