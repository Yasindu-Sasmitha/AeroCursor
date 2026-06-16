package com.example.aerocursor

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sign

class GyroToMouseConverter {
    private var calibrationX = 0f
    private var calibrationY = 0f

    private var lastDx = 0f
    private var lastDy = 0f

    private var basePitch = 0f
    private var baseRoll = 0f

    fun calibrate(currentAngularX: Float, currentAngularY: Float) {
        calibrationX = currentAngularX
        calibrationY = currentAngularY
    }

    fun calibrateAccel(ax: Float, ay: Float, az: Float) {
        basePitch = atan2(ay, az)
        baseRoll = atan2(-ax, az)
    }

    fun calculateDelta(
        angularX: Float,
        angularY: Float,
        dt: Float
    ): Pair<Int, Int> {
        val correctedX = angularX - calibrationX
        val correctedY = angularY - calibrationY

        val rawX = if (abs(correctedY) > Constants.DEAD_ZONE) correctedY else 0f
        val rawY = if (abs(correctedX) > Constants.DEAD_ZONE) correctedX else 0f

        // Apply sensitivity curve to RAW sensor data for precision
        val targetDx = applySensitivityCurve(rawX) * Constants.SENSITIVITY * dt
        val targetDy = applySensitivityCurve(rawY) * Constants.SENSITIVITY * dt

        val dx = lastDx + Constants.SMOOTHING_FACTOR * (targetDx - lastDx)
        val dy = lastDy + Constants.SMOOTHING_FACTOR * (targetDy - lastDy)

        lastDx = dx
        lastDy = dy

        return Pair(
            dx.toInt().coerceIn(-127, 127),
            dy.toInt().coerceIn(-127, 127)
        )
    }

    fun calculateDeltaFromAccel(ax: Float, ay: Float, az: Float): Pair<Int, Int> {
        val currentPitch = atan2(ay, az)
        val currentRoll = atan2(-ax, az)

        val rollDelta = currentRoll - baseRoll
        val pitchDelta = currentPitch - basePitch

        val tiltDeadZone = 0.03f 
        val rawX = if (abs(rollDelta) > tiltDeadZone) rollDelta else 0f
        val rawY = if (abs(pitchDelta) > tiltDeadZone) pitchDelta else 0f

        // Tilt Sensitivity (scaled for radians)
        val tiltSensitivity = 300f 
        val targetDx = applySensitivityCurve(rawX) * tiltSensitivity
        val targetDy = applySensitivityCurve(rawY) * tiltSensitivity

        val dx = lastDx + 0.2f * (targetDx - lastDx)
        val dy = lastDy + 0.2f * (targetDy - lastDy)

        lastDx = dx
        lastDy = dy

        return Pair(
            dx.toInt().coerceIn(-127, 127),
            dy.toInt().coerceIn(-127, 127)
        )
    }

    /**
     * Non-linear sensitivity curve: 
     * Small inputs = very small output (precision)
     * Large inputs = large output (speed)
     * Using power of 2.2 for "realistic" slow start.
     */
    private fun applySensitivityCurve(input: Float): Float {
        val power = 2.2f 
        return sign(input) * (abs(input).pow(power))
    }
}