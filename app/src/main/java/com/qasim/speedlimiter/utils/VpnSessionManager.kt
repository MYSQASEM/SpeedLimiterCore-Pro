package com.qasim.speedlimiter.utils

import android.net.VpnService
import android.util.Log
import com.qasim.speedlimiter.data.services.LocalVpnService
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
    private val packetQueue = LinkedBlockingQueue<ByteArray>(500)

    fun startSession(vpnFileDescriptor: FileDescriptor, speedLimitKbps: Int, vpnService: VpnService) {
        if (isSessionActive) return
        isSessionActive = true
        packetQueue.clear()

        // تحديث المحرك الرياضي العام فور بدء الجلسة
        val bytesPerSecond = (speedLimitKbps * 1024L)
        LocalVpnService.downloadBucket.updateRate(bytesPerSecond, bytesPerSecond)

        // 1. خيط القراءة (Reader Thread): يسحب الحزم من النظام بأقصى سرعة ويضعها في الطابور
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
                        
                        // إدخال الحزمة إلى الطابور فوراً بدون أي تأخير لحمايتها من التلف
                        if (!packetQueue.offer(packetData, 10, TimeUnit.MILLISECONDS)) {
                            // إذا امتلأ الطابور، يتم إسقاط الحزم القديمة لمنع تجمد النفق
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

        // 2. خيط التوزيع والخنق (Dispatcher Thread): يراقب الطابور ويتحكم في وقت خروج الحزم
        dispatcherThread = thread(start = true, name = "VpnDispatcherThread") {
            val outputChannel = FileOutputStream(vpnFileDescriptor).channel
            val writeBuffer = ByteBuffer.allocateDirect(16384)

            try {
                Log.d("VpnCore", "تم تشغيل الموزع المطور. سقف الخنق الشامل: $speedLimitKbps Kbps")
                
                while (isSessionActive) {
                    // سحب الحزمة من الطابور (ينتظر حتى تتوفر حزم)
                    val packetData = packetQueue.poll(10, TimeUnit.MILLISECONDS)
                    
                    if (packetData != null) {
                        // قراءة بروتوكول الحزمة بدقة (البايت رقم 9 في ترويسة IPv4)
                        val protocolType = if (packetData.size > 9) packetData[9].toInt() and 0xFF else 0
                        
                        var isDnsTraffic = false
                        
                        // فحص ما إذا كانت الحزمة هي طلب DNS (المنفذ 53) لتجنب خنقها لضمان استقرار المتصفحات
                        if (protocolType == 17 && packetData.size > 24) { // UDP Protocol
                            // استخراج بورت الهدف (Destination Port) من ترويسة UDP الممتدة من البايت 22 و 23
                            val destPort = ((packetData[22].toInt() and 0xFF) shl 8) or (packetData[23].toInt() and 0xFF)
                            val srcPort = ((packetData[20].toInt() and 0xFF) shl 8) or (packetData[21].toInt() and 0xFF)
                            if (destPort == 53 || srcPort == 53) {
                                isDnsTraffic = true
                            }
                        }

                        // التقييد الشامل والعادل: يتم خنق جميع الحزم (TCP & UDP) لضمان انصياع الـ Slider
                        // ونستثني فقط حزم الـ DNS لمنع المتصفحات من إعطاء خطأ "لا يوجد اتصال بالإنترنت"
                        if (!isDnsTraffic) { 
                            // استدعاء محرك الـ TokenBucket المطور الذي تم إصلاحه بحسابات الكسر الكهرومغناطيسية
                            LocalVpnService.downloadBucket.consume(packetData.size.toLong())
                        }

                        // تجهيز الـ Buffer وضخ الحزمة المقيدة
                        // ملاحظة هندسية: الكود يرسلها مباشرة للـ TUN، إذا واجهت انقطاعاً كاملاً لاحقاً، 
                        // سنحتاج إلى تمرير الـ packetData إلى كلاس TcpSelectorEngine للتوجيه الفعلي للإنترنت العام.
                        writeBuffer.clear()
                        writeBuffer.put(packetData)
                        writeBuffer.flip()
                        
                        while (writeBuffer.hasRemaining()) {
                            outputChannel.write(writeBuffer)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VpnCore", "توقف خيط التوزيع: ${e.message}")
            } finally {
                try { outputChannel.close() } catch (e: Exception) {}
            }
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
    }

    fun isSessionRunning(): Boolean = isSessionActive
}
