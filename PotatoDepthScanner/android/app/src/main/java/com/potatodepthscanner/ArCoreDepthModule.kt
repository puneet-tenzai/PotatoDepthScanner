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
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import java.nio.ShortBuffer
import kotlin.math.max
import kotlin.math.min

class ArCoreDepthModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "ArCoreDepthModule"
        const val TAG = "ArCoreDepth"
        const val SAMPLE_RADIUS = 3 // 7x7 grid for better averaging
        const val SMOOTHING_FACTOR = 0.3 // Exponential moving average (lower = smoother)
    }

    private var arSession: Session? = null
    private var isRunning = false
    private var depthThread: Thread? = null
    private var smoothedDepth: Double = 0.0

    // EGL resources
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var cameraTextureId: Int = -1

    override fun getName(): String = NAME

    private fun createEglContext() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("Unable to get EGL14 display")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("Unable to initialize EGL14")
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        if (numConfigs[0] == 0) throw RuntimeException("No suitable EGLConfig")

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0]!!, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("Unable to create EGL context")

        val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0]!!, surfaceAttribs, 0)

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("Unable to make EGL context current")
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        Log.d(TAG, "EGL context created, texture: $cameraTextureId")
    }

    private fun destroyEglContext() {
        if (cameraTextureId != -1) {
            try { GLES20.glDeleteTextures(1, intArrayOf(cameraTextureId), 0) } catch (_: Exception) {}
            cameraTextureId = -1
        }
        if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            eglSurface?.let { EGL14.eglDestroySurface(eglDisplay, it) }
            eglContext?.let { EGL14.eglDestroyContext(eglDisplay, it) }
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = null; eglContext = null; eglSurface = null
    }

    @ReactMethod
    fun checkDepthSupport(promise: Promise) {
        try {
            val activity: Activity? = reactApplicationContext.currentActivity
            if (activity == null) { promise.resolve(false); return }

            val availability = ArCoreApk.getInstance().checkAvailability(activity as Context)
            if (availability.isTransient || !availability.isSupported) { promise.resolve(false); return }

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
    fun startDepthSession(promise: Promise) {
        try {
            val activity: Activity? = reactApplicationContext.currentActivity
            if (activity == null) { promise.reject("NO_ACTIVITY", "No current activity"); return }

            val installStatus = ArCoreApk.getInstance().requestInstall(activity as Activity, true)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                promise.reject("INSTALL_REQUESTED", "ARCore installation requested"); return
            }

            arSession = Session(activity as Context)
            val config = Config(arSession!!)
            config.depthMode = Config.DepthMode.AUTOMATIC
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            arSession!!.configure(config)

            isRunning = true
            smoothedDepth = 0.0

            depthThread = Thread {
                try {
                    createEglContext()
                    arSession!!.setCameraTextureName(cameraTextureId)
                    arSession!!.resume()

                    Thread.sleep(2000)

                    var frameCount = 0

                    while (isRunning) {
                        try {
                            val session = arSession ?: break
                            val frame = session.update()
                            frameCount++

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

                                // Sample from CENTER of the image
                                // When pointing phone at ground, center = ground
                                val targetX = width / 2
                                val targetY = height / 2

                                val plane = depthImage.planes[0]
                                val buffer: ShortBuffer = plane.buffer.asShortBuffer()
                                val rowStride = plane.rowStride

                                val rawDepth = sampleAveragedDepth(
                                    buffer, rowStride, width, height, targetX, targetY
                                )

                                if (rawDepth > 0) {
                                    // Apply exponential moving average for stability
                                    smoothedDepth = if (smoothedDepth <= 0) {
                                        rawDepth
                                    } else {
                                        smoothedDepth * (1 - SMOOTHING_FACTOR) + rawDepth * SMOOTHING_FACTOR
                                    }

                                    Log.d(TAG, "Frame $frameCount: raw=${String.format("%.3f", rawDepth)}m smoothed=${String.format("%.3f", smoothedDepth)}m (${width}x${height})")

                                    val params = Arguments.createMap().apply {
                                        putDouble("distance", smoothedDepth)
                                        putDouble("rawDistance", rawDepth)
                                        putInt("depthWidth", width)
                                        putInt("depthHeight", height)
                                        putDouble("confidence", 1.0)
                                    }
                                    sendEvent("onDepthData", params)
                                }
                            } catch (_: NotYetAvailableException) {
                                // Normal for first few seconds
                            } finally {
                                depthImage?.close()
                            }

                            Thread.sleep(150) // ~7 FPS — slightly slower for stability
                        } catch (e: InterruptedException) { break }
                        catch (e: Exception) {
                            Log.e(TAG, "Depth error: ${e.javaClass.simpleName}: ${e.message}", e)
                            Thread.sleep(500)
                        }
                    }
                } catch (e: CameraNotAvailableException) {
                    val p = Arguments.createMap().apply { putString("error", "Camera busy. Wait and retry.") }
                    sendEvent("onDepthError", p)
                } catch (e: Exception) {
                    val p = Arguments.createMap().apply { putString("error", "${e.javaClass.simpleName}: ${e.message ?: "unknown"}") }
                    sendEvent("onDepthError", p)
                } finally {
                    destroyEglContext()
                }
            }
            depthThread?.start()
            promise.resolve(true)

        } catch (e: UnavailableArcoreNotInstalledException) { promise.reject("NOT_INSTALLED", "ARCore is not installed")
        } catch (e: UnavailableDeviceNotCompatibleException) { promise.reject("NOT_COMPATIBLE", "Device not compatible")
        } catch (e: UnavailableSdkTooOldException) { promise.reject("SDK_TOO_OLD", "ARCore SDK too old")
        } catch (e: Exception) { promise.reject("ERROR", "${e.javaClass.simpleName}: ${e.message}") }
    }

    /**
     * Sample a 7x7 grid around the target point and return averaged depth in meters.
     * Uses pixel stride from the image plane for correct indexing.
     */
    private fun sampleAveragedDepth(
        buffer: ShortBuffer,
        rowStride: Int,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int
    ): Double {
        var totalDepth: Long = 0
        var validCount = 0
        val shortsPerRow = rowStride / 2 // rowStride is in bytes, shorts are 2 bytes

        for (dy in -SAMPLE_RADIUS..SAMPLE_RADIUS) {
            for (dx in -SAMPLE_RADIUS..SAMPLE_RADIUS) {
                val px = max(0, min(width - 1, centerX + dx))
                val py = max(0, min(height - 1, centerY + dy))

                val index = py * shortsPerRow + px
                if (index >= 0 && index < buffer.limit()) {
                    val depthMm = buffer.get(index).toInt() and 0xFFFF
                    if (depthMm in 1..8000) { // Valid: 1mm to 8m
                        totalDepth += depthMm
                        validCount++
                    }
                }
            }
        }

        return if (validCount > 0) {
            (totalDepth.toDouble() / validCount) / 1000.0
        } else {
            0.0
        }
    }

    @ReactMethod
    fun stopDepthSession(promise: Promise) {
        try {
            isRunning = false
            depthThread?.interrupt()
            depthThread?.join(3000)
            depthThread = null
            arSession?.pause()
            arSession?.close()
            arSession = null
            smoothedDepth = 0.0
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERROR", "Failed to stop: ${e.message}")
        }
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        try {
            reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
        } catch (_: Exception) {}
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
