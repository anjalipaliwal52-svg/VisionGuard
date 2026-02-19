package com.example.screentofacedistance

import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark

object PostureAnalyzer {

    /**
     * Returns TRUE if posture is BAD
     */
    fun isBadPosture(pose: Pose): Boolean {

        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val leftEar = pose.getPoseLandmark(PoseLandmark.LEFT_EAR)
        val rightEar = pose.getPoseLandmark(PoseLandmark.RIGHT_EAR)

        if (
            leftShoulder == null || rightShoulder == null ||
            leftEar == null || rightEar == null
        ) return false

        // Mid-points
        val shoulderMidY =
            (leftShoulder.position.y + rightShoulder.position.y) / 2

        val earMidY =
            (leftEar.position.y + rightEar.position.y) / 2

        // Head slouch calculation
        val headDrop = earMidY - shoulderMidY

        // Threshold (tweak later if needed)
        return headDrop > 35
    }
}
