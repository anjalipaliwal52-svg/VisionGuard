package com.example.screentofacedistance

import com.google.mlkit.vision.face.Face

object DistanceCalculator {
    private const val AVERAGE_FACE_WIDTH_CM = 16f
    private const val FOCAL_LENGTH_PX = 600f

    fun calculateDistance(face: Face): Float {
        val faceWidthPx = face.boundingBox.width().toFloat()
        if (faceWidthPx <= 0f) return -1f

        // Distance formula based on camera focal length and face width
        return (AVERAGE_FACE_WIDTH_CM * FOCAL_LENGTH_PX) / faceWidthPx
    }
}