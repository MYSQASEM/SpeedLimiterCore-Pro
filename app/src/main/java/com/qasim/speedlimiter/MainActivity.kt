package com.qasim.speedlimiter

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qasim.speedlimiter.data.services.LocalVpnService

class MainActivity : ComponentActivity() {

    private val VPN_REQUEST_CODE = 24

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)

        setContent {
            // الألوان الاحترافية المودرن بصيغة كوتلن الصحيحة والدقيقة
            val backgroundColor = Color(0xFF111625) // خلفية داكنة مريحة للعين
            val cardColor = Color(0xFF1E293B)       // لون البطاقات الداخلية
            val primaryPurple = Color(0xFF6366F1)   // اللون البنفسجي الأساسي للأزرار
            val successGreen = Color(0xFF10B981)    // اللون الأخضر لحالة النشاط

            // الحالات الديناميكية للواجهة
            var isVpnEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("is_enabled", false)) }
            var speedLimit by remember { mutableStateOf(sharedPrefs.getInt("speed_limit", 1024).toFloat()) }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = backgroundColor
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // 1. العنوان العلوي (Header)
                    Text(
                        text = "Speed Limiter Pro",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp)
                    )

                    // 2. عداد وحالة الاتصال الدائري (Speedometer & Status Display)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(cardColor)
                            .padding(32.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(160.dp)
                                .clip(CircleShape)
                                .background(if (isVpnEnabled) successGreen.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val speedDisplay = if (speedLimit >= 1024) "${String.format("%.1f", speedLimit / 1024f)} Mbps" else "${speedLimit.toInt()} Kbps"
                                Text(
                                    text = speedDisplay,
                                    color = if (isVpnEnabled) successGreen else Color.White,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = if (isVpnEnabled) "مُحدد السرعة نشط" else "الخدمة متوقفة",
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }

                    // 3. شريط التحكم بالسرعة (Slider Control)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(cardColor)
                            .padding(24.dp)
                    ) {
                        Text(
                            text = "اسحب لتحديد سقف السرعة الإجمالية:",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))

                        Slider(
                            value = speedLimit,
                            onValueChange = { newValue ->
                                speedLimit = newValue
                                // حفظ السرعة مباشرة أثناء السحب
                                sharedPrefs.edit().putInt("speed_limit", newValue.toInt()).apply()
                            },
                            valueRange = 512f..10240f, // النطاق من نصف ميجا إلى 10 ميجا
                            steps = 19,
                            colors = SliderDefaults.colors(
                                thumbColor = primaryPurple,
                                activeTrackColor = primaryPurple,
                                inactiveTrackColor = Color.Gray.copy(alpha = 0.3f)
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "512 Kbps", color = Color.Gray, fontSize = 12.sp)
                            Text(text = "10 Mbps", color = Color.Gray, fontSize = 12.sp)
                        }
                    }

                    // 4. زر التشغيل والإيقاف الكبير
                    Button(
                        onClick = {
                            if (isVpnEnabled) {
                                // إيقاف الـ VPN
                                val intent = Intent(this@MainActivity, LocalVpnService::class.java).apply { action = "STOP" }
                                startService(intent)
                                isVpnEnabled = false
                                sharedPrefs.edit().putBoolean("is_enabled", false).apply()
                            } else {
                                // تشغيل الـ VPN بعد فحص الصلاحية
                                val vpnIntent = VpnService.prepare(this@MainActivity)
                                if (vpnIntent != null) {
                                    startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
                                } else {
                                    onActivityResult(VPN_REQUEST_CODE, ComponentActivity.RESULT_OK, null)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isVpnEnabled) Color(0xFFEF4444) else primaryPurple
                        ),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = if (isVpnEnabled) "إيقاف محدد السرعة" else "تشغيل وتحديد السرعة الآن",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == ComponentActivity.RESULT_OK) {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            sharedPrefs.edit().putBoolean("is_enabled", true).apply()
            
            val intent = Intent(this, LocalVpnService::class.java).apply { action = "START" }
            startService(intent)
            
            recreate()
        }
    }
}
