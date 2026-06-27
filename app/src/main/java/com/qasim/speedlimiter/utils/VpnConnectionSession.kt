package com.qasim.speedlimiter.utils

import java.io.IOException
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * الكلاس المسؤول عن إدارة جلسة الاتصال لكل تطبيق (مطابق تمامًا لـ g.java في كود المطور)
 * يقوم بربط الـ SocketChannel الحقيقي مع الـ TokenBucket الخاص بالتخنيق
 */
class VpnConnectionSession(
    val sessionKey: String,          // المعرف الفريد للجلسة (مثال: IP:Port المستهدف)
    var sourceSequence: Long,         // رقم التسلسل للحزم القادمة (Sequence Number)
    var acknowledgmentNumber: Long,   // رقم التأكيد (Ack Number)
    val socketChannel: SocketChannel   // قناة السوكيت الحقيقية المتصلة بالإنترنت الفعلي
) {
    var connectionState: Int = 0     // حالة الاتصال (0: إغلاق، 1: جارٍ الاتصال، 2: متصل)
    var selectionKey: SelectionKey? = null
    
    // محرك التخنيق الخاص بالرفع والتحميل لهذه الجلسة
    var downloadBucket: TokenBucket? = null
    var uploadBucket: TokenBucket? = null

    companion object {
        // مخزن جلسات الاتصال النشطة بالوقت الحقيقي (مطابق للـ LinkedHashMap/Map في كود المطور)
        val activeSessions = ConcurrentHashMap<String, VpnConnectionSession>()

        /**
         * إغلاق كافة الاتصالات النشطة فورًا عند إيقاف الـ VPN لمنع تسريب البيانات أو التهنيج
         */
        fun closeAllSessions() {
            val iterator = activeSessions.values.iterator()
            while (iterator.hasNext()) {
                val session = iterator.next()
                try {
                    session.socketChannel.close()
                } catch (e: IOException) {
                    // القناة مغلقة بالفعل أو حدث خطأ أثناء الإغلاق
                }
                iterator.remove()
            }
        }

        /**
         * إغلاق جلسة اتصال محددة عند انتهاء نقل البيانات أو حدوث خطأ (RST/FIN)
         */
        fun closeSession(session: VpnConnectionSession) {
            try {
                session.socketChannel.close()
            } catch (e: IOException) {
                // خطأ عادي أثناء الإغلاق
            }
            activeSessions.remove(session.sessionKey)
        }
    }
}
