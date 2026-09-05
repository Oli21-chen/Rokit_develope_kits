package com.rokid.glassesbaredevsample.sensor

/**
 * 纯陀螺仪四元数积分（无地磁、无加速度计修正）。
 *
 * 用于头姿球等需「停住即停」的相对姿态；加速度计仅作原始读数/水平仪展示。
 */
class GyroQuaternionIntegrator {
    var orientation: Quaternion = Quaternion.IDENTITY
        private set

    private var lastTimestampNs: Long = 0L

    fun reset() {
        orientation = Quaternion.IDENTITY
        lastTimestampNs = 0L
    }

    fun onGyroscope(gx: Float, gy: Float, gz: Float, timestampNs: Long) {
        if (lastTimestampNs == 0L) {
            lastTimestampNs = timestampNs
            return
        }
        val dt = ((timestampNs - lastTimestampNs) * 1e-9f).coerceIn(0f, 0.1f)
        lastTimestampNs = timestampNs
        if (dt <= 0f) return

        val half = dt * 0.5f
        val delta = Quaternion(
            x = gx * half,
            y = gy * half,
            z = gz * half,
            w = 1f,
        ).normalized()
        orientation = orientation.multiply(delta).normalized()
    }

    /** 相对 [reference] 的姿态欧拉增量（pitch / roll / yaw，弧度）。 */
    fun relativeEulerSince(reference: Quaternion): Triple<Float, Float, Float> {
        val relative = reference.inverse().multiply(orientation).normalized()
        return relative.toEulerRadians()
    }
}
