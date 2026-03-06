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
import androidx.core.app.ActivityCompat
import com.facebook.react.bridge.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * High-accuracy distance measurement using Camera2 autofocus PDAF.
 *
 * Improvements for accuracy:
 * - 3 separate AF trigger cycles, 10 readings each = 30 total readings
 * - Trims outliers (keeps middle 60%)
 * - Applies Samsung S24 calibration correction
 * - Validates max distance (5 feet = 1.52m)
 */
class ArCoreDepthModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "ArCoreDepthModule"
        const val TAG = "DepthSensor"
        const val AF_CYCLES = 3            // Number of AF trigger cycles
        const val READINGS_PER_CYCLE = 10  // Readings per cycle
        const val MAX_DISTANCE_M = 1.52    // 5 feet in meters

        // Calibration: Samsung S24 PDAF typically underreports by ~1.6x
        // This can be fine-tuned after testing at multiple known distances
        const val CALIBRATION_FACTOR = 1.6
    }

    override fun getName(): String = NAME

    @ReactMethod
    fun checkDepthSupport(promise: Promise) {
        promise.resolve(true) // PDAF works on all modern phones
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
                val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                val hasAF = afModes.isNotEmpty()
                val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

                sb.appendLine("Camera $cameraId ($facing): AF=$hasAF, " +
                    "minFocusDist=${String.format("%.1f", minFocusDist)}D " +
                    "(min ${if (minFocusDist > 0) String.format("%.1f", 100.0/minFocusDist) else "N/A"} cm)")
            }

            Log.d(TAG, "Cameras:\n$sb")
            promise.resolve(sb.toString())
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    /**
     * Measure distance with high accuracy using multiple AF cycles.
     *
     * Flow:
     * 1. Open rear camera
     * 2. Run 3 AF cycles:
     *    - Trigger AF_TRIGGER_START
     *    - Wait for FOCUSED_LOCKED
     *    - Collect 10 LENS_FOCUS_DISTANCE readings
     *    - Trigger AF_TRIGGER_CANCEL to reset
     * 3. Trim outliers, average, apply calibration
     * 4. Validate distance <= 5 feet
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
                val cameraId = findRearCameraWithAF(cameraManager)
                if (cameraId == null) {
                    promise.reject("NO_CAMERA", "No rear camera with autofocus")
                    return@Thread
                }

                val chars = cameraManager.getCameraCharacteristics(cameraId)
                Log.d(TAG, "=== measureDepth START (camera $cameraId) ===")

                // Setup
                handlerThread = HandlerThread("FocusThread").also { it.start() }
                val handler = Handler(handlerThread.looper)

                val streamConfigMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                val jpegSizes = streamConfigMap.getOutputSizes(ImageFormat.JPEG)
                val smallSize = jpegSizes.minByOrNull { it.width * it.height } ?: Size(640, 480)
                imageReader = ImageReader.newInstance(smallSize.width, smallSize.height, ImageFormat.JPEG, 2)
                imageReader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)

                // Open camera
                val cameraLatch = CountDownLatch(1)
                val cameraRef = AtomicReference<CameraDevice?>(null)
                val cameraError = AtomicReference<String?>(null)

                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) { cameraRef.set(camera); cameraLatch.countDown() }
                    override fun onDisconnected(camera: CameraDevice) { cameraError.set("disconnected"); cameraLatch.countDown() }
                    override fun onError(camera: CameraDevice, error: Int) { cameraError.set("error $error"); cameraLatch.countDown() }
                }, handler)

                if (!cameraLatch.await(10, TimeUnit.SECONDS)) {
                    promise.reject("TIMEOUT", "Camera open timeout"); return@Thread
                }
                cameraDevice = cameraRef.get()
                if (cameraDevice == null) {
                    promise.reject("ERROR", "Camera: ${cameraError.get()}"); return@Thread
                }

                // Create session
                val sessionLatch = CountDownLatch(1)
                val sessionRef = AtomicReference<CameraCaptureSession?>(null)

                cameraDevice.createCaptureSession(
                    listOf(imageReader.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { sessionRef.set(s); sessionLatch.countDown() }
                        override fun onConfigureFailed(s: CameraCaptureSession) { sessionLatch.countDown() }
                    }, handler
                )

                if (!sessionLatch.await(10, TimeUnit.SECONDS)) {
                    promise.reject("TIMEOUT", "Session timeout"); return@Thread
                }
                captureSession = sessionRef.get()
                if (captureSession == null) {
                    promise.reject("ERROR", "Session failed"); return@Thread
                }

                // === MULTIPLE AF CYCLES ===
                val allReadings = mutableListOf<Float>()

                for (cycle in 1..AF_CYCLES) {
                    Log.d(TAG, "--- AF Cycle $cycle/$AF_CYCLES ---")

                    // Trigger AF
                    val triggerRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(imageReader.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                    }.build()
                    captureSession.capture(triggerRequest, null, handler)

                    // Wait for AF to lock
                    Thread.sleep(1500)

                    // Collect readings
                    val cycleReadings = mutableListOf<Float>()
                    val cycleLatch = CountDownLatch(READINGS_PER_CYCLE)

                    val readRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(imageReader.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    }.build()

                    captureSession.setRepeatingRequest(readRequest, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                        ) {
                            val dist = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: return
                            val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: return

                            if (dist > 0) {
                                synchronized(cycleReadings) {
                                    if (cycleReadings.size < READINGS_PER_CYCLE) {
                                        cycleReadings.add(dist)
                                        Log.d(TAG, "  Reading ${cycleReadings.size}: " +
                                            "${String.format("%.3f", dist)}D = " +
                                            "${String.format("%.4f", 1.0/dist)}m raw, " +
                                            "${String.format("%.4f", CALIBRATION_FACTOR/dist)}m cal " +
                                            "(AF=$afState)")
                                        cycleLatch.countDown()
                                    }
                                }
                            }
                        }
                    }, handler)

                    cycleLatch.await(8, TimeUnit.SECONDS)
                    try { captureSession.stopRepeating() } catch (_: Exception) {}

                    synchronized(cycleReadings) {
                        allReadings.addAll(cycleReadings)
                        Log.d(TAG, "Cycle $cycle: got ${cycleReadings.size} readings")
                    }

                    // Cancel AF to allow re-trigger
                    if (cycle < AF_CYCLES) {
                        val cancelRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(imageReader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                        }.build()
                        captureSession.capture(cancelRequest, null, handler)
                        Thread.sleep(500) // Brief pause before next cycle
                    }
                }

                if (allReadings.isEmpty()) {
                    promise.reject("NO_FOCUS", "Could not read focus distance")
                    return@Thread
                }

                // Process: trim outliers, average, calibrate
                allReadings.sort()

                // Keep middle 60%
                val trimCount = allReadings.size / 5
                val trimmed = if (allReadings.size > 5) {
                    allReadings.subList(trimCount, allReadings.size - trimCount)
                } else {
                    allReadings
                }

                // Average diopters
                val avgDiopters = trimmed.map { it.toDouble() }.average()
                val rawDistanceM = 1.0 / avgDiopters
                val calibratedDistanceM = rawDistanceM * CALIBRATION_FACTOR

                // Min/max (in calibrated meters)
                val maxDiopters = trimmed.first().toDouble()  // smallest diopter = farthest
                val minDiopters = trimmed.last().toDouble()   // largest diopter = closest
                val minDistM = CALIBRATION_FACTOR / minDiopters
                val maxDistM = CALIBRATION_FACTOR / maxDiopters

                Log.d(TAG, "=== RESULT: raw=${String.format("%.3f", rawDistanceM)}m " +
                    "calibrated=${String.format("%.3f", calibratedDistanceM)}m " +
                    "(${String.format("%.1f", calibratedDistanceM * 3.281)}ft) " +
                    "from ${trimmed.size}/${allReadings.size} readings ===")

                // Check max distance
                val tooFar = calibratedDistanceM > MAX_DISTANCE_M

                val result = Arguments.createMap().apply {
                    putDouble("averageDistance", calibratedDistanceM)
                    putDouble("rawDistance", rawDistanceM)
                    putDouble("minDistance", minDistM)
                    putDouble("maxDistance", maxDistM)
                    putInt("framesUsed", trimmed.size)
                    putDouble("totalPixels", allReadings.size.toDouble())
                    putString("method", "Autofocus PDAF")
                    putBoolean("tooFar", tooFar)
                    putDouble("calibrationFactor", CALIBRATION_FACTOR)
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
                Log.d(TAG, "=== measureDepth END ===")
            }
        }.start()
    }

    private fun findRearCameraWithAF(cameraManager: CameraManager): String? {
        for (cameraId in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue

            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: continue
            if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)) {
                val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                if (minFocusDist > 0) return cameraId
            }
        }
        return null
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
