package com.qasim.speedlimiter

import android.content.Context

object AppConfig {
    const val DEFAULT_SPEED_LIMIT = 80
    const val PREFS_NAME = "SpeedLimiterPrefs"
    const val KEY_SPEED_LIMIT = "speed_limit"
    const val KEY_IS_ENABLED = "is_enabled"
    
    // مفتاح تخزين قائمة حزم التطبيقات المحددة (Packages)
    private const val KEY_TARGET_APPS = "target_applications"

    // إعدادات الـ VPN الافتراضية للثبات والـ Build
    const val VPN_SESSION_NAME = "SpeedLimiterSystem"
    const val VPN_ADDRESS = "10.0.0.1"
    const val VPN_ROUTE = "0.0.0.0"
    const val VPN_MTU = 1500
    val DNS_SERVERS = listOf("8.8.8.8", "1.1.1.1")

    // حدود السرعة للسلايدر
    const val MIN_SPEED_LIMIT = 1 // 1 Kbps
    const val MAX_SPEED_LIMIT = 102400 // 100 Mbps

    /**
     * دالة لحفظ قائمة التطبيقات المحددة إلى الـ SharedPreferences
     */
    fun saveTargetApps(context: Context, appPackages: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_TARGET_APPS, appPackages).apply()
    }

    /**
     * دالة لجلب قائمة التطبيقات المحددة من الـ SharedPreferences
     * إذا كانت القائمة فارغة، يمكنك وضع تطبيقات افتراضية أو تركها فارغة بحسب رغبتك
     */
    fun getTargetApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_TARGET_APPS, emptySet()) ?: emptySet()
    }
}
