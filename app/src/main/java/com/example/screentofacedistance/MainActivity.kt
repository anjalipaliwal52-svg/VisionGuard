package com.example.screentofacedistance

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var txtDistance: TextView
    private lateinit var txtStatus: TextView
    private lateinit var btnStart: Button

    // Receiver to get distance updates from the Service
    private val distanceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val distance = intent?.getFloatExtra("distance_cm", -1f) ?: -1f
            if (distance > 0) {
                txtDistance.text = "Distance: ${distance.toInt()} cm"
                txtStatus.text = "Monitoring Active"
            } else {
                txtStatus.text = "No face detected"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtDistance = findViewById(R.id.txtDistance)
        txtStatus = findViewById(R.id.txtStatus)
        btnStart = findViewById(R.id.btnStart)

        // Register the receiver to listen for "DISTANCE_UPDATE"
        val intentFilter = IntentFilter("DISTANCE_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(distanceReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(distanceReceiver, intentFilter)
        }

        btnStart.setOnClickListener {
            checkPermissionsAndStartService()
        }
    }

    private fun checkPermissionsAndStartService() {
        // 1. Check Overlay Permission (System Alert Window)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Please grant Overlay permission then press Start", Toast.LENGTH_LONG).show()
            return
        }

        // 2. Check Camera and Notification Permissions
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            startMonitorService()
        }
    }

    private fun startMonitorService() {
        val serviceIntent = Intent(this, DistanceMonitorService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        txtStatus.text = "Starting Service..."
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startMonitorService()
            } else {
                txtStatus.text = "Permissions denied. Cannot monitor."
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Prevent memory leaks by unregistering receiver
        try {
            unregisterReceiver(distanceReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }
}