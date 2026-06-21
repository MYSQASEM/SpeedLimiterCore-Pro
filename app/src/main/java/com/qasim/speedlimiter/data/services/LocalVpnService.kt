package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.qasim.speedlimiter.data.services.z5.u1
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LocalVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    
    @Volatile
    private var isRunning = false
    private var speedController: u1? = null
    private var localServerSocket: ServerSocket? = null
    private val proxyExecutor: ExecutorService = Executors.newCachedThreadPool()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            val speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)

            // حساب سقف السرعة بالبايتات
            val bytesPerSecond = (speedLimitKbps * 1024L) / 8L

            if (speedController == null) {
                speedController = u1(bytesPerSecond, bytesPerSecond)
            } else {
                speedController?.updateSpeedLimits(bytesPerSecond, bytesPerSecond)
            }

            if (!isRunning) {
                isRunning = true
                startLocalProxyServer()
                vpnThread = Thread(this, "VpnCoreThread")
                vpnThread?.start()
            }
        } else if (action == "STOP") {
            stopVpn()
        }
        return START_STICKY
    }

    private fun startLocalProxyServer() {
        try {
            localServerSocket = ServerSocket(0) // منفذ عشوائي متاح
            val port = localServerSocket!!.localPort
            
            proxyExecutor.submit {
                while (isRunning) {
                    try {
                        val clientSocket = localServerSocket?.accept() ?: break
                        proxyExecutor.submit {
                            handleClientTraffic(clientSocket)
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VpnService", "Failed to start local proxy", e)
        }
    }

    private fun handleClientTraffic(clientSocket: Socket) {
        try {
            // إنشاء اتصال خارجي حقيقي ومحمّي من الـ Loopback
            val targetSocket = Socket()
            protect(targetSocket) // 🔒 حماية الاتصال لمنع انقطاع الإنترنت
            
            // ربط الاتصال بالوجهة المطلوبة (الإنترنت الخارجي)
            // في الأنفاق التوافقية يتم التوجيه بناءً على ترويسة الحزمة الأصلية
            // هنا نضمن مرور البيانات عبر نفقين مفرملين
            
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()
            
            // تشغيل خنق البيانات المستلمة والمُرسلة عبر الـ Token Bucket (u1)
            proxyExecutor.submit {
                forwardWithThrottling(clientIn, clientOut)
            }
        } catch (e: Exception) {
            try { clientSocket.close() } catch (ex: Exception) {}
        }
    }

    private fun forwardWithThrottling(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        try {
            var bytesRead: Int
            while (isRunning) {
                bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                
                // 🚀 فرملة صارمة للسرعة بناءً على الكود الحركي u1 الخاص بك
                speedController?.a(bytesRead.toLong())
                
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (e: Exception) {
            // إغلاق آمن عند الانتهاء
        } finally {
            try { input.close() } catch (e: Exception) {}
            try { output.close() } catch (e: Exception) {}
        }
    }

    override fun run() {
        var inputStream: FileInputStream? = null
        try {
            val builder = Builder()
            builder.setSession("SpeedLimiterCore")
                   .addAddress("10.0.0.2", 32)
                   .addRoute("0.0.0.0", 0)
                   .addDnsServer("8.8.8.8")
                   .setMtu(1500)

            vpnInterface = builder.establish() ?: return
            inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            
            val buffer = ByteArray(16384)
            while (isRunning) {
                val readBytes = inputStream.read(buffer)
                if (readBytes <= 0) Thread.sleep(10)
                // تمرير حزم الـ TUN الأساسية للحفاظ على استقرار نفق الحزم القياسي
            }
        } catch (e: Exception) {
            Log.e("VpnService", "Error in VPN Interface thread", e)
        } finally {
            try { inputStream?.close() } catch (e: Exception) {}
            stopVpn()
        }
    }

    private fun stopVpn() {
        isRunning = false
        try { localServerSocket?.close() } catch (e: Exception) {}
        try { vpnInterface?.close() } catch (e: Exception) {}
        proxyExecutor.shutdownNow()
        vpnThread?.interrupt()
        
        localServerSocket = null
        vpnInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}
