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
 * Distance measurement using Camera2 autofocus PDAF.
 *
 * Uses piecewise linear calibration curve built from real-world test data
 * on Samsung Galaxy S24. Fast: 2 AF cycles, ~5 seconds total.
 */
class ArCoreDepthModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "ArCoreDepthModule"
        const val TAG = "DepthSensor"
        const val AF_CYCLES = 2            // Reduced from 3 for speed
        const val READINGS_PER_CYCLE = 8   // Reduced from 10 for speed
        const val MAX_DISTANCE_M = 1.52    // 5 feet

        /**
         * Calibration data from real-world measurements on Samsung S24.
         * Format: Pair(raw_diopter_distance_meters, actual_distance_meters)
         *
         * Raw values derived from user test (displayed / 1.6 calibration factor):
         *   Actual 0.5ft (0.152m) → measured showed 1.1ft (0.335m) → raw = 0.209m
         *   Actual 1.0ft (0.305m) → measured showed 1.4ft (0.427m) → raw = 0.267m
         *   Actual 2.0ft (0.610m) → measured showed 2.2ft (0.670m) → raw = 0.419m
         *   Actual 3.0ft (0.914m) → measured showed 3.2ft (0.975m) → raw = 0.609m
         *   Actual 4.0ft (1.219m) → measured showed 4.2ft (1.280m) → raw = 0.800m
         *   Actual 5.0ft (1.524m) → measured showed 4.6ft (1.402m) → raw = 0.876m
         */
        val CALIBRATION_TABLE = listOf(
            Pair(0.209, 0.152),   // 0.5 ft
            Pair(0.267, 0.305),   // 1.0 ft
            Pair(0.419, 0.610),   // 2.0 ft
            Pair(0.609, 0.914),   // 3.0 ft
            Pair(0.800, 1.219),   // 4.0 ft
            Pair(0.876, 1.524),   // 5.0 ft
        )
    }

    override fun getName(): String = NAME

    @ReactMethod
    fun checkDepthSupport(promise: Promise) {
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
                val minFD = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                sb.appendLine("Camera $cameraId ($facing): minFocusDist=${String.format("%.1f", minFD)}D")
            }
            promise.resolve(sb.toString())
        } catch (e: Exception) { promise.reject("ERROR", e.message) }
    }

    /**
     * Apply piecewise linear calibration using the lookup table.
     * Interpolates between known calibration points.
     */
    private fun calibrate(rawDistanceM: Double): Double {
        val table = CALIBRATION_TABLE

        // Below minimum calibration point — extrapolate using first segment
        if (rawDistanceM <= table.first().first) {
            val (r0, a0) = table[0]
            val (r1, a1) = table[1]
            val slope = (a1 - a0) / (r1 - r0)
            return a0 + slope * (rawDistanceM - r0)
        }

        // Above maximum calibration point — extrapolate using last segment
        if (rawDistanceM >= table.last().first) {
            val (r0, a0) = table[table.size - 2]
            val (r1, a1) = table[table.size - 1]
            val slope = (a1 - a0) / (r1 - r0)
            return a1 + slope * (rawDistanceM - r1)
        }

        // Interpolate between two surrounding points
        for (i in 0 until table.size - 1) {
            val (r0, a0) = table[i]
            val (r1, a1) = table[i + 1]
            if (rawDistanceM in r0..r1) {
                val t = (rawDistanceM - r0) / (r1 - r0)
                return a0 + t * (a1 - a0)
            }
        }

        // Fallback (shouldn't reach here)
        return rawDistanceM
    }

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
                Log.d(TAG, "=== measureDepth START ===")

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
                    override fun onOpened(cam: CameraDevice) { cameraRef.set(cam); cameraLatch.countDown() }
                    override fun onDisconnected(cam: CameraDevice) { cameraError.set("disconnected"); cameraLatch.countDown() }
                    override fun onError(cam: CameraDevice, err: Int) { cameraError.set("error $err"); cameraLatch.countDown() }
                }, handler)

                if (!cameraLatch.await(8, TimeUnit.SECONDS)) {
                    promise.reject("TIMEOUT", "Camera open timeout"); return@Thread
                }
                cameraDevice = cameraRef.get()
                if (cameraDevice == null) {
                    promise.reject("ERROR", "Camera: ${cameraError.get()}"); return@Thread
                }

                // Session
                val sessionLatch = CountDownLatch(1)
                val sessionRef = AtomicReference<CameraCaptureSession?>(null)

                cameraDevice.createCaptureSession(
                    listOf(imageReader.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { sessionRef.set(s); sessionLatch.countDown() }
                        override fun onConfigureFailed(s: CameraCaptureSession) { sessionLatch.countDown() }
                    }, handler
                )

                if (!sessionLatch.await(8, TimeUnit.SECONDS)) {
                    promise.reject("TIMEOUT", "Session timeout"); return@Thread
                }
                captureSession = sessionRef.get()
                if (captureSession == null) {
                    promise.reject("ERROR", "Session failed"); return@Thread
                }

                // === FAST AF CYCLES ===
                val allReadings = mutableListOf<Float>()

                for (cycle in 1..AF_CYCLES) {
                    Log.d(TAG, "--- AF Cycle $cycle ---")

                    // Trigger AF
                    val triggerReq = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(imageReader.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                    }.build()
                    captureSession.capture(triggerReq, null, handler)

                    // Wait for AF lock (reduced from 1.5s)
                    Thread.sleep(1000)

                    // Read distances
                    val cycleReadings = mutableListOf<Float>()
                    val cycleLatch = CountDownLatch(READINGS_PER_CYCLE)

                    val readReq = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(imageReader.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    }.build()

                    captureSession.setRepeatingRequest(readReq, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                        ) {
                            val dist = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: return
                            if (dist > 0) {
                                synchronized(cycleReadings) {
                                    if (cycleReadings.size < READINGS_PER_CYCLE) {
                                        cycleReadings.add(dist)
                                        val rawM = 1.0 / dist
                                        val calM = calibrate(rawM)
                                        Log.d(TAG, "  [${cycleReadings.size}] ${String.format("%.3f", dist)}D " +
                                            "raw=${String.format("%.3f", rawM)}m " +
                                            "cal=${String.format("%.3f", calM)}m " +
                                            "(${String.format("%.1f", calM * 3.281)}ft)")
                                        cycleLatch.countDown()
                                    }
                                }
                            }
                        }
                    }, handler)

                    cycleLatch.await(5, TimeUnit.SECONDS)
                    try { captureSession.stopRepeating() } catch (_: Exception) {}

                    synchronized(cycleReadings) { allReadings.addAll(cycleReadings) }

                    // Cancel AF for next cycle
                    if (cycle < AF_CYCLES) {
                        val cancelReq = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(imageReader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                        }.build()
                        captureSession.capture(cancelReq, null, handler)
                        Thread.sleep(300)
                    }
                }

                if (allReadings.isEmpty()) {
                    promise.reject("NO_FOCUS", "Could not read focus distance")
                    return@Thread
                }

                // Process readings
                allReadings.sort()

                // Trim outliers (keep middle 60%)
                val trimCount = allReadings.size / 5
                val trimmed = if (allReadings.size > 5) {
                    allReadings.subList(trimCount, allReadings.size - trimCount)
                } else { allReadings }

                // Average raw diopters → raw distance → calibrate
                val avgDiopters = trimmed.map { it.toDouble() }.average()
                val rawDistanceM = 1.0 / avgDiopters
                val calibratedDistanceM = calibrate(rawDistanceM)

                // Min/max calibrated
                val maxDiopters = trimmed.first().toDouble()
                val minDiopters = trimmed.last().toDouble()
                val minDistM = calibrate(1.0 / minDiopters)
                val maxDistM = calibrate(1.0 / maxDiopters)

                val tooFar = calibratedDistanceM > MAX_DISTANCE_M

                Log.d(TAG, "=== RESULT: raw=${String.format("%.3f", rawDistanceM)}m " +
                    "calibrated=${String.format("%.3f", calibratedDistanceM)}m " +
                    "(${String.format("%.1f", calibratedDistanceM * 3.281)}ft) " +
                    "tooFar=$tooFar ===")

                val result = Arguments.createMap().apply {
                    putDouble("averageDistance", calibratedDistanceM)
                    putDouble("rawDistance", rawDistanceM)
                    putDouble("minDistance", minDistM)
                    putDouble("maxDistance", maxDistM)
                    putInt("framesUsed", trimmed.size)
                    putDouble("totalPixels", allReadings.size.toDouble())
                    putString("method", "Autofocus PDAF")
                    putBoolean("tooFar", tooFar)
                }
                promise.resolve(result)

            } catch (e: SecurityException) {
                promise.reject("NO_PERMISSION", "Camera permission required")
            } catch (e: Exception) {
                Log.e(TAG, "measureDepth: ${e.javaClass.simpleName}: ${e.message}", e)
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
                val minFD = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                if (minFD > 0) return cameraId
            }
        }
        return null
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
