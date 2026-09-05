package com.rokid.glassesbaredevsample.camera

import android.util.Size
import androidx.camera.core.ImageAnalysis
import java.util.concurrent.Executor

/**
 * Builds ImageAnalysis for Plan D frame capture. RGBA_8888 for reliable rowStride handling.
 */
@Suppress("DEPRECATION")
fun createAnalysisUseCase(
    executor: Executor,
    analyzer: ImageAnalysis.Analyzer,
): ImageAnalysis = ImageAnalysis.Builder()
    .setTargetResolution(Size(FrameJpegEncoder.ANALYSIS_WIDTH, FrameJpegEncoder.ANALYSIS_HEIGHT))
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    .build()
    .also { it.setAnalyzer(executor, analyzer) }
