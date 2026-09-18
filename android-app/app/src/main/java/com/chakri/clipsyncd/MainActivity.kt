package com.chakri.clipsyncd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var macIpInput: TextInputEditText
    private lateinit var secretInput: TextInputEditText
    private lateinit var shizukuChip: Chip
    private lateinit var serviceChip: Chip
    private lateinit var discoveryChip: Chip

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { view, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = statusBar.top)
            insets
        }

        macIpInput = findViewById(R.id.macIpInput)
        secretInput = findViewById(R.id.secretInput)
        shizukuChip = findViewById(R.id.shizukuChip)
        serviceChip = findViewById(R.id.serviceChip)
        discoveryChip = findViewById(R.id.discoveryChip)

        macIpInput.setText(Prefs.getMacIp(this).orEmpty())
        secretInput.setText(Prefs.getSecret(this).orEmpty())

        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            Prefs.save(this, macIpInput.text.toString(), secretInput.text.toString())
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.grantShizukuButton).setOnClickListener {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Shizuku service is not running", Toast.LENGTH_SHORT).show()
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Already granted", Toast.LENGTH_SHORT).show()
            } else {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            }
        }

        findViewById<MaterialButton>(R.id.startServiceButton).setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, SyncService::class.java))
            refreshStatus()
        }

        findViewById<MaterialButton>(R.id.stopServiceButton).setOnClickListener {
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
        when {
            !Shizuku.pingBinder() -> setChipStatus(shizukuChip, "not running", Status.BAD)
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                setChipStatus(shizukuChip, "granted", Status.GOOD)
            else -> setChipStatus(shizukuChip, "permission needed", Status.BAD)
        }
        setChipStatus(
            serviceChip,
            if (SyncService.isRunning) "running" else "stopped",
            if (SyncService.isRunning) Status.GOOD else Status.NEUTRAL
        )
        val macHost = SyncService.discoveredMacHost
        setChipStatus(
            discoveryChip,
            macHost ?: "not found",
            if (macHost != null) Status.GOOD else Status.NEUTRAL
        )
    }

    private enum class Status { GOOD, BAD, NEUTRAL }

    private fun setChipStatus(chip: Chip, text: String, status: Status) {
        chip.text = text
        when (status) {
            Status.GOOD -> {
                chip.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                chip.setTextColor(Color.WHITE)
            }
            Status.BAD -> {
                chip.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#C62828"))
                chip.setTextColor(Color.WHITE)
            }
            Status.NEUTRAL -> {
                chip.chipBackgroundColor = ColorStateList.valueOf(
                    MaterialColors.getColor(chip, com.google.android.material.R.attr.colorSurfaceVariant)
                )
                chip.setTextColor(
                    MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
            }
        }
    }

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 1001
    }
}
