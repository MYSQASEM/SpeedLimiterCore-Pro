package com.qasim.speedlimiter.utils

import android.util.Log

class TokenBucket(private var capacity: Long, private var refillRatePerSecond: Long) {
    
    private var tokens: Long = capacity
    private var lastRefillTimestamp: Long = System.currentTimeMillis()
    private var isPaused: Boolean = false

    /**
     * الدالة الجوهرية (المطابقة للتابع a في كود المطور):
     * تقوم بفحص الرصيد وخنق الخيط بدقة متناهية بالملي ثانية إذا تجاوزت البيانات السرعة المطلوبة
     */
    @Synchronized
    fun consume(bytesCount: Long) {
        while (true) {
            if (isPaused) {
                try {
                    (this as Object).wait(60000L)
                } catch (e: Exception) {
                    // تم إيقاظ الخيط أو مقاطعته
                }
            } else {
                refillTokens()
                
                if (tokens >= bytesCount) {
                    // الرصيد كافٍ، قم بخصم البايتات والمرور فوراً
                    tokens -= bytesCount
                    break
                } else {
                    // الرصيد غير كافٍ، احسب بدقة كم ملي ثانية نحتاج لامتلاء الخزان
                    val bytesNeeded = bytesCount - tokens
                    val millisecondsToWait = (bytesNeeded * 1000L) / refillRatePerSecond
                    
                    if (millisecondsToWait > 0) {
                        try {
                            // التوقف التزامني الدقيق (السر الذي منع تقطيع يوتيوب وفيسبوك)
                            (this as Object).wait(millisecondsToWait.coerceAtMost(100L))
                        } catch (e: Exception) {
                            // تم تحديث السلايدر وإيقاظ الخيط
                        }
                    } else {
                        // تأخير ضئيل جداً لمنع تجمد المعالج
                        try { Thread.sleep(1) } catch (e: Exception) {}
                    }
                }
            }
        }
    }

    /**
     * إعادة تعبئة الخزان بالتوكنز بناءً على الوقت المنقضي (مطابق للتابع b)
     */
    private fun refillTokens() {
        val currentTime = System.currentTimeMillis()
        val elapsedTime = currentTime - lastRefillTimestamp
        
        if (elapsedTime > 0) {
            val tokensToAdd = (elapsedTime * refillRatePerSecond) / 1000L
            if (tokensToAdd > 0) {
                tokens = (tokens + tokensToAdd).coerceAtMost(capacity)
                lastRefillTimestamp = currentTime
            }
        }
    }

    /**
     * تحديث فوري وديناميكي عند سحب السلايدر (مطابق للتابع c)
     * يقوم بتغيير السرعة وإيقاظ كافة الخيوط المنتظرة فوراً لإعادة الحساب بالسرعة الجديدة
     */
    @Synchronized
    fun updateRate(newCapacity: Long, newRate: Long) {
        this.capacity = newCapacity
        this.refillRatePerSecond = newRate
        if (this.tokens > newCapacity) {
            this.tokens = newCapacity
        }
        Log.d("VpnCore", "تم تحديث محرك التخنيق فوريًا: $newRate Bytes/Sec")
        
        // إيقاظ فوري لجميع الخيوط المخنوقة لتطبيق السرعة الجديدة بدون تهنيج
        (this as Object).notifyAll()
    }

    @Synchronized
    fun pause() {
        isPaused = true
        (this as Object).notifyAll()
    }

    @Synchronized
    fun resume() {
        isPaused = false
        lastRefillTimestamp = System.currentTimeMillis()
        (this as Object).notifyAll()
    }
}
