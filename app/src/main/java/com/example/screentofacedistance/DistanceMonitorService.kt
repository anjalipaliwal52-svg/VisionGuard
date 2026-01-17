package com.example.screentofacedistance

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

class DistanceMonitorService : LifecycleService() {

    companion object {
        private const val TAG = "DistanceService"
        private const val CHANNEL_ID = "distance_channel"
        private const val TOO_CLOSE_DISTANCE_CM = 35f
        private const val ANALYSIS_INTERVAL_MS = 500L
        private const val TOO_CLOSE_CONFIRMATION_MS = 10_000L
        private const val ACTION_STOP = "ACTION_STOP_DISTANCE_SERVICE"
    }

    private var lastAnalysisTime = 0L
    private var alertShown = false
    private var tooCloseStartTime: Long? = null

    private lateinit var windowManager: WindowManager
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // 🔹 FIX: track overlay properly
    private var overlayView: android.view.View? = null

    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        FaceDetection.getClient(options)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(1, createForegroundNotification())
        startCameraAnalysis()
    }

    private fun startCameraAnalysis() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Camera permission NOT granted")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImage(imageProxy)
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                imageAnalysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalysisTime < ANALYSIS_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastAnalysisTime = now

        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val distance = DistanceCalculator.calculateDistance(faces[0])
                    if (distance < 15 || distance > 100) return@addOnSuccessListener

                    if (distance < TOO_CLOSE_DISTANCE_CM) {
                        if (tooCloseStartTime == null) {
                            tooCloseStartTime = SystemClock.elapsedRealtime()
                        }

                        val duration =
                            SystemClock.elapsedRealtime() - tooCloseStartTime!!

                        if (duration >= TOO_CLOSE_CONFIRMATION_MS && !alertShown) {
                            alertShown = true
                            Handler(Looper.getMainLooper()).post {
                                showTooCloseOverlay()
                                showTooCloseNotification()
                            }
                        }
                    } else {
                        tooCloseStartTime = null
                        alertShown = false
                    }
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun showTooCloseOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) return

        if (overlayView != null) return

        val view = LayoutInflater.from(this)
            .inflate(R.layout.activity_alert, null)

        view.findViewById<TextView>(R.id.txtAlert).text =
            "⚠ TOO CLOSE!\nKeep at least 35cm distance"

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 100
        }

        view.findViewById<Button>(R.id.btnDismiss)
            .setOnClickListener { removeOverlay() }

        windowManager.addView(view, params)
        overlayView = view
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            overlayView = null
        }
    }

    private fun showTooCloseNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Too close to screen")
            .setContentText("Please maintain at least 35cm distance")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(101, notification)
    }

    private fun createForegroundNotification(): Notification {
        val stopIntent = Intent(this, DistanceMonitorService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Eye Guard Protection")
            .setContentText("Monitoring screen distance...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .addAction(0, "STOP", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Distance Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        removeOverlay()
    }
}
