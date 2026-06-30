package com.qasim.speedlimiter.utils

import android.net.VpnService
import android.util.Log
import com.qasim.speedlimiter.data.services.LocalVpnService
import com.qasim.speedlimiter.data.services.TcpSelectorEngine // 🚀 تفعيل مسار محرك السوكتات الصحيح
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class VpnSessionManager {
    private var isSessionActive = false
    private var readerThread: Thread? = null
    private var dispatcherThread: Thread? = null

    // طابور الحزم الوسيط للفصل التام بين القراءة والتوزيع
    private val packetQueue = LinkedBlockingQueue<ByteArray>(1000)
    
    // مرجع لقناة الكتابة إلى نظام الأندرويد لإرسال الحزم المحقونة والمحددة السرعة
    private var vpnOutputStream: FileOutputStream? = null

    fun startSession(vpnFileDescriptor: FileDescriptor, speedLimitKbps: Int, vpnService: VpnService) {
        if (isSessionActive) return
        isSessionActive = true
        packetQueue.clear()

        val bytesPerSecond = (speedLimitKbps * 1024L)
        LocalVpnService.downloadBucket.updateRate(bytesPerSecond, bytesPerSecond)
        
        vpnOutputStream = FileOutputStream(vpnFileDescriptor)

        // 1. خيط القراءة (Reader Thread): يسحب الحزم الصادرة من تطبيقات الهاتف
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
                        Thread.sleep(5)
                    }
                }
            } catch (e: Exception) {
                Log.e("VpnCore", "توقف خيط القراءة: ${e.message}")
            } finally {
                try { inputChannel.close() } catch (e: Exception) {}
            }
        }

        // 2. خيط التوزيع والربط الحقيقي (Dispatcher Thread)
        dispatcherThread = thread(start = true, name = "VpnDispatcherThread") {
            try {
                Log.d("VpnCore", "تم تشغيل الموزع المطور والربط الحقيقي بالسلايدر.")
                
                while (isSessionActive) {
                    val packetData = packetQueue.poll(10, TimeUnit.MILLISECONDS)
                    
                    if (packetData != null) {
                        val buffer = ByteBuffer.wrap(packetData)
                        
                        // استخراج نوع البروتوكول باستخدام كلاس الأدوات
                        val protocolType = NetworkPacketUtils.getProtocolFromPacket(buffer)
                        
                        if (protocolType == 6) { // 6 = TCP Protocol
                            // 🚀 [تفعيل المعالجة الحقيقية]: كسر الحلقة المفرغة نهائياً
                            // تمرير الحزمة الصادرة فوراً إلى محرك السوكتات ليفتح اتصالاً بالإنترنت ويخنق التنزيل العائد
                            
                            buffer.clear() // إعادة المؤشر للبداية قبل التمرير
                            
                            // 🛠️ تفعيل الاستدعاء الفعلي للمحرك:
                            TcpSelectorEngine.processOutgoingPacket(buffer)
                            
                        } else {
                            // الحزم الأخرى (مثل UDP والـ DNS المستثنى) تمرر مباشرة لتأمين استقرار المتصفح وعدم انقطاع الخدمات
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
     * دالة مركزية آمنة لحقن الحزم القادمة من الإنترنت والمحددة السرعة داخل نظام الأندرويد
     */
    fun writePacketToAndroid(packetData: ByteArray) {
        try {
            val outputStream = vpnOutputStream ?: return
            synchronized(outputStream) {
                outputStream.write(packetData)
                outputStream.flush()
            }
        } catch (e: Exception) {
            Log.e("VpnCore", "خطأ أثناء ضخ الحزمة المحددة للأندرويد: ${e.message}")
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
        try { vpnOutputStream?.close() } catch (e: Exception) {}
        vpnOutputStream = null
    }

    fun isSessionRunning(): Boolean = isSessionActive
}
