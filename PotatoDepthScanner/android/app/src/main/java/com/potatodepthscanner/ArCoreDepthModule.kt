package com.potatodepthscanner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.core.app.ActivityCompat
import com.facebook.react.bridge.*
import java.nio.ShortBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Direct ToF sensor access via Camera2 API.
 * Bypasses ARCore entirely for accurate hardware depth measurements.
 *
 * Flow:
 * 1. Find the depth camera (ToF sensor) via CameraManager
 * 2. Open it and capture depth frames in DEPTH16 format
 * 3. Read center region, use median for robust result
 * 4. Clean up and return
 */
class ArCoreDepthModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "ArCoreDepthModule"
        const val TAG = "DepthSensor"
        const val CENTER_RATIO = 0.4f      // Use center 40% of depth image
        const val DEPTH_FRAME_COUNT = 5    // Frames to collect
        const val TIMEOUT_SECONDS = 10L    // Max wait time
    }

    override fun getName(): String = NAME

    /**
     * Check if this device has a depth (ToF) camera.
     */
    @ReactMethod
    fun checkDepthSupport(promise: Promise) {
        try {
            val depthCameraId = findDepthCamera()
            promise.resolve(depthCameraId != null)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking depth support", e)
            promise.resolve(false)
        }
    }

    /**
     * Find the depth/ToF camera ID.
     */
    private fun findDepthCamera(): String? {
        val cameraManager = reactApplicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        for (cameraId in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue

            if (capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)) {
                // Check it supports DEPTH16 format
                val streamConfigMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val depthSizes = streamConfigMap.getOutputSizes(ImageFormat.DEPTH16)
                if (depthSizes != null && depthSizes.isNotEmpty()) {
                    Log.d(TAG, "Found depth camera: $cameraId, sizes: ${depthSizes.map { "${it.width}x${it.height}" }}")
                    return cameraId
                }
            }
        }

        Log.w(TAG, "No depth camera found on this device")
        return null
    }

    /**
     * Measure depth using the ToF sensor directly via Camera2 API.
     * Returns: { averageDistance, minDistance, maxDistance, framesUsed, totalPixels }
     */
    @ReactMethod
    fun measureDepth(promise: Promise) {
        Thread {
            var handlerThread: HandlerThread? = null
            var cameraDevice: CameraDevice? = null
            var imageReader: ImageReader? = null
            var captureSession: CameraCaptureSession? = null

            try {
                val depthCameraId = findDepthCamera()
                if (depthCameraId == null) {
                    promise.reject("NO_SENSOR", "No ToF/depth sensor found on this device")
                    return@Thread
                }

                val cameraManager = reactApplicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val chars = cameraManager.getCameraCharacteristics(depthCameraId)

                // Check camera permission
                if (ActivityCompat.checkSelfPermission(reactApplicationContext, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                    promise.reject("NO_PERMISSION", "Camera permission not granted")
                    return@Thread
                }

                // Get the best depth output size
                val streamConfigMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                val depthSizes = streamConfigMap.getOutputSizes(ImageFormat.DEPTH16)!!
                val depthSize = depthSizes.maxByOrNull { it.width * it.height }
                    ?: throw Exception("No depth sizes available")

                Log.d(TAG, "Using depth size: ${depthSize.width}x${depthSize.height}")

                // Create handler thread for camera callbacks
                handlerThread = HandlerThread("DepthThread").also { it.start() }
                val handler = Handler(handlerThread.looper)

                // Create ImageReader for depth frames
                imageReader = ImageReader.newInstance(
                    depthSize.width, depthSize.height,
                    ImageFormat.DEPTH16, DEPTH_FRAME_COUNT + 2
                )

                // Collect depth frames
                val depthFrames = mutableListOf<Image>()
                val framesLatch = CountDownLatch(DEPTH_FRAME_COUNT)

                imageReader.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    synchronized(depthFrames) {
                        if (depthFrames.size < DEPTH_FRAME_COUNT) {
                            depthFrames.add(image)
                            framesLatch.countDown()
                            Log.d(TAG, "Captured depth frame ${depthFrames.size}/${DEPTH_FRAME_COUNT}")
                        } else {
                            image.close()
                        }
                    }
                }, handler)

                // Open camera
                val cameraLatch = CountDownLatch(1)
                val cameraRef = AtomicReference<CameraDevice?>(null)
                val cameraError = AtomicReference<String?>(null)

                cameraManager.openCamera(depthCameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraRef.set(camera)
                        cameraLatch.countDown()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        cameraError.set("Camera disconnected")
                        cameraLatch.countDown()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        cameraError.set("Camera error: $error")
                        cameraLatch.countDown()
                    }
                }, handler)

                if (!cameraLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    promise.reject("TIMEOUT", "Timed out opening depth camera")
                    return@Thread
                }

                cameraDevice = cameraRef.get()
                if (cameraDevice == null || cameraError.get() != null) {
                    promise.reject("CAMERA_ERROR", cameraError.get() ?: "Failed to open depth camera")
                    return@Thread
                }

                Log.d(TAG, "Depth camera opened successfully")

                // Create capture session
                val sessionLatch = CountDownLatch(1)
                val sessionRef = AtomicReference<CameraCaptureSession?>(null)
                val sessionError = AtomicReference<String?>(null)

                cameraDevice.createCaptureSession(
                    listOf(imageReader.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            sessionRef.set(session)
                            sessionLatch.countDown()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            sessionError.set("Session configuration failed")
                            sessionLatch.countDown()
                        }
                    },
                    handler
                )

                if (!sessionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    promise.reject("TIMEOUT", "Timed out creating capture session")
                    return@Thread
                }

                captureSession = sessionRef.get()
                if (captureSession == null || sessionError.get() != null) {
                    promise.reject("SESSION_ERROR", sessionError.get() ?: "Failed to create session")
                    return@Thread
                }

                Log.d(TAG, "Capture session created, starting repeating request")

                // Start capturing
                val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(imageReader.surface)
                }.build()

                captureSession.setRepeatingRequest(captureRequest, null, handler)

                // Wait for frames
                if (!framesLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    Log.w(TAG, "Only got ${depthFrames.size}/${DEPTH_FRAME_COUNT} frames before timeout")
                }

                // Stop capturing
                try { captureSession.stopRepeating() } catch (_: Exception) {}

                // Process collected frames
                if (depthFrames.isEmpty()) {
                    promise.reject("NO_DEPTH", "No depth frames captured")
                    return@Thread
                }

                Log.d(TAG, "Processing ${depthFrames.size} depth frames...")

                val frameMedians = mutableListOf<Double>()
                var globalMin = Double.MAX_VALUE
                var globalMax = 0.0
                var totalPixels: Long = 0

                synchronized(depthFrames) {
                    for (image in depthFrames) {
                        try {
                            val result = processDepthFrame(image)
                            if (result != null) {
                                frameMedians.add(result.median)
                                if (result.min < globalMin) globalMin = result.min
                                if (result.max > globalMax) globalMax = result.max
                                totalPixels += result.pixelCount
                                Log.d(TAG, "Frame result: median=${String.format("%.3f", result.median)}m " +
                                    "min=${String.format("%.3f", result.min)}m " +
                                    "max=${String.format("%.3f", result.max)}m " +
                                    "(${result.pixelCount} pixels)")
                            }
                        } finally {
                            image.close()
                        }
                    }
                    depthFrames.clear()
                }

                if (frameMedians.isEmpty()) {
                    promise.reject("NO_VALID_DEPTH", "No valid depth data in captured frames")
                    return@Thread
                }

                // Median of medians
                frameMedians.sort()
                val finalDistance = if (frameMedians.size % 2 == 0) {
                    (frameMedians[frameMedians.size / 2 - 1] + frameMedians[frameMedians.size / 2]) / 2.0
                } else {
                    frameMedians[frameMedians.size / 2]
                }

                Log.d(TAG, "FINAL: distance=${String.format("%.3f", finalDistance)}m from ${frameMedians.size} frames")

                val result = Arguments.createMap().apply {
                    putDouble("averageDistance", finalDistance)
                    putDouble("minDistance", if (globalMin == Double.MAX_VALUE) 0.0 else globalMin)
                    putDouble("maxDistance", globalMax)
                    putInt("framesUsed", frameMedians.size)
                    putDouble("totalPixels", totalPixels.toDouble())
                }
                promise.resolve(result)

            } catch (e: SecurityException) {
                promise.reject("NO_PERMISSION", "Camera permission required: ${e.message}")
            } catch (e: Exception) {
                promise.reject("ERROR", "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try { captureSession?.close() } catch (_: Exception) {}
                try { cameraDevice?.close() } catch (_: Exception) {}
                try { imageReader?.close() } catch (_: Exception) {}
                handlerThread?.quitSafely()
                Log.d(TAG, "Cleaned up depth resources")
            }
        }.start()
    }

    data class FrameResult(
        val median: Double,
        val min: Double,
        val max: Double,
        val pixelCount: Int
    )

    /**
     * Process a single DEPTH16 frame.
     * Reads center region, filters by confidence, returns median depth.
     *
     * DEPTH16 format: each pixel is 16 bits
     * - Lower 13 bits: depth in millimeters
     * - Upper 3 bits: confidence (0=low, 7=high)
     */
    private fun processDepthFrame(image: Image): FrameResult? {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer: ShortBuffer = plane.buffer.asShortBuffer()
        val pixelStride = plane.pixelStride / 2  // Convert bytes to shorts
        val rowStride = plane.rowStride / 2       // Convert bytes to shorts

        // Center region bounds
        val marginX = ((1.0f - CENTER_RATIO) / 2 * width).toInt()
        val marginY = ((1.0f - CENTER_RATIO) / 2 * height).toInt()
        val startX = marginX
        val endX = width - marginX
        val startY = marginY
        val endY = height - marginY

        val depthValues = mutableListOf<Double>()

        for (y in startY until endY) {
            for (x in startX until endX) {
                val index = y * rowStride + x * maxOf(pixelStride, 1)
                if (index >= 0 && index < buffer.limit()) {
                    val rawValue = buffer.get(index).toInt() and 0xFFFF

                    // Extract depth and confidence from DEPTH16
                    val depthMm = rawValue and 0x1FFF          // Lower 13 bits
                    val confidence = (rawValue shr 13) and 0x7  // Upper 3 bits

                    // Only use high-confidence pixels with valid depth
                    if (confidence >= 1 && depthMm in 30..5000) { // 3cm to 5m
                        depthValues.add(depthMm / 1000.0)
                    }
                }
            }
        }

        if (depthValues.isEmpty()) {
            // Try without confidence filter (some sensors encode differently)
            for (y in startY until endY) {
                for (x in startX until endX) {
                    val index = y * rowStride + x * maxOf(pixelStride, 1)
                    if (index >= 0 && index < buffer.limit()) {
                        val rawValue = buffer.get(index).toInt() and 0xFFFF
                        // Treat full 16 bits as depth in mm
                        if (rawValue in 30..5000) {
                            depthValues.add(rawValue / 1000.0)
                        }
                    }
                }
            }
        }

        if (depthValues.isEmpty()) return null

        depthValues.sort()
        val median = depthValues[depthValues.size / 2]

        return FrameResult(
            median = median,
            min = depthValues.first(),
            max = depthValues.last(),
            pixelCount = depthValues.size
        )
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
