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
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.google.mlkit.vision.pose.PoseLandmark
import java.util.concurrent.Executors

class DistanceMonitorService : LifecycleService() {

    companion object {
        private const val TAG = "DistanceService"
        private const val CHANNEL_ID = "distance_channel"

        private const val TOO_CLOSE_DISTANCE_CM = 35f
        private const val TOO_CLOSE_CONFIRMATION_MS = 10_000L
        private const val BAD_POSTURE_CONFIRMATION_MS = 8_000L
        private const val ANALYSIS_INTERVAL_MS = 500L
        private const val ACTION_STOP = "ACTION_STOP_DISTANCE_SERVICE"

        private const val DISTANCE_ALERT_ID = 101
        private const val POSTURE_ALERT_ID = 102
    }

    private var lastAnalysisTime = 0L
    private var tooCloseStartTime: Long? = null
    private var distanceAlertShown = false
    private var badPostureStartTime: Long? = null
    private var postureAlertShown = false

    private lateinit var windowManager: WindowManager
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var overlayView: View? = null

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )
    }

    private val poseDetector by lazy {
        val options = AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build()
        PoseDetection.getClient(options)
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(1, createForegroundNotification())
        startCameraAnalysis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startCameraAnalysis() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImage(imageProxy)
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Binding failed", e)
            }
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

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        // Face Detection (Distance)
        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    handleDistanceLogic(faces[0], now)
                }
            }

        // Pose Detection (Posture)
        poseDetector.process(image)
            .addOnSuccessListener { pose ->
                handlePostureLogic(pose, now)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handleDistanceLogic(face: Face, now: Long) {
        val distance = DistanceCalculator.calculateDistance(face)
        if (distance in 15f..TOO_CLOSE_DISTANCE_CM) {
            if (tooCloseStartTime == null) tooCloseStartTime = now
            if (now - tooCloseStartTime!! >= TOO_CLOSE_CONFIRMATION_MS && !distanceAlertShown) {
                distanceAlertShown = true
                Handler(Looper.getMainLooper()).post {
                    showTooCloseOverlay()
                    showTooCloseNotification()
                }
            }
        } else {
            tooCloseStartTime = null
            distanceAlertShown = false
        }
    }

    private fun handlePostureLogic(pose: Pose, now: Long) {
        if (isBadPosture(pose)) {
            if (badPostureStartTime == null) badPostureStartTime = now
            if (now - badPostureStartTime!! >= BAD_POSTURE_CONFIRMATION_MS && !postureAlertShown) {
                postureAlertShown = true
                showPostureNotification()
            }
        } else {
            badPostureStartTime = null
            postureAlertShown = false
        }
    }

    private fun isBadPosture(pose: Pose): Boolean {
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val leftEar = pose.getPoseLandmark(PoseLandmark.LEFT_EAR)
        val rightEar = pose.getPoseLandmark(PoseLandmark.RIGHT_EAR)

        if (leftShoulder == null || rightShoulder == null || leftEar == null || rightEar == null)
            return false

        val shoulderMidY = (leftShoulder.position.y + rightShoulder.position.y) / 2
        val earMidY = (leftEar.position.y + rightEar.position.y) / 2

        // If the head (ears) sinks too close to the shoulder line, they are slouching
        // Threshold: 150 pixels (adjust based on testing)
        return (shoulderMidY - earMidY) < 150f
    }

    private fun showPostureNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Bad Posture Detected")
            .setContentText("Please sit up straight!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(POSTURE_ALERT_ID, notification)
    }

    private fun showTooCloseOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        if (overlayView != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.activity_alert, null)
        view.findViewById<TextView>(R.id.txtAlert).text = "⚠ TOO CLOSE!\nKeep at least 35cm distance"

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 100
        }

        view.findViewById<Button>(R.id.btnDismiss).setOnClickListener { removeOverlay() }
        windowManager.addView(view, params)
        overlayView = view
    }

    private fun removeOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            overlayView = null
        }
    }

    private fun showTooCloseNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Too close to screen")
            .setContentText("Please maintain distance")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(DISTANCE_ALERT_ID, notification)
    }

    private fun createForegroundNotification(): Notification {
        val stopIntent = Intent(this, DistanceMonitorService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vision Guard Active")
            .setContentText("Monitoring Distance & Posture")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .addAction(0, "STOP", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Vision Guard Alerts", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        removeOverlay()
    }
}