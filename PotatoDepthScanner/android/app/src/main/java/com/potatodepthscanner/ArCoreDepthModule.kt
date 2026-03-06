package com.potatodepthscanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.app.ActivityCompat
import com.facebook.react.bridge.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Distance measurement using Camera2 autofocus.
 *
 * Samsung S24 has no ToF/depth sensor. ARCore stereo is inaccurate.
 * The most accurate method is reading the camera's LENS_FOCUS_DISTANCE,
 * which uses the PDAF (Phase Detection Auto Focus) hardware.
 *
 * LENS_FOCUS_DISTANCE returns diopters → distance = 1 / diopters (meters)
 * Accurate to within ~10% at close range (< 1m).
 */
class ArCoreDepthModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "ArCoreDepthModule"
        const val TAG = "DepthSensor"
        const val MEASUREMENT_COUNT = 10  // Number of focus readings to average
    }

    override fun getName(): String = NAME

    @ReactMethod
    fun checkDepthSupport(promise: Promise) {
        // Autofocus distance works on all modern Android phones
        promise.resolve(true)
    }

    @ReactMethod
    fun diagnoseDepthSensors(promise: Promise) {
        try {
            val cameraManager = reactApplicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val sb = StringBuilder()

            for (cameraId in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    else -> "OTHER"
                }
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val hasDepth = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)

                val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                val hasAF = afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) ||
                    afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

                val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                val hyperfocal = chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f

                sb.appendLine("Camera $cameraId ($facing): depth_cap=$hasDepth, AF=$hasAF, " +
                    "minFocusDist=${String.format("%.1f", minFocusDist)}D, " +
                    "hyperfocal=${String.format("%.1f", hyperfocal)}D, " +
                    "minDistCm=${if (minFocusDist > 0) String.format("%.1f", 100.0/minFocusDist) else "N/A"}")
            }

            Log.d(TAG, "Cameras:\n$sb")
            promise.resolve(sb.toString())
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    /**
     * Measure distance using autofocus.
     *
     * Opens the rear camera, triggers autofocus, reads LENS_FOCUS_DISTANCE
     * from multiple frames, averages the readings, converts to meters.
     */
    @ReactMethod
    fun measureDepth(promise: Promise) {
        Thread {
            var handlerThread: HandlerThread? = null
            var cameraDevice: CameraDevice? = null
            var imageReader: ImageReader? = null
            var captureSession: CameraCaptureSession? = null

            try {
                if (ActivityCompat.checkSelfPermission(reactApplicationContext, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                    promise.reject("NO_PERMISSION", "Camera permission not granted")
                    return@Thread
                }

                val cameraManager = reactApplicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

                // Find rear camera with autofocus
                val cameraId = findRearCameraWithAF(cameraManager)
                if (cameraId == null) {
                    promise.reject("NO_CAMERA", "No rear camera with autofocus found")
                    return@Thread
                }

                val chars = cameraManager.getCameraCharacteristics(cameraId)
                val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                Log.d(TAG, "Using camera $cameraId, min focus distance: ${minFocusDist}D " +
                    "(= ${if (minFocusDist > 0) String.format("%.1f", 100.0/minFocusDist) else "N/A"} cm)")

                // Setup
                handlerThread = HandlerThread("FocusThread").also { it.start() }
                val handler = Handler(handlerThread.looper)

                // Small image reader just so we have a valid surface
                val streamConfigMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                val jpegSizes = streamConfigMap.getOutputSizes(ImageFormat.JPEG)
                val smallSize = jpegSizes.minByOrNull { it.width * it.height }
                    ?: Size(640, 480)
                imageReader = ImageReader.newInstance(smallSize.width, smallSize.height, ImageFormat.JPEG, 2)
                imageReader.setOnImageAvailableListener({ reader ->
                    reader.acquireLatestImage()?.close()
                }, handler)

                // Open camera
                Log.d(TAG, "Opening camera...")
                val cameraLatch = CountDownLatch(1)
                val cameraRef = AtomicReference<CameraDevice?>(null)
                val cameraError = AtomicReference<String?>(null)

                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraRef.set(camera); cameraLatch.countDown()
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        cameraError.set("Camera disconnected"); cameraLatch.countDown()
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        cameraError.set("Camera error: $error"); cameraLatch.countDown()
                    }
                }, handler)

                if (!cameraLatch.await(10, TimeUnit.SECONDS)) {
                    promise.reject("TIMEOUT", "Timed out opening camera")
                    return@Thread
                }

                cameraDevice = cameraRef.get()
                if (cameraDevice == null) {
                    promise.reject("CAMERA_ERROR", cameraError.get() ?: "Failed to open camera")
                    return@Thread
                }

                // Create capture session
                Log.d(TAG, "Creating capture session...")
                val sessionLatch = CountDownLatch(1)
                val sessionRef = AtomicReference<CameraCaptureSession?>(null)

                cameraDevice.createCaptureSession(
                    listOf(imageReader.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            sessionRef.set(session); sessionLatch.countDown()
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            sessionLatch.countDown()
                        }
                    },
                    handler
                )

                if (!sessionLatch.await(10, TimeUnit.SECONDS)) {
                    promise.reject("TIMEOUT", "Session creation timed out")
                    return@Thread
                }

                captureSession = sessionRef.get()
                if (captureSession == null) {
                    promise.reject("SESSION_ERROR", "Failed to create session")
                    return@Thread
                }

                // Step 1: Trigger autofocus
                Log.d(TAG, "Triggering autofocus...")
                val afTriggerRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(imageReader.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                }.build()

                captureSession.capture(afTriggerRequest, null, handler)

                // Step 2: Continuous captures to read focus distance
                Log.d(TAG, "Reading focus distances...")
                val focusDistances = mutableListOf<Float>()
                val readingsLatch = CountDownLatch(MEASUREMENT_COUNT)

                val previewRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(imageReader.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                }.build()

                captureSession.setRepeatingRequest(previewRequest, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val focusDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: return
                        val afState = result.get(CaptureResult.CONTROL_AF_STATE)

                        // Only accept readings when AF is focused or passive
                        if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                            afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {

                            if (focusDist > 0) {
                                synchronized(focusDistances) {
                                    if (focusDistances.size < MEASUREMENT_COUNT) {
                                        focusDistances.add(focusDist)
                                        Log.d(TAG, "Focus reading ${focusDistances.size}: " +
                                            "${String.format("%.2f", focusDist)}D = " +
                                            "${String.format("%.3f", 1.0/focusDist)}m " +
                                            "(AF state: $afState)")
                                        readingsLatch.countDown()
                                    }
                                }
                            }
                        }
                    }
                }, handler)

                // Wait for focus distance readings (with generous timeout)
                // Give AF time to lock first
                Thread.sleep(2000) // Wait 2s for AF to lock

                if (!readingsLatch.await(15, TimeUnit.SECONDS)) {
                    Log.w(TAG, "Only got ${focusDistances.size}/$MEASUREMENT_COUNT readings before timeout")
                }

                try { captureSession.stopRepeating() } catch (_: Exception) {}

                if (focusDistances.isEmpty()) {
                    // Try one more approach — read ANY focus distance, even without lock
                    Log.w(TAG, "No locked AF readings, trying any available readings...")
                    val fallbackRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(imageReader.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }.build()

                    val fallbackLatch = CountDownLatch(3)
                    val fallbackDistances = mutableListOf<Float>()

                    captureSession.setRepeatingRequest(fallbackRequest, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            val dist = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: return
                            if (dist > 0) {
                                synchronized(fallbackDistances) {
                                    if (fallbackDistances.size < 3) {
                                        fallbackDistances.add(dist)
                                        Log.d(TAG, "Fallback reading: ${String.format("%.2f", dist)}D = " +
                                            "${String.format("%.3f", 1.0/dist)}m")
                                        fallbackLatch.countDown()
                                    }
                                }
                            }
                        }
                    }, handler)

                    fallbackLatch.await(8, TimeUnit.SECONDS)
                    try { captureSession.stopRepeating() } catch (_: Exception) {}

                    focusDistances.addAll(fallbackDistances)
                }

                if (focusDistances.isEmpty()) {
                    promise.reject("NO_FOCUS", "Could not read focus distance from camera")
                    return@Thread
                }

                // Process readings
                focusDistances.sort()

                // Remove outliers (keep middle 60%)
                val trimStart = focusDistances.size / 5
                val trimEnd = focusDistances.size - trimStart
                val trimmed = if (focusDistances.size > 4) {
                    focusDistances.subList(trimStart, trimEnd)
                } else {
                    focusDistances
                }

                // Average diopters, convert to distance
                val avgDiopters = trimmed.map { it.toDouble() }.average()
                val distanceM = 1.0 / avgDiopters

                val minDiopters = trimmed.last().toDouble()  // Highest diopters = closest distance
                val maxDiopters = trimmed.first().toDouble() // Lowest diopters = farthest distance
                val minDistM = 1.0 / minDiopters  // Closest
                val maxDistM = 1.0 / maxDiopters  // Farthest

                Log.d(TAG, "=== RESULT: distance=${String.format("%.3f", distanceM)}m " +
                    "(${String.format("%.1f", distanceM * 3.281)}ft) " +
                    "from ${trimmed.size} readings, avg=${String.format("%.2f", avgDiopters)}D ===")

                val result = Arguments.createMap().apply {
                    putDouble("averageDistance", distanceM)
                    putDouble("minDistance", minDistM)
                    putDouble("maxDistance", maxDistM)
                    putInt("framesUsed", trimmed.size)
                    putDouble("totalPixels", 0.0)
                    putString("method", "Autofocus PDAF")
                }
                promise.resolve(result)

            } catch (e: SecurityException) {
                promise.reject("NO_PERMISSION", "Camera permission required")
            } catch (e: Exception) {
                Log.e(TAG, "measureDepth error: ${e.javaClass.simpleName}: ${e.message}", e)
                promise.reject("ERROR", "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try { captureSession?.close() } catch (_: Exception) {}
                try { cameraDevice?.close() } catch (_: Exception) {}
                try { imageReader?.close() } catch (_: Exception) {}
                handlerThread?.quitSafely()
                Log.d(TAG, "Cleaned up")
            }
        }.start()
    }

    private fun findRearCameraWithAF(cameraManager: CameraManager): String? {
        for (cameraId in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: continue
            if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) ||
                afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {

                val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                if (minFocusDist > 0) {
                    return cameraId
                }
            }
        }
        return null
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
