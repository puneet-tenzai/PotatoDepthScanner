package com.potatodepthscanner

import android.app.Activity
import android.content.Context
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
import kotlin.math.max
import kotlin.math.min

/**
 * Single-shot depth measurement using ARCore raw depth (ToF sensor).
 *
 * Key accuracy improvements:
 * 1. Uses acquireRawDepthImage() — direct ToF sensor data, not computed stereo depth
 * 2. Uses the raw depth confidence image to filter unreliable pixels
 * 3. Only samples the CENTER 30% of the depth image (where you're pointing)
 * 4. Uses MEDIAN instead of mean (robust to outliers)
 * 5. Averages median across multiple frames for stability
 */
class ArCoreDepthModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "ArCoreDepthModule"
        const val TAG = "ArCoreDepth"
        const val MAX_ATTEMPTS = 80        // Max frames to try (~8 seconds)
        const val DEPTH_FRAMES = 5         // Number of good frames to average
        const val CENTER_RATIO = 0.3f      // Use center 30% of image
        const val MIN_CONFIDENCE = 128     // Confidence threshold (0-255)
    }

    override fun getName(): String = NAME

    @ReactMethod
    fun checkDepthSupport(promise: Promise) {
        try {
            val activity: Activity? = reactApplicationContext.currentActivity
            if (activity == null) { promise.resolve(false); return }

            val availability = ArCoreApk.getInstance().checkAvailability(activity as Context)
            if (availability.isTransient || !availability.isSupported) {
                promise.resolve(false); return
            }

            try {
                val tempSession = Session(activity as Context)
                val supported = tempSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                tempSession.close()
                promise.resolve(supported)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking depth support", e)
                promise.resolve(false)
            }
        } catch (e: Exception) {
            promise.resolve(false)
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
                val activity: Activity? = reactApplicationContext.currentActivity
                if (activity == null) {
                    promise.reject("NO_ACTIVITY", "No activity")
                    return@Thread
                }

                // 1. Create EGL context (required by ARCore)
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

                val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                eglContext = EGL14.eglCreateContext(eglDisplay, configs[0]!!, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

                val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
                eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0]!!, surfAttribs, 0)

                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                textureId = textures[0]

                // 2. Create ARCore session with raw depth enabled
                Log.d(TAG, "measureDepth: Creating ARCore session with raw depth...")
                session = Session(activity as Context)
                val config = Config(session)
                config.depthMode = Config.DepthMode.AUTOMATIC
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                session.configure(config)
                session.setCameraTextureName(textureId)
                session.resume()

                Log.d(TAG, "measureDepth: Waiting for ARCore to initialize...")
                Thread.sleep(2000)

                // 3. Collect depth frames
                val frameMedians = mutableListOf<Double>()
                var allMins = Double.MAX_VALUE
                var allMaxs = 0.0
                var attempts = 0
                var totalPixelsUsed: Long = 0

                while (frameMedians.size < DEPTH_FRAMES && attempts < MAX_ATTEMPTS) {
                    attempts++
                    try {
                        val frame = session.update()
                        val camera = frame.camera

                        if (camera.trackingState != TrackingState.TRACKING) {
                            Thread.sleep(100)
                            continue
                        }

                        // Try raw depth first (ToF sensor), fall back to processed depth
                        val result = tryReadRawDepth(frame) ?: tryReadProcessedDepth(frame)

                        if (result != null) {
                            frameMedians.add(result.median)
                            if (result.min < allMins) allMins = result.min
                            if (result.max > allMaxs) allMaxs = result.max
                            totalPixelsUsed += result.pixelCount
                            Log.d(TAG, "measureDepth: Frame ${frameMedians.size} " +
                                "median=${String.format("%.3f", result.median)}m " +
                                "min=${String.format("%.3f", result.min)}m " +
                                "max=${String.format("%.3f", result.max)}m " +
                                "(${result.pixelCount} pixels, ${result.source})")
                        }

                        Thread.sleep(100)
                    } catch (e: Exception) {
                        Log.e(TAG, "measureDepth frame error: ${e.message}")
                        Thread.sleep(200)
                    }
                }

                // 4. Return averaged median across frames
                if (frameMedians.isNotEmpty()) {
                    val avgMedian = frameMedians.sorted()
                        .let { sorted ->
                            // Median of medians for robustness
                            if (sorted.size % 2 == 0) {
                                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
                            } else {
                                sorted[sorted.size / 2]
                            }
                        }

                    Log.d(TAG, "measureDepth: FINAL distance=${String.format("%.3f", avgMedian)}m " +
                        "from ${frameMedians.size} frames")

                    val result = Arguments.createMap().apply {
                        putDouble("averageDistance", avgMedian)
                        putDouble("minDistance", if (allMins == Double.MAX_VALUE) 0.0 else allMins)
                        putDouble("maxDistance", allMaxs)
                        putInt("framesUsed", frameMedians.size)
                        putDouble("totalPixels", totalPixelsUsed.toDouble())
                    }
                    promise.resolve(result)
                } else {
                    promise.reject("NO_DEPTH", "Could not acquire depth data after $attempts attempts")
                }

            } catch (e: CameraNotAvailableException) {
                promise.reject("CAMERA_BUSY", "Camera is busy. Make sure camera preview is paused.")
            } catch (e: Exception) {
                promise.reject("ERROR", "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try { session?.pause() } catch (_: Exception) {}
                try { session?.close() } catch (_: Exception) {}
                if (textureId != -1) {
                    try { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) } catch (_: Exception) {}
                }
                if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    eglSurface?.let { EGL14.eglDestroySurface(eglDisplay, it) }
                    eglContext?.let { EGL14.eglDestroyContext(eglDisplay, it) }
                    EGL14.eglTerminate(eglDisplay)
                }
            }
        }.start()
    }

    data class DepthFrameResult(
        val median: Double,
        val min: Double,
        val max: Double,
        val pixelCount: Int,
        val source: String
    )

    /**
     * Try reading raw depth image (direct from ToF sensor).
     * Returns null if not available.
     */
    private fun tryReadRawDepth(frame: Frame): DepthFrameResult? {
        var depthImage: Image? = null
        var confidenceImage: Image? = null
        try {
            depthImage = frame.acquireRawDepthImage()
            confidenceImage = frame.acquireRawDepthConfidenceImage()
            return processDepthImage(depthImage, confidenceImage, "ToF")
        } catch (_: NotYetAvailableException) {
            return null
        } catch (e: Exception) {
            Log.d(TAG, "Raw depth not available: ${e.message}")
            return null
        } finally {
            depthImage?.close()
            confidenceImage?.close()
        }
    }

    /**
     * Fall back to processed depth image (stereo-based).
     */
    private fun tryReadProcessedDepth(frame: Frame): DepthFrameResult? {
        var depthImage: Image? = null
        try {
            depthImage = frame.acquireDepthImage()
            return processDepthImage(depthImage, null, "stereo")
        } catch (_: NotYetAvailableException) {
            return null
        } catch (e: Exception) {
            Log.d(TAG, "Processed depth not available: ${e.message}")
            return null
        } finally {
            depthImage?.close()
        }
    }

    /**
     * Process a depth image: sample CENTER region only, use confidence filter,
     * return MEDIAN (not mean) of valid pixels.
     */
    private fun processDepthImage(
        depthImage: Image,
        confidenceImage: Image?,
        source: String
    ): DepthFrameResult? {
        val width = depthImage.width
        val height = depthImage.height

        val depthPlane = depthImage.planes[0]
        val depthBuffer: ShortBuffer = depthPlane.buffer.asShortBuffer()
        val depthRowStride = depthPlane.rowStride / 2 // Convert bytes to shorts

        // Confidence buffer (if available)
        val confBuffer = confidenceImage?.planes?.get(0)?.buffer
        val confRowStride = confidenceImage?.planes?.get(0)?.rowStride ?: 0

        // Calculate center region bounds (center 30%)
        val marginX = ((1.0f - CENTER_RATIO) / 2 * width).toInt()
        val marginY = ((1.0f - CENTER_RATIO) / 2 * height).toInt()
        val startX = marginX
        val endX = width - marginX
        val startY = marginY
        val endY = height - marginY

        // Collect all valid center-region depth values
        val depthValues = mutableListOf<Double>()

        for (y in startY until endY) {
            for (x in startX until endX) {
                // Check confidence if available
                if (confBuffer != null) {
                    val confIndex = y * confRowStride + x
                    if (confIndex < confBuffer.limit()) {
                        val confidence = confBuffer.get(confIndex).toInt() and 0xFF
                        if (confidence < MIN_CONFIDENCE) continue // Skip low-confidence pixels
                    }
                }

                val depthIndex = y * depthRowStride + x
                if (depthIndex < depthBuffer.limit()) {
                    val depthMm = depthBuffer.get(depthIndex).toInt() and 0xFFFF
                    // Valid range: 5cm to 5m (focused on close-range accuracy)
                    if (depthMm in 50..5000) {
                        depthValues.add(depthMm / 1000.0)
                    }
                }
            }
        }

        if (depthValues.isEmpty()) return null

        // Sort for median calculation
        depthValues.sort()

        val median = if (depthValues.size % 2 == 0) {
            (depthValues[depthValues.size / 2 - 1] + depthValues[depthValues.size / 2]) / 2.0
        } else {
            depthValues[depthValues.size / 2]
        }

        val minVal = depthValues.first()
        val maxVal = depthValues.last()

        return DepthFrameResult(
            median = median,
            min = minVal,
            max = maxVal,
            pixelCount = depthValues.size,
            source = source
        )
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
