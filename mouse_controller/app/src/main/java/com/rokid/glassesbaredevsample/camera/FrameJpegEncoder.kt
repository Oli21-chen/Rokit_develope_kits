package com.rokid.glassesbaredevsample.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * Converts CameraX RGBA frames to downscaled JPEG bytes for laptop-side inference.
 */
class FrameJpegEncoder(
    private val targetWidth: Int = TARGET_WIDTH,
    private val targetHeight: Int = TARGET_HEIGHT,
    private val jpegQuality: Int = JPEG_QUALITY,
) {
    private var rgbaBitmap: Bitmap? = null
    private var scaledBitmap: Bitmap? = null

    fun encode(imageProxy: ImageProxy): EncodedFrame? {
        val rotationDegrees = CameraFrameTransform.normalizeRotationDegrees(
            imageProxy.imageInfo.rotationDegrees,
        )
        val source = copyRgbaToBitmap(imageProxy) ?: return null
        val scaled = scaleBitmap(source, rotationDegrees)
        val stream = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)) {
            return null
        }
        return EncodedFrame(
            jpeg = stream.toByteArray(),
            rotationDegrees = rotationDegrees,
            width = scaled.width,
            height = scaled.height,
            sourceWidth = imageProxy.width,
            sourceHeight = imageProxy.height,
        )
    }

    fun close() {
        rgbaBitmap?.recycle()
        rgbaBitmap = null
        scaledBitmap?.recycle()
        scaledBitmap = null
    }

    private fun scaleBitmap(source: Bitmap, rotationDegrees: Int): Bitmap {
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        val out = scaledBitmap?.takeIf { it.width == targetWidth && it.height == targetHeight }
            ?: Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also {
                scaledBitmap = it
            }
        val scaleMatrix = Matrix().apply {
            setScale(
                targetWidth.toFloat() / rotated.width,
                targetHeight.toFloat() / rotated.height,
            )
        }
        android.graphics.Canvas(out).drawBitmap(rotated, scaleMatrix, null)
        if (rotated !== source) {
            rotated.recycle()
        }
        return out
    }

    private fun copyRgbaToBitmap(imageProxy: ImageProxy): Bitmap? {
        val width = imageProxy.width
        val height = imageProxy.height
        val bitmap = rgbaBitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { rgbaBitmap = it }
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        buffer.rewind()
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
                if (buffer.remaining() <= 0) break
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

    data class EncodedFrame(
        val jpeg: ByteArray,
        val rotationDegrees: Int,
        val width: Int,
        val height: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
    )

    companion object {
        /** Stream size sent to laptop (4:3). */
        const val TARGET_WIDTH = 320
        const val TARGET_HEIGHT = 240
        const val JPEG_QUALITY = 65

        /** CameraX analysis resolution before downscale to JPEG stream. */
        const val ANALYSIS_WIDTH = 640
        const val ANALYSIS_HEIGHT = 480
    }
}
