package com.qasim.speedlimiter.utils

import android.util.Log

class TokenBucket(private var capacity: Long, private var refillRatePerSecond: Long) {
    
    private var tokens: Long = capacity
    private var lastRefillTimestamp: Long = System.currentTimeMillis()
    private var isPaused: Boolean = false

    /**
     * الدالة الجوهرية لخنق الحزم بناءً على الرصيد المتاح بالملي ثانية
     */
    @Synchronized
    fun consume(bytesCount: Long) {
        while (true) {
            if (isPaused) {
                try {
                    (this as Object).wait(60000L)
                } catch (e: Exception) {
                    // تم إيقاظ الخيط
                }
            } else {
                refillTokens()
                
                if (tokens >= bytesCount) {
                    tokens -= bytesCount
                    break
                } else {
                    // حساب مدة الانتظار المطلوبة بدقة بالملي ثانية
                    val bytesNeeded = bytesCount - tokens
                    val millisecondsToWait = (bytesNeeded * 1000L) / refillRatePerSecond
                    
                    if (millisecondsToWait > 0) {
                        try {
                            (this as Object).wait(millisecondsToWait.coerceAtMost(80L))
                        } catch (e: Exception) {
                            // تم تحديث السرعة أو الاستيقاظ
                        }
                    } else {
                        try { Thread.sleep(1) } catch (e: Exception) {}
                    }
                }
            }
        }
    }

    /**
     * إعادة تعبئة الخزان بالتوكنز بناءً على الوقت المنقضي
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
     * تحديث فوري وديناميكي عند سحب السلايدر وإيقاظ الخيوط المنتظرة فوراً
     */
    @Synchronized
    fun updateRate(newCapacity: Long, newRate: Long) {
        this.capacity = newCapacity
        this.refillRatePerSecond = newRate
        if (this.tokens > newCapacity) {
            this.tokens = newCapacity
        }
        Log.d("TokenBucket", "تم تحديث محرك التخنيق فوريًا: $newRate Bytes/Sec")
        
        // إيقاظ فوري لكافة الخيوط لتطبيق السرعة الجديدة بدون أي تهنيج
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
