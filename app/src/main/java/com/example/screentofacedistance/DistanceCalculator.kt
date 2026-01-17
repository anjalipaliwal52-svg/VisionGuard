package com.example.screentofacedistance

import com.google.mlkit.vision.face.Face

object DistanceCalculator {
    private const val AVERAGE_FACE_WIDTH_CM = 16f
    private const val FOCAL_LENGTH_PX = 600f // Common for most front cameras

    fun calculateDistance(face: Face): Float {
        val faceWidthPx = face.boundingBox.width().toFloat()
        if (faceWidthPx <= 0) return -1f

        // Formula: (RealWidth * FocalLength) / PixelWidth
        return (AVERAGE_FACE_WIDTH_CM * FOCAL_LENGTH_PX) / faceWidthPx
    }
}