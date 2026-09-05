package com.rokid.glassesbaredevsample.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 6 轴 IMU 示例：陀螺仪 + 加速度计原始读数；**头姿球**用纯陀螺四元数积分（无地磁）。
 *
 * 加速度计不参与球位姿，避免静止时被重力修正「拉回中线」；水平仪等页面仍直接用 acc。
 */
class HeadOrientationTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val integrator = GyroQuaternionIntegrator()

    private var refOrientation = Quaternion.IDENTITY
    private var calibrated = false

    private val _readings = MutableStateFlow(SixAxisReading())
    val readings: StateFlow<SixAxisReading> = _readings.asStateFlow()

    private val _pose = MutableStateFlow(HeadPose())
    val pose: StateFlow<HeadPose> = _pose.asStateFlow()

    /** Latest pose for IMU ball / HUD (gyro-axis remapped euler). */
    fun snapshotPose(): HeadPose = _pose.value

    /** Turn-left/right (yaw) and nod-up/down (pitch) for mouse pointer; roll ignored. */
    fun snapshotPointerPose(): HeadPose {
        if (!calibrated) return HeadPose(isCalibrated = false)
        val (yawDeg, pitchDeg) = GlassesAxisRemapper.pointerYawPitchDegrees(
            reference = refOrientation,
            current = integrator.orientation,
        )
        return HeadPose(yawDeg, pitchDeg, isCalibrated = true)
    }

    private val _available = MutableStateFlow(gyro != null && accel != null)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private var running = false

    fun start() {
        if (running) return
        if (gyro == null || accel == null) {
            Log.e(TAG, "6-axis IMU unavailable: gyro=$gyro accel=$accel")
            _available.value = false
            return
        }
        running = true
        val rate = SensorManager.SENSOR_DELAY_GAME
        sensorManager.registerListener(this, gyro, rate)
        sensorManager.registerListener(this, accel, rate)
        Log.i(TAG, "6-axis IMU started (gyro + accelerometer)")
    }

    fun stop() {
        if (!running) return
        sensorManager.unregisterListener(this)
        running = false
    }

    fun calibrate() {
        refOrientation = integrator.orientation
        calibrated = true
        _pose.value = HeadPose(0f, 0f, true)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                _readings.value = _readings.value.copy(
                    gxRad = event.values[0],
                    gyRad = event.values[1],
                    gzRad = event.values[2],
                )
                integrator.onGyroscope(
                    event.values[0], event.values[1], event.values[2], event.timestamp,
                )
                if (calibrated) publishPose()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                _readings.value = _readings.value.copy(
                    ax = event.values[0],
                    ay = event.values[1],
                    az = event.values[2],
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun publishPose() {
        val (pitchRad, rollRad, yawRad) = integrator.relativeEulerSince(refOrientation)
        val (yawDeg, pitchDeg) = GlassesAxisRemapper.mapFilterEulerToBallDegrees(
            deltaPitchRad = pitchRad,
            deltaRollRad = rollRad,
            deltaYawRad = yawRad,
        )
        _pose.value = HeadPose(yawDeg, pitchDeg, true)
    }

    companion object {
        private const val TAG = "HeadOrientationTracker"
    }
}
