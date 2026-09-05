package com.rokid.glassesbaredevsample.sensor

/**
 * 眼镜端 IMU 与竖屏手机「屏幕朝用户」时的 [Android 设备坐标系](https://developer.android.com/reference/android/hardware/SensorEvent#values) 一致。
 *
 * 静止平放、屏幕朝上时：az ≈ +9.8 m/s²；竖持面向用户时：az 分量体现「朝向佩戴者」的 +Z。
 */
object PhonePortraitAxes {
    const val AXIS_X_LABEL = "+X 右"
    const val AXIS_Y_LABEL = "+Y 上"
    const val AXIS_Z_LABEL = "+Z 朝佩戴者"

    fun formatAccelLine(r: SixAxisReading): String =
        "acc  X ${fmt(r.ax)}  Y ${fmt(r.ay)}  Z ${fmt(r.az)} m/s²"

    fun formatGyroLine(r: SixAxisReading): String =
        "gyro X ${fmt(r.gxDeg)}°  Y ${fmt(r.gyDeg)}°  Z ${fmt(r.gzDeg)}°/s"

    fun expectedAxisLines(): List<String> = listOf(
        "左右转头 → +Z",
        "上下点头 → +X",
        "左右歪头 → +Y",
    )

    fun remapperTuningLines(): List<String> {
        val cal = GlassesAxisRemapper.lastCalibration ?: return listOf("水平=转头轴 · 垂直=点头轴（默认 Z / X）")
        val yaw = cal.binding(ImuMotionKind.YAW)
        val pitch = cal.binding(ImuMotionKind.PITCH)
        return listOf(
            "水平(左右): ${yaw?.motion?.title} → ${yaw?.axis?.label}",
            "垂直(上下): ${pitch?.motion?.title} → ${pitch?.axis?.label}",
        )
    }

    private fun fmt(v: Float): String = "%+.2f".format(v)
}
