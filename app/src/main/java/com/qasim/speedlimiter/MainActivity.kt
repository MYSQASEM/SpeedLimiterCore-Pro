package com.qasim.speedlimiter

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val vpnRequestCode = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onStartVpn = { startVpnService() },
                        onStopVpn = { stopVpnService() }
                    )
                }
            }
        }
    }

    private fun startVpnService() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, vpnRequestCode)
        } else {
            onActivityResult(vpnRequestCode, RESULT_OK, null)
        }
    }

    private fun stopVpnService() {
        val intent = Intent(this, com.qasim.speedlimiter.data.services.LocalVpnService::class.java)
        intent.action = "STOP"
        startService(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode && resultCode == RESULT_OK) {
            val intent = Intent(this, com.qasim.speedlimiter.data.services.LocalVpnService::class.java)
            intent.action = "START"
            startService(intent)
        }
    }
}

@Composable
fun MainScreen(onStartVpn: () -> Unit, onStopVpn: () -> Unit) {
    var isVpnRunning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Speed Limiter",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Button(
            onClick = {
                if (isVpnRunning) onStopVpn() else onStartVpn()
                isVpnRunning = !isVpnRunning
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text(if (isVpnRunning) "Stop VPN" else "Start VPN")
        }
    }
}