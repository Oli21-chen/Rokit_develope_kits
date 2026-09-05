package com.rokid.glassesbaredevsample.hand

enum class ControlScheme {
    /** Palm translation + thumb–index pinch (Stage 3/4 legacy). */
    TRANSLATION_PINCH,
    /** Static aim vector + fist click (Stage 6). */
    POINTING_FIST,
    /** Solution A: palm translation for motion; TouchPad for click/scroll (no hand click). */
    TOUCH_GATED_TRACKPAD,
    /** Solution B: head yaw/pitch for motion; TouchPad for click/scroll; camera off. */
    IMU_HEAD_POINTER,
    /** Solution C: hand when visible, else IMU head pointer. */
    HYBRID_IMU_HAND,
    /** Laptop-side MediaPipe: glasses stream JPEG frames; pointer math on PC. */
    LAPTOP_INFERENCE,
}

/**
 * Plan D — laptop-side MediaPipe; glasses stream JPEG + control packets only.
 */
data class PlanDMouseConfig(
    val controlScheme: ControlScheme = ControlScheme.LAPTOP_INFERENCE,
    // --- Legacy translation + pinch ---
    val pinchOnNormalized: Float = 0.38f,
    val pinchOffNormalized: Float = 0.52f,
    val pinchMinHoldMs: Long = 50L,
    val pinchCooldownMs: Long = 150L,
    val moveDeadzone: Float = 0.002f,
    val positionSmoothing: Float = 0.55f,
    val sensitivity: Float = 2000f,
    val usePalmCenter: Boolean = true,
    // --- Shared ---
    val accelerationExponent: Float = 1.1f,
    val maxDeltaPerFrame: Float = 120f,
    // --- Pointing + fist (Stage 6) ---
    val pointingDeadzoneDeg: Float = 15f,
    val pointingMaxOffsetDeg: Float = 40f,
    val pointingSensitivity: Float = 85f,
    val pointingSmoothing: Float = 0.35f,
    val pointingMinExtension: Float = 0.55f,
    val pointingFlipX: Boolean = false,
    val pointingFlipY: Boolean = false,
    /** Normalized thumb-spread dead band around clutch neutral. */
    val thumbVerticalDeadzone: Float = 0.06f,
    /** Thumb spread delta for full vertical speed. */
    val thumbVerticalMaxDelta: Float = 0.16f,
    val thumbSmoothing: Float = 0.35f,
    val fistOnCurl: Float = 0.42f,
    val fistOffCurl: Float = 0.52f,
    val fistThumbCurl: Float = 0.48f,
    val fistMinHoldMs: Long = 50L,
    val fistCooldownMs: Long = 150L,
    // --- IMU head pointer (Solution B/C) ---
    /** Degrees from neutral (after recenter) before cursor moves. */
    val imuDeadzoneDeg: Float = 3f,
    /** Degrees from neutral at which IMU speed saturates. */
    val imuMaxTiltDeg: Float = 22f,
    // --- IMU head pointer (Solution B/C) ---
    /** Internal IMU curve scale (fixed); user gain is [LinkGain] on laptop. */
    val imuSensitivity: Float = 3.5f,
    /** RG-glasses: invert so cursor follows head turn/nod direction. */
    val imuFlipX: Boolean = true,
    val imuFlipY: Boolean = true,
    // --- Hybrid (Solution C) ---
    val hybridImuWeight: Float = 0.8f,
    val hybridHandWeight: Float = 0.2f,
    /** Process 1 of every N camera frames (~15 Hz when camera delivers ~30 FPS). */
    val cameraProcessEveryNFrames: Int = 2,
    /** Bind camera / MediaPipe only while TouchPad clutch is ON (Plan C thermal save). */
    val cameraOnlyWhenClutchOn: Boolean = true,
) {
    init {
        require(pinchOnNormalized > 0f && pinchOnNormalized < pinchOffNormalized)
        require(pinchMinHoldMs >= 0L && pinchCooldownMs >= 0L)
        require(fistMinHoldMs >= 0L && fistCooldownMs >= 0L)
        require(moveDeadzone >= 0f)
        require(positionSmoothing in 0f..1f)
        require(sensitivity > 0f)
        require(pointingSensitivity > 0f)
        require(pointingDeadzoneDeg >= 0f && pointingMaxOffsetDeg > pointingDeadzoneDeg)
        require(pointingSmoothing in 0f..1f)
        require(pointingMinExtension > 0f)
        require(thumbVerticalDeadzone >= 0f)
        require(thumbVerticalMaxDelta > thumbVerticalDeadzone)
        require(thumbSmoothing in 0f..1f)
        require(fistOnCurl > 0f && fistOnCurl < fistOffCurl)
        require(accelerationExponent >= 1f)
        require(maxDeltaPerFrame > 0f)
        require(imuDeadzoneDeg >= 0f)
        require(imuMaxTiltDeg > imuDeadzoneDeg)
        require(imuSensitivity > 0f)
        require(hybridImuWeight in 0f..1f)
        require(hybridHandWeight in 0f..1f)
        require(kotlin.math.abs(hybridImuWeight + hybridHandWeight - 1f) < 0.001f)
        require(cameraProcessEveryNFrames >= 1)
    }

    fun usesCameraForPointer(): Boolean = when (controlScheme) {
        ControlScheme.IMU_HEAD_POINTER -> false
        else -> true
    }

    fun usesOnDeviceMediaPipe(): Boolean = when (controlScheme) {
        ControlScheme.LAPTOP_INFERENCE, ControlScheme.IMU_HEAD_POINTER -> false
        else -> true
    }

    fun usesLaptopInference(): Boolean = controlScheme == ControlScheme.LAPTOP_INFERENCE

    fun usesImuMotion(): Boolean = when (controlScheme) {
        ControlScheme.IMU_HEAD_POINTER, ControlScheme.HYBRID_IMU_HAND -> true
        else -> false
    }

    companion object {
        val PlanD = PlanDMouseConfig(
            controlScheme = ControlScheme.LAPTOP_INFERENCE,
            usePalmCenter = false,
            moveDeadzone = 0.002f,
            sensitivity = 2000f,
            cameraProcessEveryNFrames = 1,
            cameraOnlyWhenClutchOn = true,
        )
        val Default = PlanD
        val LaptopInference = PlanD
        // Legacy presets for unit tests only
        val SolutionA = PlanDMouseConfig(controlScheme = ControlScheme.TOUCH_GATED_TRACKPAD)
        val SolutionB = PlanDMouseConfig(controlScheme = ControlScheme.IMU_HEAD_POINTER)
        val SolutionC = PlanDMouseConfig(
            controlScheme = ControlScheme.HYBRID_IMU_HAND,
            usePalmCenter = false,
            moveDeadzone = 0.001f,
            sensitivity = 1500f,
        )
        val LegacyTranslation = PlanDMouseConfig(controlScheme = ControlScheme.TRANSLATION_PINCH)
        val LegacyPointingFist = PlanDMouseConfig(controlScheme = ControlScheme.POINTING_FIST)
    }
}

/** @deprecated Renamed to [PlanDMouseConfig]; kept for existing unit tests. */
typealias HandMouseConfig = PlanDMouseConfig
