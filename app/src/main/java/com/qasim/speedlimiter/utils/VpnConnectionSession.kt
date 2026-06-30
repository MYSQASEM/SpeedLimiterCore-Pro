package com.qasim.speedlimiter.utils

import java.io.IOException
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * يمثل جلسة اتصال فرعية مستقلة لقناة الاتصال (SocketChannel)
 * مسؤول عن تتبع حالة كل اتصال وتطبيق خنق السرعة الخاص به
 */
class VpnConnectionSession {
    var sessionKey: String = ""
    var channel: SocketChannel? = null
    var selectionKey: SelectionKey? = null
    var connectionState: Int = 0 // 0: مغلق، 1: جاري الاتصال، 2: متصل
    
    // ⬇️ [الإصلاح الجوهري] إضافة المتغيرات الرياضية وعناوين الـ IP المفقودة لتأمين بناء الحزم ونجاح الـ Build
    var localAddressIp: Int = 0
    var remoteAddressIp: Int = 0
    var localPort: Int = 0
    var remotePort: Int = 0
    
    // أرقام الـ Sequence الـخاصة ببروتوكول TCP للمحافظة على تزامن التصفح
    var sendNextSequenceNumber: Int = 0
    var receiveNextSequenceNumber: Int = 0
    
    // ربط الجلسة بمحرك السرعة الخاص بها
    var downloadBucket: TokenBucket? = null

    companion object {
        // قاموس للاحتفاظ بجميع الجلسات النشطة في الذاكرة
        private val activeSessions = ConcurrentHashMap<String, VpnConnectionSession>()

        fun addSession(key: String, session: VpnConnectionSession) {
            activeSessions[key] = session
        }

        fun getSession(key: String): VpnConnectionSession? {
            return activeSessions[key]
        }

        fun removeSession(key: String): VpnConnectionSession? {
            return activeSessions.remove(key)
        }

        /**
         * إغلاق جلسة معينة وتحرير قنواتها بأمان
         */
        fun closeSession(session: VpnConnectionSession) {
            session.connectionState = 0
            try {
                session.selectionKey?.cancel()
                session.channel?.close()
            } catch (e: IOException) {
                // تجاهل الخطأ عند الإغلاق
            }
            activeSessions.remove(session.sessionKey)
        }

        /**
         * تنظيف وإغلاق كافة الجلسات عند إيقاف الـ VPN
         */
        fun closeAllSessions() {
            val iterator = activeSessions.values.iterator()
            while (iterator.hasNext()) {
                val session = iterator.next()
                try {
                    session.selectionKey?.cancel()
                    session.channel?.close()
                } catch (e: IOException) {}
                iterator.remove()
            }
        }
    }
}
