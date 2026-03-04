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
        const val SAMPLE_RADIUS = 2
    }

    private var arSession: Session? = null
    private var isRunning = false
    private var depthThread: Thread? = null

    // EGL resources for offscreen GL context
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var cameraTextureId: Int = -1

    override fun getName(): String = NAME

    /**
     * Creates a minimal offscreen EGL context so ARCore can use OpenGL.
     * ARCore requires a valid GL context for session.update() and depth acquisition.
     */
    private fun createEglContext() {
        // 1. Get default display
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("Unable to get EGL14 display")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("Unable to initialize EGL14")
        }

        // 2. Choose config
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

        if (numConfigs[0] == 0) {
            throw RuntimeException("Unable to find a suitable EGLConfig")
        }

        val eglConfig = configs[0]!!

        // 3. Create context
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Unable to create EGL context")
        }

        // 4. Create 1x1 pbuffer surface (offscreen)
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("Unable to create EGL surface")
        }

        // 5. Make current
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("Unable to make EGL context current")
        }

        // 6. Create a GL texture for ARCore camera
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]

        Log.d(TAG, "EGL context created, camera texture ID: $cameraTextureId")
    }

    /**
     * Clean up EGL resources
     */
    private fun destroyEglContext() {
        if (cameraTextureId != -1) {
            // Can only delete if context is current
            try {
                GLES20.glDeleteTextures(1, intArrayOf(cameraTextureId), 0)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete GL texture", e)
            }
            cameraTextureId = -1
        }
        if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = null
        eglContext = null
        eglSurface = null
        Log.d(TAG, "EGL context destroyed")
    }

    @ReactMethod
    fun checkDepthSupport(promise: Promise) {
        try {
            val activity: Activity? = reactApplicationContext.currentActivity
            if (activity == null) {
                promise.resolve(false)
                return
            }

            val availability = ArCoreApk.getInstance().checkAvailability(activity as Context)
            if (availability.isTransient) {
                promise.resolve(false)
                return
            }

            if (!availability.isSupported) {
                promise.resolve(false)
                return
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
            Log.e(TAG, "Error checking ARCore availability", e)
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun startDepthSession(promise: Promise) {
        try {
            val activity: Activity? = reactApplicationContext.currentActivity
            if (activity == null) {
                promise.reject("NO_ACTIVITY", "No current activity")
                return
            }

            val installStatus = ArCoreApk.getInstance().requestInstall(activity as Activity, true)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                promise.reject("INSTALL_REQUESTED", "ARCore installation requested")
                return
            }

            Log.d(TAG, "Creating ARCore session...")

            arSession = Session(activity as Context)
            val config = Config(arSession!!)
            config.depthMode = Config.DepthMode.AUTOMATIC
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            arSession!!.configure(config)

            isRunning = true

            // Start depth thread — EGL context and session resume happen on this thread
            depthThread = Thread {
                try {
                    // Create EGL context on this thread (must be on the same thread that calls session.update)
                    Log.d(TAG, "Creating EGL context on depth thread...")
                    createEglContext()

                    // Set the camera texture name for ARCore
                    arSession!!.setCameraTextureName(cameraTextureId)

                    // Resume session (camera opens here)
                    Log.d(TAG, "Resuming ARCore session...")
                    arSession!!.resume()
                    Log.d(TAG, "ARCore session resumed successfully")

                    // Give ARCore time to acquire first frames and start tracking
                    Thread.sleep(2000)

                    var frameCount = 0
                    var successCount = 0

                    while (isRunning) {
                        try {
                            val session = arSession ?: break
                            val frame = session.update()
                            frameCount++

                            // Check tracking state
                            val camera = frame.camera
                            if (camera.trackingState != TrackingState.TRACKING) {
                                if (frameCount % 20 == 0) {
                                    Log.d(TAG, "Frame $frameCount: Tracking state = ${camera.trackingState}")
                                }
                                Thread.sleep(100)
                                continue
                            }

                            // Try to acquire depth image
                            var depthImage: Image? = null
                            try {
                                depthImage = frame.acquireDepthImage()

                                val width = depthImage.width
                                val height = depthImage.height

                                // Sample at bottom-center for ground distance
                                val targetX = width / 2
                                val targetY = (height * 0.85).toInt()

                                val plane = depthImage.planes[0]
                                val buffer: ShortBuffer = plane.buffer.asShortBuffer()
                                val rowStride = plane.rowStride

                                val depthMeters = sampleAveragedDepth(
                                    buffer, rowStride, width, height, targetX, targetY
                                )

                                if (depthMeters > 0) {
                                    successCount++
                                    val params = Arguments.createMap().apply {
                                        putDouble("distance", depthMeters)
                                        putInt("depthWidth", width)
                                        putInt("depthHeight", height)
                                        putDouble("confidence", 1.0)
                                    }
                                    sendEvent("onDepthData", params)
                                }
                            } catch (e: NotYetAvailableException) {
                                // Normal during first few seconds
                                if (frameCount % 30 == 0) {
                                    Log.d(TAG, "Frame $frameCount: Depth not yet available")
                                }
                            } finally {
                                depthImage?.close()
                            }

                            Thread.sleep(100) // ~10 FPS
                        } catch (e: InterruptedException) {
                            Log.d(TAG, "Depth thread interrupted")
                            break
                        } catch (e: Exception) {
                            val errorMsg = "${e.javaClass.simpleName}: ${e.message ?: "no details"}"
                            Log.e(TAG, "Depth error at frame $frameCount: $errorMsg", e)

                            val errorParams = Arguments.createMap().apply {
                                putString("error", errorMsg)
                            }
                            sendEvent("onDepthError", errorParams)

                            Thread.sleep(500)
                        }
                    }

                    Log.d(TAG, "Depth loop ended (frames: $frameCount, successes: $successCount)")
                } catch (e: InterruptedException) {
                    Log.d(TAG, "Depth thread interrupted during setup")
                } catch (e: CameraNotAvailableException) {
                    Log.e(TAG, "Camera not available for ARCore", e)
                    val errorParams = Arguments.createMap().apply {
                        putString("error", "Camera not available. Close other camera apps and try again.")
                    }
                    sendEvent("onDepthError", errorParams)
                } catch (e: Exception) {
                    val errorMsg = "${e.javaClass.simpleName}: ${e.message ?: "no details"}"
                    Log.e(TAG, "Depth thread fatal error: $errorMsg", e)
                    val errorParams = Arguments.createMap().apply {
                        putString("error", errorMsg)
                    }
                    sendEvent("onDepthError", errorParams)
                } finally {
                    // Clean up EGL on this thread
                    destroyEglContext()
                }
            }
            depthThread?.start()

            promise.resolve(true)
        } catch (e: UnavailableArcoreNotInstalledException) {
            promise.reject("NOT_INSTALLED", "ARCore is not installed")
        } catch (e: UnavailableDeviceNotCompatibleException) {
            promise.reject("NOT_COMPATIBLE", "Device does not support ARCore")
        } catch (e: UnavailableSdkTooOldException) {
            promise.reject("SDK_TOO_OLD", "ARCore SDK is too old")
        } catch (e: Exception) {
            val errorMsg = "${e.javaClass.simpleName}: ${e.message ?: "no details"}"
            Log.e(TAG, "Failed to start depth: $errorMsg", e)
            promise.reject("ERROR", "Failed to start: $errorMsg")
        }
    }

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

        for (dy in -SAMPLE_RADIUS..SAMPLE_RADIUS) {
            for (dx in -SAMPLE_RADIUS..SAMPLE_RADIUS) {
                val px = max(0, min(width - 1, centerX + dx))
                val py = max(0, min(height - 1, centerY + dy))

                val index = (py * rowStride / 2) + px
                if (index >= 0 && index < buffer.limit()) {
                    val depthMm = buffer.get(index).toInt() and 0xFFFF
                    if (depthMm > 0 && depthMm < 10000) {
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
            Log.d(TAG, "Stopping depth session...")
            isRunning = false
            depthThread?.interrupt()
            depthThread?.join(3000) // Wait up to 3 seconds for thread to finish
            depthThread = null

            arSession?.pause()
            arSession?.close()
            arSession = null

            Log.d(TAG, "Depth session stopped")
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping depth session", e)
            promise.reject("ERROR", "Failed to stop depth session: ${e.message}")
        }
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        try {
            reactApplicationContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending event $eventName", e)
        }
    }

    @ReactMethod
    fun addListener(eventName: String) {}

    @ReactMethod
    fun removeListeners(count: Int) {}
}
