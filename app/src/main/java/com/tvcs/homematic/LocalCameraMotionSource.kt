package com.tvcs.homematic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * LocalCameraMotionSource
 *
 * Uses CameraX ImageAnalysis to feed frames from the device's front or rear
 * camera into [MotionDetectionEngine]. Requires CAMERA permission.
 *
 * Call [start] to begin, [stop] to release camera resources.
 * Re-create or call [start] again after a config change if needed.
 *
 * @param facing  [CameraSelector.LENS_FACING_FRONT] or [LENS_FACING_BACK]
 */
class LocalCameraMotionSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val motionEngine: MotionDetectionEngine,
    var facing: Int = CameraSelector.LENS_FACING_FRONT
) {
    companion object {
        private const val TAG = "LocalCamMotion"
        /** Analyse at most 1 frame every N ms to avoid overloading the engine. */
        private const val ANALYSIS_INTERVAL_MS = 2_000L
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var lastAnalysisMs = 0L

    /** Start the camera and begin sending frames to the motion engine. */
    fun start() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                bindCamera(provider)
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Stop the camera and release all resources. */
    fun stop() {
        try {
            cameraProvider?.unbindAll()
            cameraProvider = null
        } catch (e: Exception) {
            Log.w(TAG, "stop: ${e.message}")
        }
    }

    /** Restart with a different lens (front ↔ back). */
    fun switchFacing(newFacing: Int) {
        facing = newFacing
        cameraProvider?.let { bindCamera(it) }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun bindCamera(provider: ProcessCameraProvider) {
        try {
            provider.unbindAll()

            val selector = CameraSelector.Builder()
                .requireLensFacing(facing)
                .build()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                processFrame(imageProxy)
            }

            provider.bindToLifecycle(lifecycleOwner, selector, analysis)
            Log.d(TAG, "Camera bound (facing=$facing)")
        } catch (e: Exception) {
            Log.e(TAG, "bindCamera failed: ${e.message}", e)
        }
    }

    private fun processFrame(image: ImageProxy) {
        try {
            if (!motionEngine.enabled) { image.close(); return }

            val now = System.currentTimeMillis()
            if (now - lastAnalysisMs < ANALYSIS_INTERVAL_MS) { image.close(); return }
            lastAnalysisMs = now

            val bmp = image.toBitmap() ?: run { image.close(); return }
            motionEngine.process(bmp)
            bmp.recycle()
        } finally {
            image.close()
        }
    }

    // YUV_420_888 → JPEG → Bitmap (most reliable cross-device path)
    private fun ImageProxy.toBitmap(): Bitmap? {
        return try {
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 60, out)
            val raw = out.toByteArray()
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null

            // Rotate according to image rotation metadata
            val rotation = imageInfo.rotationDegrees
            if (rotation == 0) bmp
            else {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                bmp.recycle()
                rotated
            }
        } catch (e: Exception) {
            Log.w(TAG, "toBitmap failed: ${e.message}")
            null
        }
    }
}
