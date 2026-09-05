package com.rokid.glassesbaredevsample.sensor

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 将头姿映射到线框小球：
 * - [deltaYawDeg] → 水平位移（左右转头）
 * - [deltaPitchDeg] → 垂直位移（上下点头）
 *
 * 优先使用轴向验证结果，从相对姿态欧拉增量中选取对应陀螺仪轴。
 */
object GlassesAxisRemapper {

    /** 最近一次轴向验证结果（供界面展示与映射）。 */
    var lastCalibration: ImuAxisCalibrationResult? = null
        private set

    fun applyCalibration(result: ImuAxisCalibrationResult) {
        lastCalibration = result
    }

    /**
     * @param deltaPitchRad 绕 X（gx）积分角增量
     * @param deltaRollRad 绕 Y（gy）积分角增量
     * @param deltaYawRad 绕 Z（gz）积分角增量
     */
    fun mapFilterEulerToBallDegrees(
        deltaPitchRad: Float,
        deltaRollRad: Float,
        deltaYawRad: Float,
    ): Pair<Float, Float> {
        val yawBinding = lastCalibration?.binding(ImuMotionKind.YAW)
        val pitchBinding = lastCalibration?.binding(ImuMotionKind.PITCH)

        val yawAxis = yawBinding?.axis ?: GyroAxis.Z
        val pitchAxis = pitchBinding?.axis ?: GyroAxis.X
        val yawSign = (yawBinding?.sign ?: 1).toFloat()
        val pitchSign = (pitchBinding?.sign ?: 1).toFloat()

        val yawRad = normalizeRadians(componentRad(yawAxis, deltaPitchRad, deltaRollRad, deltaYawRad)) * yawSign
        val pitchRad = normalizeRadians(componentRad(pitchAxis, deltaPitchRad, deltaRollRad, deltaYawRad)) * pitchSign

        return radiansToDegrees(yawRad) to radiansToDegrees(pitchRad)
    }

    private fun componentRad(
        axis: GyroAxis,
        deltaPitch: Float,
        deltaRoll: Float,
        deltaYaw: Float,
    ): Float = when (axis) {
        GyroAxis.X -> deltaPitch
        GyroAxis.Y -> deltaRoll
        GyroAxis.Z -> deltaYaw
    }

    private fun normalizeRadians(rad: Float): Float {
        var a = rad
        val twoPi = (2.0 * PI).toFloat()
        val pi = PI.toFloat()
        while (a > pi) a -= twoPi
        while (a < -pi) a += twoPi
        return a
    }

    /**
     * Pointer mapping: horizontal = turn left/right, vertical = nod up/down.
     * Look-direction extraction ignores head roll (ear-to-shoulder tilt).
     */
    fun pointerYawPitchDegrees(reference: Quaternion, current: Quaternion): Pair<Float, Float> =
        relativeYawPitchDegrees(reference, current)

    fun relativeYawPitchDegrees(reference: Quaternion, current: Quaternion): Pair<Float, Float> {
        val relative = reference.inverse().multiply(current.normalized())
        val (fx, fy, fz) = relative.rotateVector(
            vx = LOOK_FORWARD_X,
            vy = LOOK_FORWARD_Y,
            vz = LOOK_FORWARD_Z,
        )
        val horizontalRad = atan2(fx, fz)
        val verticalRad = atan2(
            -fy,
            sqrt(fx * fx + fz * fz).coerceAtLeast(1e-6f),
        )
        return radiansToDegrees(horizontalRad) to radiansToDegrees(verticalRad)
    }

    private const val LOOK_FORWARD_X = 0f
    private const val LOOK_FORWARD_Y = 0f
    private const val LOOK_FORWARD_Z = 1f
}
