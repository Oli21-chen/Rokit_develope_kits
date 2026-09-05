package com.rokid.glassesbaredevsample.sensor

/** 设备坐标系陀螺仪轴：+X 右、+Y 上、+Z 朝佩戴者（竖屏标准）。 */
enum class GyroAxis(val label: String) {
    X("+X"),
    Y("+Y"),
    Z("+Z"),
    ;

    companion object {
        fun fromIndex(index: Int): GyroAxis = entries[index.coerceIn(0, 2)]
    }
}

enum class ImuMotionKind(val title: String, val instruction: String) {
    YAW("左右转头", "请缓慢向左、再向右转头"),
    PITCH("上下点头", "请缓慢向上、再向下点头"),
    ROLL("左右歪头", "请缓慢向左肩、再向右肩歪头"),
}

data class MotionAxisBinding(
    val motion: ImuMotionKind,
    val axis: GyroAxis,
    /** 该动作正方向时，该轴角速度符号（+1 / -1）。 */
    val sign: Int,
    val peakRadPerSec: Float,
)

data class ImuAxisCalibrationResult(
    val bindings: List<MotionAxisBinding>,
) {
    fun binding(motion: ImuMotionKind): MotionAxisBinding? =
        bindings.firstOrNull { it.motion == motion }

    fun summaryLines(): List<String> = bindings.map { b ->
        val signStr = if (b.sign > 0) "+" else "−"
        "${b.motion.title} → ${b.axis.label} 轴 $signStr"
    }
}
