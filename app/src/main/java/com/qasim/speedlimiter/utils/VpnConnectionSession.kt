package com.qasim.speedlimiter.utils

import android.net.VpnService
import android.util.Log
import com.qasim.speedlimiter.data.services.LocalVpnService
import com.qasim.speedlimiter.data.services.TcpSelectorEngine
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.net.InetAddress
import kotlin.concurrent.thread

class VpnSessionManager {
    private var isSessionActive = false
    private var readerThread: Thread? = null
    private var dispatcherThread: Thread? = null

    // طابور الحزم الوسيط للفصل التام بين القراءة والتوزيع
    private val packetQueue = LinkedBlockingQueue<ByteArray>(2000)
    
    // مرجع لقناة الكتابة إلى نظام الأندرويد لإرسال الحزم المحقونة والمحددة السرعة
    private var vpnOutputStream: FileOutputStream? = null

    fun startSession(vpnFileDescriptor: FileDescriptor, speedLimitKbps: Int, vpnService: VpnService) {
        if (isSessionActive) return
        isSessionActive = true
        packetQueue.clear()

        val bytesPerSecond = (speedLimitKbps * 1024L)
        LocalVpnService.downloadBucket.updateRate(bytesPerSecond, bytesPerSecond)
        
        vpnOutputStream = FileOutputStream(vpnFileDescriptor)

        // 1. خيط القراءة (Reader Thread)
        readerThread = thread(start = true, name = "VpnReaderThread") {
            val inputChannel = FileInputStream(vpnFileDescriptor).channel
            val buffer = ByteBuffer.allocateDirect(16384)

            try {
                while (isSessionActive) {
                    buffer.clear()
                    val readBytes = inputChannel.read(buffer)
                    if (readBytes > 0) {
                        buffer.flip()
                        val packetData = ByteArray(readBytes)
                        buffer.get(packetData)
                        
                        if (!packetQueue.offer(packetData, 10, TimeUnit.MILLISECONDS)) {
                            packetQueue.poll()
                            packetQueue.offer(packetData)
                        }
                    } else {
                        Thread.sleep(2)
                    }
                }
            } catch (e: Exception) {
                Log.e("VpnCore", "توقف خيط القراءة: ${e.message}")
            } finally {
                try { inputChannel.close() } catch (e: Exception) {}
            }
        }

        // 2. خيط التوزيع المطور (Dispatcher Thread) - تحديث استراتيجية التوجيه والمصافحة
        dispatcherThread = thread(start = true, name = "VpnDispatcherThread") {
            try {
                Log.d("VpnCore", "تم تشغيل استراتيجية التوجيه الذكية وحل المصافحة للـ TCP.")
                
                while (isSessionActive) {
                    val packetData = packetQueue.poll(10, TimeUnit.MILLISECONDS)
                    
                    if (packetData != null) {
                        val buffer = ByteBuffer.wrap(packetData)
                        val protocolType = NetworkPacketUtils.getProtocolFromPacket(buffer)
                        
                        if (protocolType == 6) { // TCP Protocol
                            buffer.order(ByteOrder.BIG_ENDIAN)
                            
                            // استخراج علم الـ TCP (Flags) لمعرفة نوع الحزمة (هل هي SYN بداية اتصال؟)
                            val ipHeaderLength = (buffer.get(0).toInt() and 0x0F) * 4
                            val tcpFlagsOffset = ipHeaderLength + 13
                            val flags = buffer.get(tcpFlagsOffset).toInt() and 0xFF
                            
                            val isSYN = (flags and 0x02) != 0
                            
                            if (isSYN) {
                                // 🚀 [استراتيجية التوجيه الحاسمة]: الرد الفوري بحزمة SYN-ACK وهمية للتطبيق لفتح الاتصال وعدم الانقطاع
                                handleTcpSynPacket(buffer, ipHeaderLength)
                            }
                            
                            // تمرير الحزمة فوراً إلى محرك السوكتات الخارجي لفتح الاتصال الحقيقي بخنق السرعة
                            buffer.clear()
                            TcpSelectorEngine.processOutgoingPacket(buffer)
                            
                        } else {
                            // تمرير حزم الـ UDP والـ DNS مباشرة للشبكة بدون إعاقة لحماية الاستقرار
                            writePacketToAndroid(packetData)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VpnCore", "توقف خيط التوزيع: ${e.message}")
            }
        }
    }

    /**
     * دالة ذكية لصناعة حزمة SYN-ACK ومحاكاة المصافحة الثلاثية لمنع انقطاع يوتيوب وفيسبوك
     */
    private fun handleTcpSynPacket(buffer: ByteBuffer, ipHeaderLength: Int) {
        try {
            val srcIpBuf = ByteArray(4)
            val destIpBuf = ByteArray(4)
            buffer.position(12)
            buffer.get(srcIpBuf)
            buffer.get(destIpBuf)
            
            buffer.position(ipHeaderLength)
            val srcPort = buffer.short.toInt() and 0xFFFF
            val destPort = buffer.short.toInt() and 0xFFFF
            val clientSeq = buffer.int
            
            // إنشاء جلسة اتصال فرعية وهمية فورية لتسجيل أرقام الـ Sequence المبدئية
            val sessionKey = "${InetAddress.getByAddress(srcIpBuf).hostAddress}:$srcPort -> ${InetAddress.getByAddress(destIpBuf).hostAddress}:$destPort"
            var session = VpnConnectionSession.getSession(sessionKey)
            if (session == null) {
                session = VpnConnectionSession().apply {
                    this.sessionKey = sessionKey
                    this.localAddressIp = ByteBuffer.wrap(srcIpBuf).int
                    this.remoteAddressIp = ByteBuffer.wrap(destIpBuf).int
                    this.localPort = srcPort
                    this.remotePort = destPort
                    this.receiveNextSequenceNumber = clientSeq + 1
                    this.sendNextSequenceNumber = 1000 // رقم مبدئي عشوائي
                    this.connectionState = 1 // جاري الاتصال
                }
                VpnConnectionSession.addSession(sessionKey, session)
            }

            // بناء حزمة الـ SYN-ACK الرد وضخها للهاتف
            val replyBuffer = ByteBuffer.allocate(40)
            val emptyData = ByteBuffer.allocate(0)
            NetworkPacketUtils.buildTcpPacket(replyBuffer, session, emptyData, 0)
            
            // تعديل الأعلام في الحزمة المبنية لتصبح SYN-ACK (0x12) بدلاً من PSH-ACK
            replyBuffer.put(33, 0x12.toByte()) 
            
            writePacketToAndroid(replyBuffer.array())
            
            // تحديث الـ Sequence للمستقبل
            session.sendNextSequenceNumber += 1
        } catch (e: Exception) {
            Log.e("VpnCore", "فشل محاكاة الـ SYN-ACK: ${e.message}")
        }
    }

    /**
     * دالة مركزية آمنة لحقن الحزم القادمة من الإنترنت داخل نظام الأندرويد
     */
    fun writePacketToAndroid(packetData: ByteArray) {
        try {
            val outputStream = vpnOutputStream ?: return
            synchronized(outputStream) {
                outputStream.write(packetData)
                outputStream.flush()
            }
        } catch (e: Exception) {
            Log.e("VpnCore", "خطأ أثناء ضخ الحزمة للأندرويد: ${e.message}")
        }
    }

    fun setRateLimit(speedLimitKbps: Int) {
        Log.d("VpnCore", "تحديث سقف السرعة في المحرك إلى: $speedLimitKbps Kbps")
        val bytesPerSecond = (speedLimitKbps * 1024L)
        LocalVpnService.downloadBucket.updateRate(bytesPerSecond, bytesPerSecond)
    }

    fun stopSession() {
        isSessionActive = false
        readerThread?.interrupt()
        dispatcherThread?.interrupt()
        readerThread = null
        dispatcherThread = null
        packetQueue.clear()
        VpnConnectionSession.closeAllSessions()
        try { vpnOutputStream?.close() } catch (e: Exception) {}
        vpnOutputStream = null
    }

    fun isSessionRunning(): Boolean = isSessionActive
}
