package com.potatodepthscanner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.core.app.ActivityCompat
import com.facebook.react.bridge.*
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import java.nio.ShortBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Multi-strategy depth measurement:
 * 1. Try Camera2 API on ANY camera that outputs DEPTH16
 * 2. Fall back to ARCore raw depth (ToF via ARCore)
 * 3. Fall back to ARCore processed depth (stereo)
 */
class ArCoreDepthModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "ArCoreDepthModule"
        const val TAG = "DepthSensor"
        const val CENTER_RATIO = 0.4f
    }

    override fun getName(): String = NAME

    @ReactMethod
    fun checkDepthSupport(promise: Promise) {
        try {
            // Check Camera2 depth cameras OR ARCore support
            val depthCamera = findAnyDepthCamera()
            if (depthCamera != null) {
                promise.resolve(true)
                return
            }

            // Check ARCore
            val activity: Activity? = reactApplicationContext.currentActivity
            if (activity != null) {
                val availability = ArCoreApk.getInstance().checkAvailability(activity as Context)
                if (availability.isSupported) {
                    promise.resolve(true)
                    return
                }
            }
            promise.resolve(false)
        } catch (e: Exception) {
            promise.resolve(false)
        }
    }

    /**
     * Diagnostic: list ALL cameras and their depth capabilities.
     * Returns a readable string with all info.
     */
    @ReactMethod
    fun diagnoseDepthSensors(promise: Promise) {
        try {
            val cameraManager = reactApplicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val sb = StringBuilder()

            for (cameraId in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val facingStr = when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN"
                }

                val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val capNames = capabilities.map { cap ->
                    when (cap) {
                        0 -> "BACKWARD_COMPATIBLE"
                        1 -> "MANUAL_SENSOR"
                        2 -> "MANUAL_POST_PROCESSING"
                        3 -> "RAW"
                        4 -> "PRIVATE_REPROCESSING"
                        5 -> "READ_SENSOR_SETTINGS"
                        6 -> "BURST_CAPTURE"
                        7 -> "YUV_REPROCESSING"
                        8 -> "DEPTH_OUTPUT"
                        9 -> "CONSTRAINED_HIGH_SPEED"
                        10 -> "MOTION_TRACKING"
                        11 -> "LOGICAL_MULTI_CAMERA"
                        12 -> "MONOCHROME"
                        13 -> "SECURE_IMAGE"
                        14 -> "SYSTEM_CAMERA"
                        15 -> "OFFLINE_PROCESSING"
                        else -> "CAP_$cap"
                    }
                }

                val streamConfigMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val depth16Sizes = streamConfigMap?.getOutputSizes(ImageFormat.DEPTH16)
                val depth16Str = depth16Sizes?.joinToString(", ") { "${it.width}x${it.height}" } ?: "NONE"

                val depthPointSizes = try {
                    streamConfigMap?.getOutputSizes(ImageFormat.DEPTH_POINT_CLOUD)
                } catch (_: Exception) { null }
                val depthPointStr = depthPointSizes?.joinToString(", ") { "${it.width}x${it.height}" } ?: "NONE"

                sb.appendLine("Camera $cameraId ($facingStr):")
                sb.appendLine("  Capabilities: $capNames")
                sb.appendLine("  DEPTH16 sizes: $depth16Str")
                sb.appendLine("  DEPTH_POINT_CLOUD: $depthPointStr")
                sb.appendLine()
            }

            Log.d(TAG, "Camera diagnostics:\n$sb")
            promise.resolve(sb.toString())
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    /**
     * Find ANY camera that supports DEPTH16 output (not just ones with DEPTH_OUTPUT capability).
     */
    private fun findAnyDepthCamera(): Pair<String, Size>? {
        try {
            val cameraManager = reactApplicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            for (cameraId in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                val streamConfigMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

                val depthSizes = try {
                    streamConfigMap.getOutputSizes(ImageFormat.DEPTH16)
                } catch (_: Exception) { null }

                if (depthSizes != null && depthSizes.isNotEmpty()) {
                    val bestSize = depthSizes.maxByOrNull { it.width * it.height }!!
                    Log.d(TAG, "Found depth camera: $cameraId, sizes: ${depthSizes.map { "${it.width}x${it.height}" }}")
                    return Pair(cameraId, bestSize)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning cameras", e)
        }
        return null
    }

    /**
     * Main measurement function — tries Camera2 first, then ARCore.
     */
    @ReactMethod
    fun measureDepth(promise: Promise) {
        Thread {
            // Strategy 1: Camera2 direct depth
            val camera2Result = tryCamera2Depth()
            if (camera2Result != null) {
                promise.resolve(camera2Result)
                return@Thread
            }

            Log.d(TAG, "Camera2 depth not available, trying ARCore...")

            // Strategy 2: ARCore depth
            val arCoreResult = tryARCoreDepth()
            if (arCoreResult != null) {
                promise.resolve(arCoreResult)
                return@Thread
            }

            promise.reject("NO_DEPTH", "Could not measure depth with any method")
        }.start()
    }

    // ======== STRATEGY 1: Camera2 Direct Depth ========

    private fun tryCamera2Depth(): WritableMap? {
        val depthInfo = findAnyDepthCamera() ?: return null
        val cameraId = depthInfo.first
        val depthSize = depthInfo.second

        var handlerThread: HandlerThread? = null
        var cameraDevice: CameraDevice? = null
        var imageReader: ImageReader? = null
        var captureSession: CameraCaptureSession? = null

        try {
            if (ActivityCompat.checkSelfPermission(reactApplicationContext, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                return null
            }

            val cameraManager = reactApplicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            handlerThread = HandlerThread("DepthThread").also { it.start() }
            val handler = Handler(handlerThread.looper)

            imageReader = ImageReader.newInstance(depthSize.width, depthSize.height, ImageFormat.DEPTH16, 8)

            val depthFrames = mutableListOf<ShortArray>()
            val frameWidth = depthSize.width
            val frameHeight = depthSize.height
            val framesLatch = CountDownLatch(5)

            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    synchronized(depthFrames) {
                        if (depthFrames.size < 5) {
                            // Copy buffer data before closing image
                            val buffer = image.planes[0].buffer.asShortBuffer()
                            val data = ShortArray(buffer.remaining())
                            buffer.get(data)
                            depthFrames.add(data)
                            framesLatch.countDown()
                        }
                    }
                } finally {
                    image.close()
                }
            }, handler)

            // Open camera
            val cameraLatch = CountDownLatch(1)
            val cameraRef = AtomicReference<CameraDevice?>(null)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { cameraRef.set(camera); cameraLatch.countDown() }
                override fun onDisconnected(camera: CameraDevice) { cameraLatch.countDown() }
                override fun onError(camera: CameraDevice, error: Int) { cameraLatch.countDown() }
            }, handler)

            if (!cameraLatch.await(8, TimeUnit.SECONDS)) return null
            cameraDevice = cameraRef.get() ?: return null

            // Create session
            val sessionLatch = CountDownLatch(1)
            val sessionRef = AtomicReference<CameraCaptureSession?>(null)

            cameraDevice.createCaptureSession(
                listOf(imageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) { sessionRef.set(session); sessionLatch.countDown() }
                    override fun onConfigureFailed(session: CameraCaptureSession) { sessionLatch.countDown() }
                },
                handler
            )

            if (!sessionLatch.await(8, TimeUnit.SECONDS)) return null
            captureSession = sessionRef.get() ?: return null

            // Capture
            val request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                .apply { addTarget(imageReader.surface) }.build()
            captureSession.setRepeatingRequest(request, null, handler)

            framesLatch.await(8, TimeUnit.SECONDS)
            try { captureSession.stopRepeating() } catch (_: Exception) {}

            // Process frames
            if (depthFrames.isEmpty()) return null

            return processCamera2Frames(depthFrames, frameWidth, frameHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Camera2 depth error: ${e.message}", e)
            return null
        } finally {
            try { captureSession?.close() } catch (_: Exception) {}
            try { cameraDevice?.close() } catch (_: Exception) {}
            try { imageReader?.close() } catch (_: Exception) {}
            handlerThread?.quitSafely()
        }
    }

    private fun processCamera2Frames(frames: List<ShortArray>, width: Int, height: Int): WritableMap? {
        val frameMedians = mutableListOf<Double>()
        var globalMin = Double.MAX_VALUE
        var globalMax = 0.0
        var totalPixels: Long = 0

        for (data in frames) {
            val result = processDepthData(data, width, height, isCamera2 = true) ?: continue
            frameMedians.add(result.median)
            if (result.min < globalMin) globalMin = result.min
            if (result.max > globalMax) globalMax = result.max
            totalPixels += result.pixelCount
            Log.d(TAG, "Camera2 frame: median=${String.format("%.3f", result.median)}m (${result.pixelCount}px)")
        }

        if (frameMedians.isEmpty()) return null

        frameMedians.sort()
        val finalDist = frameMedians[frameMedians.size / 2]

        Log.d(TAG, "Camera2 FINAL: ${String.format("%.3f", finalDist)}m")
        return Arguments.createMap().apply {
            putDouble("averageDistance", finalDist)
            putDouble("minDistance", if (globalMin == Double.MAX_VALUE) 0.0 else globalMin)
            putDouble("maxDistance", globalMax)
            putInt("framesUsed", frameMedians.size)
            putDouble("totalPixels", totalPixels.toDouble())
            putString("method", "Camera2 ToF")
        }
    }

    // ======== STRATEGY 2: ARCore Depth ========

    private fun tryARCoreDepth(): WritableMap? {
        var eglDisplay: EGLDisplay? = null
        var eglContext: EGLContext? = null
        var eglSurface: EGLSurface? = null
        var session: Session? = null
        var textureId = -1

        try {
            val activity: Activity? = reactApplicationContext.currentActivity ?: return null

            // EGL setup
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)

            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0]!!,
                EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0]!!,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]

            // ARCore session
            session = Session(activity as Context)
            val config = Config(session)
            config.depthMode = Config.DepthMode.AUTOMATIC
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            session.configure(config)
            session.setCameraTextureName(textureId)
            session.resume()

            // Give ARCore much more time to track (5 seconds)
            Log.d(TAG, "ARCore: Waiting 5s for tracking...")
            Thread.sleep(5000)

            // Try to get good frames
            val frameMedians = mutableListOf<Double>()
            var globalMin = Double.MAX_VALUE
            var globalMax = 0.0
            var totalPixels: Long = 0
            var attempts = 0
            val maxAttempts = 100
            val targetFrames = 5

            while (frameMedians.size < targetFrames && attempts < maxAttempts) {
                attempts++
                try {
                    val frame = session.update()
                    if (frame.camera.trackingState != TrackingState.TRACKING) {
                        Thread.sleep(100)
                        continue
                    }

                    // Try raw depth first (uses ToF if available)
                    var result = tryARCoreRawDepth(frame)
                    val method = if (result != null) "ARCore Raw/ToF" else {
                        result = tryARCoreProcessedDepth(frame)
                        "ARCore Stereo"
                    }

                    if (result != null) {
                        frameMedians.add(result.median)
                        if (result.min < globalMin) globalMin = result.min
                        if (result.max > globalMax) globalMax = result.max
                        totalPixels += result.pixelCount
                        Log.d(TAG, "$method frame ${frameMedians.size}: " +
                            "median=${String.format("%.3f", result.median)}m " +
                            "min=${String.format("%.3f", result.min)}m " +
                            "(${result.pixelCount}px)")
                    }

                    Thread.sleep(100)
                } catch (e: Exception) {
                    Thread.sleep(200)
                }
            }

            if (frameMedians.isEmpty()) return null

            frameMedians.sort()
            val finalDist = frameMedians[frameMedians.size / 2]

            Log.d(TAG, "ARCore FINAL: ${String.format("%.3f", finalDist)}m from ${frameMedians.size} frames")
            return Arguments.createMap().apply {
                putDouble("averageDistance", finalDist)
                putDouble("minDistance", if (globalMin == Double.MAX_VALUE) 0.0 else globalMin)
                putDouble("maxDistance", globalMax)
                putInt("framesUsed", frameMedians.size)
                putDouble("totalPixels", totalPixels.toDouble())
                putString("method", "ARCore")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ARCore depth error: ${e.message}", e)
            return null
        } finally {
            try { session?.pause() } catch (_: Exception) {}
            try { session?.close() } catch (_: Exception) {}
            if (textureId != -1) try { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) } catch (_: Exception) {}
            if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                eglSurface?.let { EGL14.eglDestroySurface(eglDisplay, it) }
                eglContext?.let { EGL14.eglDestroyContext(eglDisplay, it) }
                EGL14.eglTerminate(eglDisplay)
            }
        }
    }

    private fun tryARCoreRawDepth(frame: Frame): FrameResult? {
        var depthImage: Image? = null
        var confImage: Image? = null
        try {
            depthImage = frame.acquireRawDepthImage()
            confImage = frame.acquireRawDepthConfidenceImage()
            val w = depthImage.width
            val h = depthImage.height
            val buffer = depthImage.planes[0].buffer.asShortBuffer()
            val rowStride = depthImage.planes[0].rowStride / 2
            val data = ShortArray(buffer.remaining())
            buffer.get(data)
            return processDepthData(data, w, h, isCamera2 = false, rowStride = rowStride)
        } catch (_: NotYetAvailableException) { return null
        } catch (_: Exception) { return null
        } finally {
            depthImage?.close()
            confImage?.close()
        }
    }

    private fun tryARCoreProcessedDepth(frame: Frame): FrameResult? {
        var depthImage: Image? = null
        try {
            depthImage = frame.acquireDepthImage()
            val w = depthImage.width
            val h = depthImage.height
            val buffer = depthImage.planes[0].buffer.asShortBuffer()
            val rowStride = depthImage.planes[0].rowStride / 2
            val data = ShortArray(buffer.remaining())
            buffer.get(data)
            return processDepthData(data, w, h, isCamera2 = false, rowStride = rowStride)
        } catch (_: NotYetAvailableException) { return null
        } catch (_: Exception) { return null
        } finally {
            depthImage?.close()
        }
    }

    // ======== Shared Processing ========

    data class FrameResult(val median: Double, val min: Double, val max: Double, val pixelCount: Int)

    /**
     * Process depth pixel data.
     * @param isCamera2 true = DEPTH16 with confidence bits, false = raw mm values (ARCore)
     */
    private fun processDepthData(
        data: ShortArray,
        width: Int,
        height: Int,
        isCamera2: Boolean,
        rowStride: Int = width
    ): FrameResult? {
        val marginX = ((1.0f - CENTER_RATIO) / 2 * width).toInt()
        val marginY = ((1.0f - CENTER_RATIO) / 2 * height).toInt()

        val depthValues = mutableListOf<Double>()

        for (y in marginY until (height - marginY)) {
            for (x in marginX until (width - marginX)) {
                val index = y * rowStride + x
                if (index < 0 || index >= data.size) continue

                val rawValue = data[index].toInt() and 0xFFFF

                val depthMm: Int
                if (isCamera2) {
                    // Camera2 DEPTH16: lower 13 bits = depth, upper 3 = confidence
                    depthMm = rawValue and 0x1FFF
                    val confidence = (rawValue shr 13) and 0x7
                    if (confidence < 1) continue
                } else {
                    // ARCore: full 16 bits = depth in mm
                    depthMm = rawValue
                }

                if (depthMm in 30..5000) { // 3cm to 5m
                    depthValues.add(depthMm / 1000.0)
                }
            }
        }

        if (depthValues.isEmpty()) return null

        depthValues.sort()
        // Use 25th percentile instead of median — closer to the nearest surface
        val p25Index = depthValues.size / 4
        val distance = depthValues[p25Index]

        return FrameResult(
            median = distance,
            min = depthValues.first(),
            max = depthValues.last(),
            pixelCount = depthValues.size
        )
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
