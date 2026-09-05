package com.rokid.glassesbaredevsample.camera

import android.util.Size
import androidx.camera.core.ImageAnalysis
import java.util.concurrent.Executor

private const val ANALYSIS_WIDTH = 640
private const val ANALYSIS_HEIGHT = 480

/**
 * Builds ImageAnalysis for ML. RGBA_8888 is required by MediaPipe Tasks Vision
 * (`MediaImageBuilder` rejects YUV_420_888 on this stack).
 */
@Suppress("DEPRECATION")
fun createAnalysisUseCase(
    executor: Executor,
    analyzer: ImageAnalysis.Analyzer,
): ImageAnalysis = ImageAnalysis.Builder()
    .setTargetResolution(Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT))
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    .build()
    .also { it.setAnalyzer(executor, analyzer) }
