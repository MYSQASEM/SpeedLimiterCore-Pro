package com.qasim.speedlimiter

object AppConfig {
    // اسم الجلسة والنفق في النظام
    const val VPN_SESSION_NAME = "SpeedLimiterCorePro"
    
    // إعدادات الشبكة والنفق الافتراضية
    const val VPN_ADDRESS = "10.0.0.2"
    const val VPN_ROUTE = "0.0.0.0"
    const val VPN_MTU = 1500
    
    // خوادم الـ DNS لضمان استقرار حزم الـ TCP/UDP ومنع انقطاع المتصفحات
    val DNS_SERVERS = listOf(
        "8.8.8.8", // Google DNS
        "1.1.1.1"  // Cloudflare DNS
    )

    // القائمة السوداء للتطبيقات التي سيتم خنق سرعتها والتحكم بها عبر السلايدر
    val TARGET_APPLICATIONS = listOf(
        "com.android.chrome",             // متصفح جوجل كروم
        "com.google.android.youtube",     // يوتيوب (يعتمد على UDP/QUIC)
        "com.facebook.katana",            // فيسبوك
        "org.zwanoo.android.speedtest"     // تطبيق قياس السرعة Speedtest
    )
    
    // الحدود الدنيا والعليا للسرعة لمنع انهيار المحرك الرياضي (بالـ Kbps)
    const val MIN_SPEED_LIMIT = 100
    const val MAX_SPEED_LIMIT = 30000
}
