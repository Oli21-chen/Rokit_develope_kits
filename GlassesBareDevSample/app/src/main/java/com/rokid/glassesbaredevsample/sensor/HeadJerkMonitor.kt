package com.rokid.glassesbaredevsample.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Lightweight gyro listener for [HeadJerkGate] — used during hand-mouse scene only.
 */
class HeadJerkMonitor(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gate = HeadJerkGate()
    private var running = false

    fun start() {
        if (running || gyro == null) return
        running = true
        sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!running) return
        sensorManager.unregisterListener(this)
        running = false
        gate.reset()
    }

    fun isPaused(nowMs: Long): Boolean = gate.isPaused(nowMs)

    fun reset() {
        gate.reset()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        val nowMs = System.nanoTime() / 1_000_000L
        gate.onGyroSample(event.values[0], event.values[1], event.values[2], nowMs)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
