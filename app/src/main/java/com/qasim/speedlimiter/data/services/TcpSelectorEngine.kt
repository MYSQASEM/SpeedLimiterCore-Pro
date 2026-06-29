package com.qasim.speedlimiter.data.services

import android.util.Log
import com.qasim.speedlimiter.utils.VpnConnectionSession
import com.qasim.speedlimiter.utils.NetworkPacketUtils
import com.qasim.speedlimiter.data.services.LocalVpnService
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.BlockingQueue

/**
 * محرك معالجة البيانات واستقبالها من الإنترنت الحقيقي
 * متواجد في حزمة الخدمات (services) ويعمل في خيط منفصل لإدارة الاتصالات وتأخير حزم التحميل (Download)
 */
class TcpSelectorEngine(
    private val selector: Selector,
    private val outputQueue: BlockingQueue<ByteBuffer> // الطابور الموجه لإعادة ضخ الحزم بالنفق
) : Runnable {

    override fun run() {
        Log.d("TcpSelectorEngine", "بدأ خيط معالجة السوكتات والاستقبال الفعلي بالعمل المستقر...")
        
        while (!Thread.interrupted()) {
            try {
                // الانتظار حتى تصبح إحدى القنوات جاهزة للاستقبال أو الاتصال
                if (selector.select() == 0) {
                    Thread.sleep(10)
                    continue
                }

                val selectedKeys = selector.selectedKeys()
                val iterator = selectedKeys.iterator()

                while (iterator.hasNext() && !Thread.interrupted()) {
                    val key = iterator.next()
                    
                    // الإصلاح الجوهري: حذف المفتاح فوراً هنا لضمان عدم تعليق الـ Selector أو تجمد ترافيك المتصفحات
                    iterator.remove()
                    
                    if (key.isValid) {
                        if (key.isConnectable) {
                            handleConnect(key)
                        } else if (key.isReadable) {
                            handleRead(key)
                        }
                    }
                }
            } catch (e: Exception) {
                if (Thread.interrupted()) break
                Log.e("TcpSelectorEngine", "خطأ في حلقة الـ Selector الرئيسي: ${e.message}")
            }
        }
    }

    /**
     * معالجة إتمام الاتصال بنجاح مع السيرفر الخارجي خارج الـ VPN
     */
    private fun handleConnect(key: SelectionKey) {
        val session = key.attachment() as? VpnConnectionSession ?: return
        val channel = key.channel() as SocketChannel
        
        try {
            if (channel.finishConnect()) {
                session.connectionState = 2 // متصل الآن بنجاح
                // تحويل القناة لتصبح جاهزة للقراءة والاستماع فقط
                key.interestOps(SelectionKey.OP_READ)
                Log.d("TcpSelectorEngine", "تم الاتصال بنجاح بالسيرفر الخارجي للجلسة: ${session.sessionKey}")
            }
        } catch (e: IOException) {
            Log.e("TcpSelectorEngine", "فشل إتمام الاتصال بالسيرفر: ${e.message}")
            VpnConnectionSession.closeSession(session)
        }
    }

    /**
     * قراءة بايتات التحميل وتطبيق الخنق بالملي ثانية عبر الـ TokenBucket الخاص بالخدمة
     */
    private fun handleRead(key: SelectionKey) {
        val session = key.attachment() as? VpnConnectionSession ?: return
        val channel = key.channel() as SocketChannel
        
        // حجز بايت بفر مخصص لقراءة الحزمة من الإنترنت الحقيقي
        val buffer = ByteBuffer.allocate(16384)
        
        try {
            val readBytes = channel.read(buffer)
            
            if (readBytes > 0) {
                // 🚀 [التحكم الصارم بالسلايدر] استهلاك بايتات التحميل الحقيقية من الـ TokenBucket الخاص بالخدمة حياً
                LocalVpnService.downloadBucket.consume(readBytes.toLong())
                
                buffer.flip()
                
                // هندسة بناء الحزمة الترويسية الكاملة (20 بايت IP + 20 بايت TCP = 40 بايت ترويسة قياسية)
                val packetBuffer = ByteBuffer.allocate(readBytes + 40)
                
                // 🚀 [الإصلاح المعجزة]: استدعاء كلاس الأدوات لملء الحزمة بالترويسات الحقيقية والـ Checksum ليفهمها الأندرويد ولا يسقطها
                NetworkPacketUtils.buildTcpPacket(packetBuffer, session, buffer, readBytes)
                
                // تحديث أرقام الـ Sequence للمحافظة على تدفق واستقرار الجلسة دون انقطاع التصفح
                session.sendNextSequenceNumber += readBytes
                
                // إرسال البيانات المغلفة بالترويسات السليمة والمحددة السرعة إلى طابور البث فوراً
                outputQueue.put(packetBuffer)
                
            } else if (readBytes < 0) {
                // إشارة إغلاق الاتصال من الطرف الآخر (FIN)
                Log.d("TcpSelectorEngine", "السيرفر قام بإنهاء الجلسة: ${session.sessionKey}")
                VpnConnectionSession.closeSession(session)
            }
        } catch (e: Exception) {
            Log.e("TcpSelectorEngine", "حدث خطأ أثناء قراءة البيانات وخنقها: ${e.message}")
            VpnConnectionSession.closeSession(session)
        }
    }
}
