package com.rokid.glassesbaredevsample.link

import kotlin.math.roundToInt

/** Shared laptop↔glasses pointer gain (applied on laptop when scaling dx/dy). */
object LinkGain {
    const val MIN = 0.01f
    const val MAX = 2.0f
    const val DEFAULT = 0.25f
    const val STEP = 0.05f

    fun clamp(value: Float): Float = value.coerceIn(MIN, MAX)

    fun toMilli(gain: Float): Int = (clamp(gain) * 1000f).roundToInt().coerceIn(10, 2000)

    fun fromMilli(milli: Int): Float = clamp(milli / 1000f)
}
