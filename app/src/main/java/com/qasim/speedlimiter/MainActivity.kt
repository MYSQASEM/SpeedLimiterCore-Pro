package com.qasim.speedlimiter

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var speedLimit = 50 
    private val vpnRequestCode = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // الحصول على المعرفات ديناميكياً لتفادي مشاكل الاستيراد (R) في السيرفر السحابي
        val layoutId = resources.getIdentifier("activity_main", "layout", packageName)
        if (layoutId != 0) {
            setContentView(layoutId)
        }

        val seekBarId = resources.getIdentifier("speedSeekBar", "id", packageName)
        val textSpeedId = resources.getIdentifier("currentSpeedText", "id", packageName)
        val btnVpnId = resources.getIdentifier("btnToggleVpn", "id", packageName)

        val speedSeekBar = if (seekBarId != 0) findViewById<SeekBar>(seekBarId) else null
        val currentSpeedText = if (textSpeedId != 0) findViewById<TextView>(textSpeedId) else null
        val btnToggleVpn = if (btnVpnId != 0) findViewById<Button>(btnVpnId) else null

        speedSeekBar?.max = 100
        speedSeekBar?.progress = speedLimit
        currentSpeedText?.text = "السرعة الحالية: $speedLimit KB/s"

        speedSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                var currentProgress = progress
                if (currentProgress < 1) currentProgress = 1 
                speedLimit = currentProgress
                currentSpeedText?.text = "السرعة الحالية: $speedLimit KB/s"
                
                val intent = Intent(this@MainActivity, com.qasim.speedlimiter.data.services.LocalVpnService::class.java).apply {
                    action = "UPDATE_SPEED"
                    putExtra("SPEED_LIMIT", speedLimit)
                }
                startService(intent)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnToggleVpn?.setOnClickListener {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, vpnRequestCode)
            } else {
                onActivityResult(vpnRequestCode, RESULT_OK, null)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode && resultCode == RESULT_OK) {
            val intent = Intent(this, com.qasim.speedlimiter.data.services.LocalVpnService::class.java).apply {
                action = "START_VPN"
                putExtra("SPEED_LIMIT", speedLimit)
            }
            startService(intent)
            Toast.makeText(this, "تم تفعيل التحكم بالسرعة!", Toast.LENGTH_SHORT).show()
        }
    }
}
