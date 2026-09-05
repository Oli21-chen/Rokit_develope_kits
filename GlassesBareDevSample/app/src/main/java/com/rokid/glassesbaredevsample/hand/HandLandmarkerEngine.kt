package com.rokid.glassesbaredevsample.hand

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.rokid.glassesbaredevsample.camera.CameraFrameTransform
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Synchronous MediaPipe Hand Landmarker (VIDEO mode, CPU, one hand).
 *
 * Expects CameraX [ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888]. Call [detect] only from the
 * analysis executor. Always close [ImageProxy] in the caller after this returns.
 */
class HandLandmarkerEngine {
    private val landmarkerRef = AtomicReference<HandLandmarker?>(null)
    private val timestamps = MonotonicTimestampMs()
    private var rgbaBitmap: Bitmap? = null

    @Volatile
    var lastInitError: String? = null
        private set

    val isReady: Boolean
        get() = landmarkerRef.get() != null

    fun initialize(context: Context): String? {
        close()
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_PATH)
                .setDelegate(Delegate.CPU)
                .build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(1)
                .setMinHandDetectionConfidence(MIN_HAND_DETECTION_CONFIDENCE)
                .setMinHandPresenceConfidence(MIN_HAND_PRESENCE_CONFIDENCE)
                .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
                .build()
            val landmarker = HandLandmarker.createFromOptions(context, options)
            landmarkerRef.set(landmarker)
            lastInitError = null
            Log.i(TAG, "HandLandmarker ready (VIDEO, CPU, numHands=1, RGBA input)")
            null
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            lastInitError = message
            Log.e(TAG, "HandLandmarker init failed", error)
            message
        }
    }

    fun detect(imageProxy: ImageProxy): HandFrameResult {
        val landmarker = landmarkerRef.get()
            ?: error(lastInitError ?: "HandLandmarker not initialized")

        val rotationDegrees = CameraFrameTransform.normalizeRotationDegrees(
            imageProxy.imageInfo.rotationDegrees,
        )
        val timestampMs = timestamps.next(
            TimeUnit.NANOSECONDS.toMillis(imageProxy.imageInfo.timestamp),
        )
        val imageProcessingOptions = ImageProcessingOptions.builder()
            .setRotationDegrees(rotationDegrees)
            .build()

        val bitmap = copyRgbaToBitmap(imageProxy)
        val startedNs = System.nanoTime()
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = landmarker.detectForVideo(mpImage, imageProcessingOptions, timestampMs)
        val inferenceMs = (System.nanoTime() - startedNs) / 1_000_000.0

        val firstHand = result.landmarks().firstOrNull()
        val landmarks = firstHand?.map { landmark ->
            HandLandmark(x = landmark.x(), y = landmark.y(), z = landmark.z())
        }.orEmpty()

        return HandFrameResult(
            timestampMs = timestampMs,
            handPresent = landmarks.isNotEmpty(),
            landmarks = landmarks,
            inferenceMs = inferenceMs,
        )
    }

    fun close() {
        timestamps.reset()
        landmarkerRef.getAndSet(null)?.close()
        rgbaBitmap?.recycle()
        rgbaBitmap = null
    }

    private fun copyRgbaToBitmap(imageProxy: ImageProxy): Bitmap {
        val width = imageProxy.width
        val height = imageProxy.height
        val bitmap = rgbaBitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { rgbaBitmap = it }
        val plane = imageProxy.planes.firstOrNull()
            ?: error("RGBA ImageProxy has no planes")
        val buffer = plane.buffer
        buffer.rewind()
        // CameraX RGBA_8888 may include row stride padding; copy row-by-row when needed.
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val packedRowBytes = width * pixelStride
        if (rowStride == packedRowBytes) {
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            val pixels = IntArray(width * height)
            val row = ByteArray(rowStride)
            var offset = 0
            for (y in 0 until height) {
                buffer.get(row, 0, rowStride.coerceAtMost(buffer.remaining()))
                var x = 0
                while (x < width) {
                    val i = x * pixelStride
                    val r = row[i].toInt() and 0xFF
                    val g = row[i + 1].toInt() and 0xFF
                    val b = row[i + 2].toInt() and 0xFF
                    val a = row[i + 3].toInt() and 0xFF
                    pixels[offset + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    x++
                }
                offset += width
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        }
        return bitmap
    }

    companion object {
        private const val TAG = "HandLandmarker"
        const val MODEL_ASSET_PATH = "ml/hand_landmarker.task"
        private const val MIN_HAND_DETECTION_CONFIDENCE = 0.5f
        private const val MIN_HAND_PRESENCE_CONFIDENCE = 0.5f
        private const val MIN_TRACKING_CONFIDENCE = 0.5f
    }
}
