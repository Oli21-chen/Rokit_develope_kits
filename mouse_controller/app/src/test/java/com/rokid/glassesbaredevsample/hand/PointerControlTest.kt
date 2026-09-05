package com.rokid.glassesbaredevsample.hand

import com.rokid.glassesbaredevsample.sensor.HeadJerkGate
import com.rokid.glassesbaredevsample.sensor.HeadPose
import com.rokid.glassesbaredevsample.sensor.HybridPointerBlender
import com.rokid.glassesbaredevsample.sensor.ImuPointerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureClassifierTest {
    private fun openHand(scale: Float = 0.2f): List<HandLandmark> {
        // Wrist at origin-ish; middle MCP defines scale; tips far apart (not pinched).
        val landmarks = MutableList(21) { HandLandmark(0.5f, 0.5f, 0f) }
        landmarks[0] = HandLandmark(0.5f, 0.8f, 0f) // wrist
        landmarks[9] = HandLandmark(0.5f, 0.8f - scale, 0f) // middle MCP
        landmarks[4] = HandLandmark(0.35f, 0.5f, 0f) // thumb tip
        landmarks[8] = HandLandmark(0.65f, 0.5f, 0f) // index tip
        landmarks[5] = HandLandmark(0.45f, 0.65f, 0f)
        landmarks[13] = HandLandmark(0.55f, 0.65f, 0f)
        landmarks[17] = HandLandmark(0.60f, 0.65f, 0f)
        return landmarks
    }

    private fun pinchedHand(scale: Float = 0.2f): List<HandLandmark> {
        val landmarks = openHand(scale).toMutableList()
        landmarks[4] = HandLandmark(0.49f, 0.50f, 0f)
        landmarks[8] = HandLandmark(0.51f, 0.50f, 0f)
        return landmarks
    }

    @Test
    fun requiresHoldBeforePinchAndReleasesWithHysteresis() {
        val config = HandMouseConfig(
            pinchOnNormalized = 0.4f,
            pinchOffNormalized = 0.55f,
            pinchMinHoldMs = 50L,
            pinchCooldownMs = 100L,
        )
        val classifier = GestureClassifier(config)

        assertFalse(classifier.update(pinchedHand(), nowMs = 0L, handPresent = true))
        assertFalse(classifier.update(pinchedHand(), nowMs = 40L, handPresent = true))
        assertTrue(classifier.update(pinchedHand(), nowMs = 60L, handPresent = true))
        assertTrue(classifier.update(pinchedHand(), nowMs = 80L, handPresent = true))

        // Between on/off thresholds: stay pressed.
        val mid = openHand().toMutableList()
        mid[4] = HandLandmark(0.47f, 0.5f, 0f)
        mid[8] = HandLandmark(0.53f, 0.5f, 0f)
        // distance 0.06 / scale 0.2 = 0.3 — wait that's more pinched. Need larger gap.
        mid[4] = HandLandmark(0.45f, 0.5f, 0f)
        mid[8] = HandLandmark(0.55f, 0.5f, 0f) // 0.10/0.2 = 0.5, between 0.4 and 0.55
        assertTrue(classifier.update(mid, nowMs = 100L, handPresent = true))

        assertFalse(classifier.update(openHand(), nowMs = 120L, handPresent = true))
    }

    @Test
    fun handLossReleasesImmediately() {
        val classifier = GestureClassifier(
            HandMouseConfig(pinchMinHoldMs = 0L, pinchCooldownMs = 0L),
        )
        assertTrue(classifier.update(pinchedHand(), nowMs = 0L, handPresent = true))
        assertFalse(classifier.update(emptyList(), nowMs = 10L, handPresent = false))
        assertFalse(classifier.isPinched())
    }

    @Test
    fun cooldownBlocksImmediateRePinch() {
        val classifier = GestureClassifier(
            HandMouseConfig(
                pinchOnNormalized = 0.4f,
                pinchOffNormalized = 0.55f,
                pinchMinHoldMs = 0L,
                pinchCooldownMs = 200L,
            ),
        )
        assertTrue(classifier.update(pinchedHand(), nowMs = 0L, handPresent = true))
        assertFalse(classifier.update(openHand(), nowMs = 10L, handPresent = true))
        assertFalse(classifier.update(pinchedHand(), nowMs = 50L, handPresent = true))
        assertTrue(classifier.update(pinchedHand(), nowMs = 220L, handPresent = true))
    }
}

class PointerMapperTest {
    private fun handAt(x: Float, y: Float): List<HandLandmark> {
        val landmarks = MutableList(21) { HandLandmark(x, y, 0f) }
        landmarks[0] = HandLandmark(x, y + 0.15f, 0f)
        landmarks[5] = HandLandmark(x - 0.05f, y, 0f)
        landmarks[9] = HandLandmark(x, y - 0.05f, 0f)
        landmarks[13] = HandLandmark(x + 0.05f, y, 0f)
        landmarks[17] = HandLandmark(x + 0.08f, y, 0f)
        landmarks[4] = HandLandmark(x - 0.12f, y - 0.05f, 0f)
        landmarks[8] = HandLandmark(x + 0.12f, y - 0.05f, 0f)
        return landmarks
    }

    @Test
    fun clutchOffProducesNoMotionOrClick() {
        val mapper = PointerMapper(HandMouseConfig.SolutionA)
        val cmd = mapper.update(handAt(0.5f, 0.5f), handPresent = true, nowMs = 0L)
        assertFalse(cmd.outputEnabled)
        assertEquals(0f, cmd.dx, 0.001f)
        assertEquals(0f, cmd.dy, 0.001f)
        assertFalse(cmd.leftPressed)
        assertEquals(PointerGesture.IDLE, cmd.gesture)
    }

    @Test
    fun relativeMotionUsesFullRawDeltaNotSmoothedAttenuation() {
        val mapper = PointerMapper(
            HandMouseConfig.LegacyTranslation.copy(
                moveDeadzone = 0f,
                positionSmoothing = 0.2f,
                sensitivity = 1000f,
                accelerationExponent = 1f,
                maxDeltaPerFrame = 500f,
            ),
        )
        mapper.setOutputEnabled(true)
        mapper.update(handAt(0.40f, 0.50f), handPresent = true, nowMs = 0L)
        val moved = mapper.update(handAt(0.45f, 0.50f), handPresent = true, nowMs = 16L)
        assertEquals(50f, moved.dx, 0.5f)
    }

    @Test
    fun relativeMotionWhenClutchEnabled() {
        val mapper = PointerMapper(
            HandMouseConfig.LegacyTranslation.copy(
                moveDeadzone = 0.001f,
                positionSmoothing = 1f,
                sensitivity = 1000f,
                accelerationExponent = 1f,
                maxDeltaPerFrame = 200f,
            ),
        )
        mapper.setOutputEnabled(true)
        mapper.update(handAt(0.40f, 0.50f), handPresent = true, nowMs = 0L) // anchor
        val moved = mapper.update(handAt(0.45f, 0.50f), handPresent = true, nowMs = 16L)
        assertTrue(moved.dx > 0f)
        assertEquals(0f, moved.dy, 0.5f)
        assertEquals(PointerGesture.TRACKING, moved.gesture)
    }

    @Test
    fun handLossFreezesAndReleases() {
        val mapper = PointerMapper(
            HandMouseConfig.LegacyTranslation.copy(
                pinchMinHoldMs = 0L,
                pinchCooldownMs = 0L,
            ),
        )
        mapper.setOutputEnabled(true)
        mapper.update(handAt(0.5f, 0.5f), handPresent = true, nowMs = 0L)
        val lost = mapper.update(emptyList(), handPresent = false, nowMs = 20L)
        assertFalse(lost.handOk)
        assertEquals(0f, lost.dx, 0.001f)
        assertFalse(lost.leftPressed)
        assertEquals(PointerGesture.LOST, lost.gesture)
    }
}

class PointingFistTest {
    private fun pointingHand(
        angleRad: Float,
        extension: Float = 0.22f,
        scale: Float = 0.2f,
        thumbSpreadOffset: Float = 0f,
    ): List<HandLandmark> {
        val cx = 0.5f
        val cy = 0.55f
        val landmarks = MutableList(21) { HandLandmark(cx, cy, 0f) }
        landmarks[0] = HandLandmark(cx, cy + scale, 0f)
        landmarks[5] = HandLandmark(cx - 0.04f, cy, 0f)
        landmarks[9] = HandLandmark(cx + 0.04f, cy, 0f)
        landmarks[13] = HandLandmark(cx + 0.08f, cy, 0f)
        landmarks[17] = HandLandmark(cx + 0.12f, cy, 0f)
        val tipX = cx + kotlin.math.cos(angleRad) * extension
        val tipY = cy + kotlin.math.sin(angleRad) * extension
        landmarks[8] = HandLandmark(tipX - 0.01f, tipY, 0f)
        landmarks[12] = HandLandmark(tipX + 0.01f, tipY, 0f)
        landmarks[16] = HandLandmark(tipX + 0.05f, tipY, 0f)
        landmarks[20] = HandLandmark(tipX + 0.09f, tipY, 0f)
        val baseSpread = 0.14f + thumbSpreadOffset
        landmarks[3] = HandLandmark(cx - baseSpread * 0.65f, cy + 0.02f, 0f)
        landmarks[4] = HandLandmark(cx - baseSpread, cy - 0.02f, 0f)
        return landmarks
    }

    private fun fistHand(scale: Float = 0.2f): List<HandLandmark> {
        val cx = 0.5f
        val cy = 0.55f
        val landmarks = MutableList(21) { HandLandmark(cx, cy, 0f) }
        landmarks[0] = HandLandmark(cx, cy + scale, 0f)
        landmarks[5] = HandLandmark(cx - 0.04f, cy, 0f)
        landmarks[8] = HandLandmark(cx - 0.03f, cy + 0.01f, 0f)
        landmarks[9] = HandLandmark(cx + 0.04f, cy, 0f)
        landmarks[12] = HandLandmark(cx + 0.03f, cy + 0.01f, 0f)
        landmarks[13] = HandLandmark(cx + 0.08f, cy, 0f)
        landmarks[16] = HandLandmark(cx + 0.07f, cy + 0.01f, 0f)
        landmarks[17] = HandLandmark(cx + 0.12f, cy, 0f)
        landmarks[20] = HandLandmark(cx + 0.11f, cy + 0.01f, 0f)
        landmarks[3] = HandLandmark(cx - 0.08f, cy, 0f)
        landmarks[4] = HandLandmark(cx - 0.06f, cy + 0.01f, 0f)
        return landmarks
    }

    @Test
    fun neutralPointingProducesNoMotionAfterCalibration() {
        val config = HandMouseConfig(
            controlScheme = ControlScheme.POINTING_FIST,
            pointingDeadzoneDeg = 15f,
            pointingMaxOffsetDeg = 40f,
            pointingSensitivity = 100f,
            pointingSmoothing = 1f,
            fistMinHoldMs = 0L,
            fistCooldownMs = 0L,
        )
        val mapper = PointerMapper(config)
        mapper.setOutputEnabled(true)
        val neutral = pointingHand(angleRad = 0f)
        mapper.update(neutral, handPresent = true, nowMs = 0L)
        val steady = mapper.update(neutral, handPresent = true, nowMs = 16L)
        assertEquals(0f, steady.dx, 0.001f)
        assertEquals(0f, steady.dy, 0.001f)
        assertTrue(steady.poseValid)
    }

    @Test
    fun angledPointingProducesHorizontalMotion() {
        val config = HandMouseConfig(
            controlScheme = ControlScheme.POINTING_FIST,
            pointingDeadzoneDeg = 5f,
            pointingMaxOffsetDeg = 45f,
            pointingSensitivity = 120f,
            pointingSmoothing = 1f,
            fistMinHoldMs = 0L,
            fistCooldownMs = 0L,
        )
        val mapper = PointerMapper(config)
        mapper.setOutputEnabled(true)
        mapper.update(pointingHand(angleRad = 0f), handPresent = true, nowMs = 0L)
        val aimed = mapper.update(
            pointingHand(angleRad = 0.8f),
            handPresent = true,
            nowMs = 16L,
        )
        assertTrue(kotlin.math.abs(aimed.dx) > 0f)
        assertEquals(0f, aimed.dy, 0.001f)
        assertFalse(aimed.leftPressed)
        assertEquals(PointerGesture.TRACKING, aimed.gesture)
    }

    @Test
    fun thumbOutMovesUpThumbTuckedMovesDown() {
        val config = HandMouseConfig(
            controlScheme = ControlScheme.POINTING_FIST,
            pointingSensitivity = 120f,
            pointingSmoothing = 1f,
            thumbSmoothing = 1f,
            thumbVerticalDeadzone = 0.03f,
            thumbVerticalMaxDelta = 0.10f,
            fistMinHoldMs = 0L,
            fistCooldownMs = 0L,
        )
        val mapper = PointerMapper(config)
        mapper.setOutputEnabled(true)
        mapper.update(pointingHand(angleRad = 0f), handPresent = true, nowMs = 0L)
        val up = mapper.update(
            pointingHand(angleRad = 0f, thumbSpreadOffset = 0.08f),
            handPresent = true,
            nowMs = 16L,
        )
        assertTrue(up.dy < 0f)
        assertEquals(0f, up.dx, 0.001f)

        mapper.setOutputEnabled(true)
        mapper.update(pointingHand(angleRad = 0f), handPresent = true, nowMs = 32L)
        val down = mapper.update(
            pointingHand(angleRad = 0f, thumbSpreadOffset = -0.08f),
            handPresent = true,
            nowMs = 48L,
        )
        assertTrue(down.dy > 0f)
        assertEquals(0f, down.dx, 0.001f)
    }

    @Test
    fun thumbOutWithFingerAngleProducesDiagonal() {
        val config = HandMouseConfig(
            controlScheme = ControlScheme.POINTING_FIST,
            pointingDeadzoneDeg = 5f,
            pointingMaxOffsetDeg = 45f,
            pointingSensitivity = 120f,
            pointingSmoothing = 1f,
            thumbSmoothing = 1f,
            thumbVerticalDeadzone = 0.03f,
            thumbVerticalMaxDelta = 0.10f,
            fistMinHoldMs = 0L,
            fistCooldownMs = 0L,
        )
        val mapper = PointerMapper(config)
        mapper.setOutputEnabled(true)
        mapper.update(pointingHand(angleRad = 0f), handPresent = true, nowMs = 0L)
        val diag = mapper.update(
            pointingHand(angleRad = 0.8f, thumbSpreadOffset = 0.08f),
            handPresent = true,
            nowMs = 16L,
        )
        assertTrue(diag.dx != 0f)
        assertTrue(diag.dy < 0f)
    }

    @Test
    fun thumbDownWithExtendedFingersIsNotFist() {
        val hand = pointingHand(angleRad = 0f, thumbSpreadOffset = -0.08f)
        assertTrue(HandPoseMath.isPointingPoseValid(hand, minExtension = 0.55f))
        assertFalse(HandPoseMath.isPointingPoseValid(fistHand(), minExtension = 0.55f))
    }

    @Test
    fun fistSuppressesMotionAndClicks() {
        val config = HandMouseConfig(
            controlScheme = ControlScheme.POINTING_FIST,
            pointingDeadzoneDeg = 5f,
            fistMinHoldMs = 0L,
            fistCooldownMs = 0L,
        )
        val mapper = PointerMapper(config)
        mapper.setOutputEnabled(true)
        mapper.update(pointingHand(0f), handPresent = true, nowMs = 0L)
        val fist = mapper.update(fistHand(), handPresent = true, nowMs = 16L)
        assertTrue(fist.leftPressed)
        assertEquals(PointerGesture.FIST, fist.gesture)
        assertEquals(0f, fist.dx, 0.001f)
        assertEquals(0f, fist.dy, 0.001f)
    }

    @Test
    fun handPoseMathDetectsValidPointing() {
        val hand = pointingHand(0f)
        assertTrue(HandPoseMath.isPointingPoseValid(hand, minExtension = 0.55f))
        assertFalse(HandPoseMath.isPointingPoseValid(fistHand(), minExtension = 0.55f))
    }
}

class SolutionATrackpadTest {
    private fun handAt(x: Float, y: Float): List<HandLandmark> {
        val landmarks = MutableList(21) { HandLandmark(x, y, 0f) }
        landmarks[0] = HandLandmark(x, y + 0.15f, 0f)
        landmarks[5] = HandLandmark(x - 0.05f, y, 0f)
        landmarks[9] = HandLandmark(x, y - 0.05f, 0f)
        landmarks[13] = HandLandmark(x + 0.05f, y, 0f)
        landmarks[17] = HandLandmark(x + 0.08f, y, 0f)
        return landmarks
    }

    @Test
    fun touchGatedTrackpadNeverClicksFromHand() {
        val mapper = PointerMapper(HandMouseConfig.SolutionA.copy(moveDeadzone = 0f, sensitivity = 1000f))
        mapper.setOutputEnabled(true)
        mapper.update(handAt(0.4f, 0.5f), handPresent = true, nowMs = 0L)
        val moved = mapper.update(handAt(0.45f, 0.5f), handPresent = true, nowMs = 16L)
        assertFalse(moved.leftPressed)
        assertTrue(moved.dx > 0f)
        assertEquals(PointerGesture.TRACKING, moved.gesture)
    }

    @Test
    fun motionPausedByHeadJerkGate() {
        val mapper = PointerMapper(HandMouseConfig.SolutionA.copy(moveDeadzone = 0f, sensitivity = 1000f))
        mapper.setOutputEnabled(true)
        mapper.update(handAt(0.4f, 0.5f), handPresent = true, nowMs = 0L)
        val paused = mapper.update(
            handAt(0.45f, 0.5f),
            handPresent = true,
            nowMs = 16L,
            motionPaused = true,
        )
        assertEquals(0f, paused.dx, 0.001f)
        assertEquals(PointerGesture.PAUSED, paused.gesture)
    }

    @Test
    fun recenterRequiresNewAnchorFrame() {
        val mapper = PointerMapper(HandMouseConfig.SolutionA.copy(moveDeadzone = 0f, sensitivity = 1000f))
        mapper.setOutputEnabled(true)
        mapper.update(handAt(0.4f, 0.5f), handPresent = true, nowMs = 0L)
        mapper.update(handAt(0.45f, 0.5f), handPresent = true, nowMs = 16L)
        mapper.recenterAnchor()
        val after = mapper.update(handAt(0.50f, 0.5f), handPresent = true, nowMs = 32L)
        assertEquals(0f, after.dx, 0.001f)
    }
}

class SolutionBImuPointerTest {
    private fun testConfig() = HandMouseConfig.SolutionB.copy(
        imuDeadzoneDeg = 0f,
        imuMaxTiltDeg = 20f,
        imuSensitivity = 10f,
        accelerationExponent = 1f,
        imuFlipX = false,
        imuFlipY = false,
    )

    @Test
    fun tiltFromNeutralProducesHorizontalDelta() {
        val controller = ImuPointerController(testConfig())
        val (dx, _) = controller.update(HeadPose(5f, 0f, isCalibrated = true))
        assertTrue(dx > 0f)
    }

    @Test
    fun heldTiltProducesSteadyMotionEachTick() {
        val controller = ImuPointerController(testConfig())
        val first = controller.update(HeadPose(5f, 0f, isCalibrated = true))
        val second = controller.update(HeadPose(5f, 0f, isCalibrated = true))
        assertEquals(first.first, second.first, 0.001f)
        assertTrue(first.first > 0f)
    }

    @Test
    fun neutralPoseProducesZeroMotion() {
        val controller = ImuPointerController(testConfig())
        val (dx, dy) = controller.update(HeadPose(0f, 0f, isCalibrated = true))
        assertEquals(0f, dx, 0.001f)
        assertEquals(0f, dy, 0.001f)
    }
}

class HybridPointerBlenderTest {
    @Test
    fun usesHandOnlyWhenHandOk() {
        val (dx, dy) = HybridPointerBlender.blend(
            imuDx = 10f,
            imuDy = 20f,
            handDx = 5f,
            handDy = 3f,
            handOk = true,
        )
        assertEquals(5f, dx, 0.001f)
        assertEquals(3f, dy, 0.001f)
    }

    @Test
    fun usesImuOnlyWhenHandLost() {
        val (dx, dy) = HybridPointerBlender.blend(
            imuDx = 10f,
            imuDy = 20f,
            handDx = 5f,
            handDy = 0f,
            handOk = false,
        )
        assertEquals(10f, dx, 0.001f)
        assertEquals(20f, dy, 0.001f)
    }
}

class HeadJerkGateTest {
    @Test
    fun largeGyroSpikePausesOutput() {
        val gate = HeadJerkGate(jerkThresholdRadPerSec = 1f, pauseMs = 300L)
        gate.onGyroSample(2f, 0f, 0f, nowMs = 100L)
        assertTrue(gate.isPaused(150L))
        assertFalse(gate.isPaused(500L))
    }
}
