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
 * Single-shot depth measurement module.
 * When measureDepth() is called:
 *   1. Creates EGL context + ARCore session
 *   2. Waits for tracking + depth data
 *   3. Reads ALL pixels from depth image, averages them
 *   4. Returns the average distance in meters
 *   5. Cleans up everything
 */
class ArCoreDepthModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "ArCoreDepthModule"
        const val TAG = "ArCoreDepth"
        const val MAX_ATTEMPTS = 60     // Max frames to try (about 6 seconds at ~10fps)
        const val DEPTH_ATTEMPTS = 10   // Number of successful depth frames to average
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

    /**
     * Perform a single-shot depth measurement.
     * Pauses camera externally first, then:
     *   1. Opens ARCore session
     *   2. Waits for tracking
     *   3. Collects multiple depth frames and averages ALL pixels
     *   4. Returns result as a map: { averageDistance, minDistance, maxDistance, pixelCount }
     */
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

                Log.d(TAG, "measureDepth: Setting up EGL context...")

                // 1. Create EGL context
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

                // 2. Create ARCore session
                Log.d(TAG, "measureDepth: Creating ARCore session...")
                session = Session(activity as Context)
                val config = Config(session)
                config.depthMode = Config.DepthMode.AUTOMATIC
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                session.configure(config)
                session.setCameraTextureName(textureId)
                session.resume()

                Log.d(TAG, "measureDepth: Waiting for tracking...")
                Thread.sleep(1500) // Let ARCore initialize

                // 3. Collect depth data from multiple frames
                var totalDistance = 0.0
                var totalPixels: Long = 0
                var minDist = Double.MAX_VALUE
                var maxDist = 0.0
                var successfulFrames = 0
                var attempts = 0

                while (successfulFrames < DEPTH_ATTEMPTS && attempts < MAX_ATTEMPTS) {
                    attempts++
                    try {
                        val frame = session.update()
                        val camera = frame.camera

                        if (camera.trackingState != TrackingState.TRACKING) {
                            Thread.sleep(100)
                            continue
                        }

                        var depthImage: Image? = null
                        try {
                            depthImage = frame.acquireDepthImage()
                            val width = depthImage.width
                            val height = depthImage.height
                            val plane = depthImage.planes[0]
                            val buffer: ShortBuffer = plane.buffer.asShortBuffer()
                            val shortsPerRow = plane.rowStride / 2

                            // Read ALL pixels
                            var frameTotal = 0.0
                            var frameCount = 0
                            var frameMin = Double.MAX_VALUE
                            var frameMax = 0.0

                            for (y in 0 until height) {
                                for (x in 0 until width) {
                                    val index = y * shortsPerRow + x
                                    if (index < buffer.limit()) {
                                        val depthMm = buffer.get(index).toInt() and 0xFFFF
                                        if (depthMm in 100..8000) { // 10cm to 8m valid range
                                            val depthM = depthMm / 1000.0
                                            frameTotal += depthM
                                            frameCount++
                                            if (depthM < frameMin) frameMin = depthM
                                            if (depthM > frameMax) frameMax = depthM
                                        }
                                    }
                                }
                            }

                            if (frameCount > 0) {
                                totalDistance += frameTotal / frameCount
                                totalPixels += frameCount
                                if (frameMin < minDist) minDist = frameMin
                                if (frameMax > maxDist) maxDist = frameMax
                                successfulFrames++
                                Log.d(TAG, "measureDepth: Frame $successfulFrames avg=${String.format("%.3f", frameTotal/frameCount)}m ($frameCount pixels)")
                            }
                        } catch (_: NotYetAvailableException) {
                            // Normal
                        } finally {
                            depthImage?.close()
                        }

                        Thread.sleep(100)
                    } catch (e: Exception) {
                        Log.e(TAG, "measureDepth frame error: ${e.message}")
                        Thread.sleep(200)
                    }
                }

                // 4. Return results
                if (successfulFrames > 0) {
                    val avgDistance = totalDistance / successfulFrames
                    Log.d(TAG, "measureDepth: RESULT avg=${String.format("%.3f", avgDistance)}m from $successfulFrames frames")

                    val result = Arguments.createMap().apply {
                        putDouble("averageDistance", avgDistance)
                        putDouble("minDistance", if (minDist == Double.MAX_VALUE) 0.0 else minDist)
                        putDouble("maxDistance", maxDist)
                        putInt("framesUsed", successfulFrames)
                        putDouble("totalPixels", totalPixels.toDouble())
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
                // 5. Clean up
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
                Log.d(TAG, "measureDepth: Cleaned up")
            }
        }.start()
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
