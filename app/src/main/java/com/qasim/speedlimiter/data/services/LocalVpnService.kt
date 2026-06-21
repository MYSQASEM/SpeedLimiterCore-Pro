package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.qasim.speedlimiter.data.services.z5.h
import com.qasim.speedlimiter.data.services.z5.k
import com.qasim.speedlimiter.data.services.z5.u1
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors

class LocalVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var tcpThread: Thread? = null
    private var udpThread: Thread? = null
    private var isRunning = false

    private var speedController: u1? = null
    private var tcpSelector: Selector? = null
    private var udpSelector: Selector? = null
    
    private val outputQueue: BlockingQueue<ByteBuffer> = ArrayBlockingQueue(2000)
    private val executorService = Executors.newSingleThreadExecutor()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            val speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)

            val bytesPerSecond = (speedLimitKbps * 1024L) / 8L

            if (speedController == null) {
                speedController = u1(bytesPerSecond, bytesPerSecond)
            } else {
                speedController?.updateSpeedLimits(bytesPerSecond, bytesPerSecond)
            }

            if (!isRunning) {
                isRunning = true
                tcpSelector = Selector.open()
                udpSelector = Selector.open()

                // تشغيل المحركات الحركية للجافا للاستماع للردود الخارجية
                tcpThread = Thread(h(outputQueue, tcpSelector, speedController), "VpnTcpEngine")
                udpThread = Thread(k(outputQueue, udpSelector, speedController), "VpnUdpEngine")
                
                tcpThread?.start()
                udpThread?.start()

                vpnThread = Thread(this, "VpnPacketReader")
                vpnThread?.start()
                
                startWriteLoop()
            }
        } else if (action == "STOP") {
            stopVpn()
        }
        return START_STICKY
    }

    override fun run() {
        var inputStream: FileInputStream? = null
        try {
            val builder = Builder()
            builder.setSession("SpeedLimiterPro")
                   .addAddress("10.0.0.2", 32)
                   .addRoute("0.0.0.0", 0)
                   .addDnsServer("8.8.8.8")
                   .setMtu(1500)

            vpnInterface = builder.establish() ?: return
            inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteArray(16384)

            while (isRunning) {
                val readBytes = inputStream.read(buffer)
                if (readBytes > 0) {
                    val packet = ByteBuffer.wrap(buffer, 0, readBytes)
                    
                    if (readBytes < 20) continue
                    val protocol = packet.get(9).toInt() and 0xFF
                    
                    // تطبيق فرملة الرفع (Upload) بناءً على حجم الحزمة الحقيقي
                    speedController?.a(readBytes.toLong())

                    // 🚀 التوجيه الصحيح: تفكيك وإرسال الحزم للقنوات الحقيقية بدلاً من حبسها داخلياً
                    if (protocol == 6) { // TCP Protocol
                        val destIp = "${packet.get(16).toInt() and 0xFF}.${packet.get(17).toInt() and 0xFF}.${packet.get(18).toInt() and 0xFF}.${packet.get(19).toInt() and 0xFF}"
                        try {
                            val socketChannel = SocketChannel.open()
                            socketChannel.configureBlocking(false)
                            protect(socketChannel.socket()) // حماية حتمية لمنع الـ Loopback
                            socketChannel.connect(InetSocketAddress(destIp, 80))
                            socketChannel.register(tcpSelector, SelectionKey.OP_CONNECT or SelectionKey.OP_READ, "TCP_Session")
                            socketChannel.write(packet)
                        } catch (e: Exception) {}
                    } else if (protocol == 17) { // UDP Protocol (يتضمن الـ DNS للألعاب والمواقع)
                        val destIp = "${packet.get(16).toInt() and 0xFF}.${packet.get(17).toInt() and 0xFF}.${packet.get(18).toInt() and 0xFF}.${packet.get(19).toInt() and 0xFF}"
                        try {
                            val datagramChannel = DatagramChannel.open()
                            datagramChannel.configureBlocking(false)
                            protect(datagramChannel.socket())
                            datagramChannel.connect(InetSocketAddress(destIp, 53)) // ربطه بمنفذ الـ DNS أو الوجهة
                            datagramChannel.register(udpSelector, SelectionKey.OP_READ, "UDP_Session")
                            datagramChannel.write(packet)
                        } catch (e: Exception) {}
                    } else {
                        // تمرير مباشر ومؤمن للحزم الفرعية الأخرى
                        val packetCopy = ByteBuffer.allocate(readBytes)
                        packetCopy.put(buffer, 0, readBytes)
                        packetCopy.flip()
                        outputQueue.put(packetCopy)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VpnService", "Error routing packets to selectors", e)
        } finally {
            try { inputStream?.close() } catch (e: Exception) {}
            stopVpn()
        }
    }

    private fun startWriteLoop() {
        executorService.submit {
            var outputStream: FileOutputStream? = null
            try {
                vpnInterface?.fileDescriptor?.let { fd ->
                    outputStream = FileOutputStream(fd)
                    while (isRunning) {
                        val buffer = outputQueue.take()
                        outputStream?.write(buffer.array(), 0, buffer.limit())
                        outputStream?.flush()
                    }
                }
            } catch (e: Exception) {
                Log.e("VpnService", "Error writing back to interface", e)
            } finally {
                try { outputStream?.close() } catch (e: Exception) {}
            }
        }
    }

    private fun stopVpn() {
        isRunning = false
        vpnThread?.interrupt()
        tcpThread?.interrupt()
        udpThread?.interrupt()
        
        try { tcpSelector?.close() } catch (e: Exception) {}
        try { udpSelector?.close() } catch (e: Exception) {}
        try { vpnInterface?.close() } catch (e: Exception) {}
        
        vpnInterface = null
        tcpSelector = null
        udpSelector = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        executorService.shutdownNow()
    }
}
