package com.example.screentofacedistance

import android.media.Image
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetectorOptionsBase
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions  // Ensure ML Kit dependency is updated

class PoseDetectorHelper {

    // Configure the detector to stream mode
    private val options = PoseDetectorOptions.Builder()
        .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
        .build()

    private val poseDetector = PoseDetection.getClient(options)

    fun process(
        mediaImage: Image,
        rotation: Int,
        onResult: (Pose?) -> Unit
    ) {
        val image = InputImage.fromMediaImage(mediaImage, rotation)

        poseDetector.process(image)
            .addOnSuccessListener { pose ->
                onResult(pose)
            }
            .addOnFailureListener {
                onResult(null)
            }
    }

    fun close() {
        poseDetector.close()
    }
}
