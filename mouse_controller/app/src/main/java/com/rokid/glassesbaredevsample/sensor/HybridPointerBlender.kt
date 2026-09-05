package com.rokid.glassesbaredevsample.sensor

/**
 * Solution C gating: hand visible → hand-only fine control; no hand → IMU-only coarse control.
 */
object HybridPointerBlender {
    fun blend(
        imuDx: Float,
        imuDy: Float,
        handDx: Float,
        handDy: Float,
        handOk: Boolean,
    ): Pair<Float, Float> = if (handOk) {
        handDx to handDy
    } else {
        imuDx to imuDy
    }
}
