package com.rokid.glassesbaredevsample.hand

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** MediaPipe hand landmark indices used for pointing / fist. */
object HandLandmarkIndex {
    const val WRIST = 0
    const val THUMB_TIP = 4
    const val THUMB_IP = 3
    const val THUMB_MCP = 2
    const val INDEX_MCP = 5
    const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9
    const val MIDDLE_TIP = 12
    const val RING_MCP = 13
    const val RING_TIP = 16
    const val PINKY_MCP = 17
    const val PINKY_TIP = 20
}

object HandPoseMath {
    fun handScale(landmarks: List<HandLandmark>): Float {
        val wrist = landmarks[HandLandmarkIndex.WRIST]
        val middle = landmarks[HandLandmarkIndex.MIDDLE_MCP]
        return hypot(
            (middle.x - wrist.x).toDouble(),
            (middle.y - wrist.y).toDouble(),
        ).toFloat().coerceAtLeast(1e-3f)
    }

    fun midpoint(a: HandLandmark, b: HandLandmark): Pair<Float, Float> {
        return (a.x + b.x) * 0.5f to (a.y + b.y) * 0.5f
    }

    fun normalizedDistance(a: HandLandmark, b: HandLandmark, scale: Float): Float {
        return hypot(
            (a.x - b.x).toDouble(),
            (a.y - b.y).toDouble(),
        ).toFloat() / scale
    }

    /** Aim vector from knuckle midpoint to fingertip midpoint (index + middle). */
    fun pointingVector(landmarks: List<HandLandmark>): Pair<Float, Float> {
        val base = midpoint(
            landmarks[HandLandmarkIndex.INDEX_MCP],
            landmarks[HandLandmarkIndex.MIDDLE_MCP],
        )
        val tip = midpoint(
            landmarks[HandLandmarkIndex.INDEX_TIP],
            landmarks[HandLandmarkIndex.MIDDLE_TIP],
        )
        return tip.first - base.first to tip.second - base.second
    }

    fun pointingAngleRad(landmarks: List<HandLandmark>): Float {
        val (vx, vy) = pointingVector(landmarks)
        if (hypot(vx.toDouble(), vy.toDouble()) < 1e-4) return 0f
        return atan2(vy, vx)
    }

    fun wrapPi(radians: Float): Float {
        var r = radians
        while (r > PI) r -= (2 * PI).toFloat()
        while (r < -PI) r += (2 * PI).toFloat()
        return r
    }

    fun unitVectorFromAngle(radians: Float): Pair<Float, Float> =
        cos(radians) to sin(radians)

    fun indexExtension(landmarks: List<HandLandmark>, scale: Float): Float =
        normalizedDistance(
            landmarks[HandLandmarkIndex.INDEX_TIP],
            landmarks[HandLandmarkIndex.INDEX_MCP],
            scale,
        )

    fun middleExtension(landmarks: List<HandLandmark>, scale: Float): Float =
        normalizedDistance(
            landmarks[HandLandmarkIndex.MIDDLE_TIP],
            landmarks[HandLandmarkIndex.MIDDLE_MCP],
            scale,
        )

    fun fingerCurl(landmarks: List<HandLandmark>, mcp: Int, tip: Int, scale: Float): Float =
        normalizedDistance(landmarks[tip], landmarks[mcp], scale)

    fun palmCenter(landmarks: List<HandLandmark>): Pair<Float, Float> = midpoint(
        landmarks[HandLandmarkIndex.INDEX_MCP],
        landmarks[HandLandmarkIndex.MIDDLE_MCP],
    )

    /** Thumb tip distance from palm center; increases when thumb points out. */
    fun thumbSpread(landmarks: List<HandLandmark>, scale: Float): Float {
        val palm = palmCenter(landmarks)
        val thumb = landmarks[HandLandmarkIndex.THUMB_TIP]
        return hypot(
            (thumb.x - palm.first).toDouble(),
            (thumb.y - palm.second).toDouble(),
        ).toFloat() / scale
    }

    /** Horizontal aim from four fingertip average (display space). */
    fun fingerAimVector(landmarks: List<HandLandmark>): Pair<Float, Float> {
        val base = palmCenter(landmarks)
        val idx = HandLandmarkIndex
        val tips = listOf(
            landmarks[idx.INDEX_TIP],
            landmarks[idx.MIDDLE_TIP],
            landmarks[idx.RING_TIP],
            landmarks[idx.PINKY_TIP],
        )
        val tipX = tips.sumOf { it.x.toDouble() }.toFloat() / tips.size
        val tipY = tips.sumOf { it.y.toDouble() }.toFloat() / tips.size
        return tipX - base.first to tipY - base.second
    }

    fun fingerAimAngleRad(landmarks: List<HandLandmark>): Float {
        val (vx, vy) = fingerAimVector(landmarks)
        if (hypot(vx.toDouble(), vy.toDouble()) < 1e-4) return 0f
        return atan2(vy, vx)
    }

    fun areFingersExtended(landmarks: List<HandLandmark>, scale: Float, minExtension: Float): Boolean {
        val idx = HandLandmarkIndex
        return indexExtension(landmarks, scale) >= minExtension &&
            middleExtension(landmarks, scale) >= minExtension &&
            fingerCurl(landmarks, idx.RING_MCP, idx.RING_TIP, scale) >= minExtension &&
            fingerCurl(landmarks, idx.PINKY_MCP, idx.PINKY_TIP, scale) >= minExtension
    }

    fun isPointingPoseValid(landmarks: List<HandLandmark>, minExtension: Float): Boolean {
        if (landmarks.size < HandSkeleton.LANDMARK_COUNT) return false
        val scale = handScale(landmarks)
        return areFingersExtended(landmarks, scale, minExtension)
    }

    fun degreesToRadians(degrees: Float): Float = degrees * (PI.toFloat() / 180f)
}
