package com.chakri.clipsyncd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var macIpInput: EditText
    private lateinit var secretInput: EditText
    private lateinit var shizukuStatus: TextView
    private lateinit var serviceStatus: TextView

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        macIpInput = findViewById(R.id.macIpInput)
        secretInput = findViewById(R.id.secretInput)
        shizukuStatus = findViewById(R.id.accessibilityStatus)
        serviceStatus = findViewById(R.id.serviceStatus)

        macIpInput.setText(Prefs.getMacIp(this).orEmpty())
        secretInput.setText(Prefs.getSecret(this).orEmpty())

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            Prefs.save(this, macIpInput.text.toString(), secretInput.text.toString())
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.openAccessibilityButton).setOnClickListener {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Shizuku service is not running", Toast.LENGTH_SHORT).show()
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Already granted", Toast.LENGTH_SHORT).show()
            } else {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            }
        }

        findViewById<Button>(R.id.startServiceButton).setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, SyncService::class.java))
            refreshStatus()
        }

        findViewById<Button>(R.id.stopServiceButton).setOnClickListener {
            stopService(Intent(this, SyncService::class.java))
            refreshStatus()
        }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        shizukuStatus.text = when {
            !Shizuku.pingBinder() -> "Shizuku: not running"
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "Shizuku: permission granted"
            else -> "Shizuku: permission NOT granted (required)"
        }
        serviceStatus.text = if (SyncService.isRunning) "Service: running" else "Service: stopped"
    }

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 1001
    }
}
