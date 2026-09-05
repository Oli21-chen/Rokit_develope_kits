package com.rokid.glassesbaredevsample.sensor

/**
 * 6 轴 IMU 原始读数（设备坐标系）。
 *
 * 与 **竖屏、屏幕朝向佩戴者** 的标准 Android 手机一致：
 * +X 向右，+Y 向上，+Z 从屏幕指向佩戴者。
 */
data class SixAxisReading(
    val ax: Float = 0f,
    val ay: Float = 0f,
    val az: Float = 0f,
    val gxRad: Float = 0f,
    val gyRad: Float = 0f,
    val gzRad: Float = 0f,
) {
    val gxDeg: Float get() = Math.toDegrees(gxRad.toDouble()).toFloat()
    val gyDeg: Float get() = Math.toDegrees(gyRad.toDouble()).toFloat()
    val gzDeg: Float get() = Math.toDegrees(gzRad.toDouble()).toFloat()
}
