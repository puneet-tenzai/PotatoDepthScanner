package com.potatodepthscanner

import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import com.facebook.react.bridge.*
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import java.nio.ShortBuffer

/**
 * Depth measurement using ARCore (Samsung S24 has no ToF sensor).
 *
 * Key improvements for reliability:
 * - Retries session creation up to 3 times if camera is busy
 * - Waits up to 10 seconds for tracking
 * - Center 40% region, 25th percentile (nearest surface)
 * - Returns detailed error messages to JS for debugging
 */
class ArCoreDepthModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "ArCoreDepthModule"
        const val TAG = "DepthSensor"
        const val CENTER_RATIO = 0.4f
        const val MAX_SESSION_RETRIES = 3
        const val SESSION_RETRY_DELAY_MS = 2000L
    }

    override fun getName(): String = NAME

    @ReactMethod
    fun checkDepthSupport(promise: Promise) {
        try {
            val activity = reactApplicationContext.currentActivity
            if (activity == null) { promise.resolve(false); return }

            val availability = ArCoreApk.getInstance().checkAvailability(activity as Context)
            if (!availability.isSupported) { promise.resolve(false); return }

            try {
                val tempSession = Session(activity)
                val supported = tempSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                tempSession.close()
                promise.resolve(supported)
            } catch (e: Exception) {
                Log.e(TAG, "checkDepthSupport error: ${e.message}")
                promise.resolve(false)
            }
        } catch (e: Exception) {
            promise.resolve(false)
        }
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
                val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val depth16 = try { streamMap?.getOutputSizes(ImageFormat.DEPTH16) } catch (_: Exception) { null }

                sb.appendLine("Camera $cameraId ($facing): depth_cap=$hasDepth, DEPTH16=${depth16?.map { "${it.width}x${it.height}" } ?: "NONE"}")
            }

            Log.d(TAG, "Cameras:\n$sb")
            promise.resolve(sb.toString())
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun measureDepth(promise: Promise) {
        Thread {
            var eglDisplay: EGLDisplay? = null
            var eglContext: EGLContext? = null
            var eglSurface: EGLSurface? = null
            var session: Session? = null
            var textureId = -1

            try {
                val activity = reactApplicationContext.currentActivity
                if (activity == null) {
                    promise.reject("ERROR", "No activity available")
                    return@Thread
                }

                Log.d(TAG, "=== measureDepth START ===")

                // 1. Setup EGL context (required by ARCore even without rendering)
                Log.d(TAG, "Setting up EGL...")
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
                Log.d(TAG, "EGL ready, texture=$textureId")

                // 2. Create ARCore session with RETRIES
                Log.d(TAG, "Creating ARCore session (with retries)...")
                var lastError: Exception? = null

                for (attempt in 1..MAX_SESSION_RETRIES) {
                    try {
                        Log.d(TAG, "Session attempt $attempt/$MAX_SESSION_RETRIES")
                        session = Session(activity as Context)
                        val config = Config(session!!)
                        config.depthMode = Config.DepthMode.AUTOMATIC
                        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        session!!.configure(config)
                        session!!.setCameraTextureName(textureId)
                        session!!.resume()
                        Log.d(TAG, "Session resumed successfully on attempt $attempt")
                        lastError = null
                        break
                    } catch (e: CameraNotAvailableException) {
                        Log.w(TAG, "Camera busy on attempt $attempt, waiting ${SESSION_RETRY_DELAY_MS}ms...")
                        lastError = e
                        session?.close()
                        session = null
                        if (attempt < MAX_SESSION_RETRIES) {
                            Thread.sleep(SESSION_RETRY_DELAY_MS)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Session error on attempt $attempt: ${e.javaClass.simpleName}: ${e.message}")
                        lastError = e
                        session?.close()
                        session = null
                        if (attempt < MAX_SESSION_RETRIES) {
                            Thread.sleep(SESSION_RETRY_DELAY_MS)
                        }
                    }
                }

                if (session == null) {
                    val errorMsg = "Camera not available after $MAX_SESSION_RETRIES attempts: ${lastError?.message}"
                    Log.e(TAG, errorMsg)
                    promise.reject("CAMERA_BUSY", errorMsg)
                    return@Thread
                }

                // 3. Wait for tracking (up to 10 seconds)
                Log.d(TAG, "Waiting for ARCore tracking...")
                var trackingReady = false
                for (i in 1..100) {
                    try {
                        val frame = session.update()
                        if (frame.camera.trackingState == TrackingState.TRACKING) {
                            Log.d(TAG, "Tracking active after ${i * 100}ms")
                            trackingReady = true
                            break
                        }
                    } catch (_: Exception) {}
                    Thread.sleep(100)
                }

                if (!trackingReady) {
                    Log.w(TAG, "Tracking never started, trying depth anyway...")
                }

                // 4. Let tracking stabilize
                Log.d(TAG, "Stabilizing for 3 seconds...")
                Thread.sleep(3000)

                // 5. Collect depth frames
                val frameResults = mutableListOf<FrameResult>()
                var attempts = 0
                val maxAttempts = 120
                val targetFrames = 5

                while (frameResults.size < targetFrames && attempts < maxAttempts) {
                    attempts++
                    try {
                        val frame = session.update()
                        if (frame.camera.trackingState != TrackingState.TRACKING) {
                            Thread.sleep(50)
                            continue
                        }

                        // Try raw depth first (may use hardware sensors)
                        var result = readDepthImage(frame, raw = true)
                        val method = if (result != null) "raw" else "stereo"
                        if (result == null) {
                            result = readDepthImage(frame, raw = false)
                        }

                        if (result != null) {
                            frameResults.add(result)
                            Log.d(TAG, "Depth frame ${frameResults.size} ($method): " +
                                "p25=${String.format("%.3f", result.p25)}m " +
                                "median=${String.format("%.3f", result.median)}m " +
                                "min=${String.format("%.3f", result.min)}m " +
                                "(${result.pixelCount}px)")
                        }

                        Thread.sleep(100)
                    } catch (_: NotYetAvailableException) {
                        Thread.sleep(100)
                    } catch (e: Exception) {
                        Log.w(TAG, "Frame error: ${e.javaClass.simpleName}")
                        Thread.sleep(200)
                    }
                }

                Log.d(TAG, "Got ${frameResults.size} frames in $attempts attempts")

                if (frameResults.isEmpty()) {
                    promise.reject("NO_DEPTH", "No depth data after $attempts attempts. " +
                        "TrackingReady=$trackingReady. Make sure the camera can see textured surfaces.")
                    return@Thread
                }

                // 6. Median of frame p25 values (closest surface)
                val sortedP25 = frameResults.map { it.p25 }.sorted()
                val finalDist = sortedP25[sortedP25.size / 2]
                val sortedMedians = frameResults.map { it.median }.sorted()
                val medianDist = sortedMedians[sortedMedians.size / 2]
                val overallMin = frameResults.minOf { it.min }
                val overallMax = frameResults.maxOf { it.max }

                Log.d(TAG, "=== RESULT: p25=${String.format("%.3f", finalDist)}m " +
                    "median=${String.format("%.3f", medianDist)}m " +
                    "min=${String.format("%.3f", overallMin)}m ===")

                val resultMap = Arguments.createMap().apply {
                    putDouble("averageDistance", finalDist)
                    putDouble("medianDistance", medianDist)
                    putDouble("minDistance", overallMin)
                    putDouble("maxDistance", overallMax)
                    putInt("framesUsed", frameResults.size)
                    putDouble("totalPixels", frameResults.sumOf { it.pixelCount.toLong() }.toDouble())
                    putString("method", "ARCore")
                }
                promise.resolve(resultMap)

            } catch (e: Exception) {
                Log.e(TAG, "measureDepth FATAL: ${e.javaClass.simpleName}: ${e.message}", e)
                promise.reject("ERROR", "${e.javaClass.simpleName}: ${e.message}")
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
                Log.d(TAG, "=== measureDepth END ===")
            }
        }.start()
    }

    data class FrameResult(
        val p25: Double,    // 25th percentile (nearest surface)
        val median: Double,
        val min: Double,
        val max: Double,
        val pixelCount: Int
    )

    private fun readDepthImage(frame: Frame, raw: Boolean): FrameResult? {
        var depthImage: Image? = null
        var confImage: Image? = null
        try {
            if (raw) {
                depthImage = frame.acquireRawDepthImage()
                confImage = try { frame.acquireRawDepthConfidenceImage() } catch (_: Exception) { null }
            } else {
                depthImage = frame.acquireDepthImage()
            }

            val width = depthImage.width
            val height = depthImage.height
            val plane = depthImage.planes[0]
            val buffer: ShortBuffer = plane.buffer.asShortBuffer()
            val rowStride = plane.rowStride / 2

            // Center region
            val marginX = ((1.0f - CENTER_RATIO) / 2 * width).toInt()
            val marginY = ((1.0f - CENTER_RATIO) / 2 * height).toInt()

            val depthValues = mutableListOf<Double>()

            for (y in marginY until (height - marginY)) {
                for (x in marginX until (width - marginX)) {
                    val index = y * rowStride + x
                    if (index < 0 || index >= buffer.limit()) continue

                    val depthMm = buffer.get(index).toInt() and 0xFFFF

                    // ARCore depth images: full 16-bit value is depth in mm
                    if (depthMm in 30..5000) { // 3cm to 5m valid range
                        depthValues.add(depthMm / 1000.0)
                    }
                }
            }

            if (depthValues.size < 10) return null // Too few pixels

            depthValues.sort()

            val p25 = depthValues[depthValues.size / 4]
            val median = depthValues[depthValues.size / 2]

            return FrameResult(
                p25 = p25,
                median = median,
                min = depthValues.first(),
                max = depthValues.last(),
                pixelCount = depthValues.size
            )
        } catch (_: NotYetAvailableException) {
            return null
        } catch (e: Exception) {
            // Only log if not a common "not ready" error
            if (e !is DeadlineExceededException) {
                Log.d(TAG, "readDepth(raw=$raw): ${e.javaClass.simpleName}")
            }
            return null
        } finally {
            depthImage?.close()
            confImage?.close()
        }
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
