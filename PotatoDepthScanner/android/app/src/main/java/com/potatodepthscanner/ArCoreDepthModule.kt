package com.potatodepthscanner

import android.app.Activity
import android.content.Context
import android.media.Image
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
        const val SAMPLE_RADIUS = 2 // 5x5 grid
    }

    private var arSession: Session? = null
    private var isRunning = false
    private var depthThread: Thread? = null

    override fun getName(): String = NAME

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

            // Install ARCore if needed
            val installStatus = ArCoreApk.getInstance().requestInstall(activity as Activity, true)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                promise.reject("INSTALL_REQUESTED", "ARCore installation requested")
                return
            }

            Log.d(TAG, "Creating ARCore session...")

            // Create and configure session
            arSession = Session(activity as Context)
            val config = Config(arSession!!)
            config.depthMode = Config.DepthMode.AUTOMATIC
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            arSession!!.configure(config)

            Log.d(TAG, "Resuming ARCore session...")
            arSession!!.resume()
            Log.d(TAG, "ARCore session started successfully")

            isRunning = true

            // Start background thread to poll depth data
            depthThread = Thread {
                Log.d(TAG, "Depth thread started, waiting for initialization...")
                // Give ARCore time to initialize and acquire first frames
                Thread.sleep(1500)
                Log.d(TAG, "Initialization wait complete, starting depth polling")

                var frameCount = 0
                var errorCount = 0

                while (isRunning) {
                    try {
                        val session = arSession ?: break
                        val frame = session.update()

                        frameCount++

                        // Check tracking state
                        val camera = frame.camera
                        if (camera.trackingState != TrackingState.TRACKING) {
                            if (frameCount % 10 == 0) {
                                Log.d(TAG, "Frame $frameCount: Not tracking yet (state: ${camera.trackingState})")
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

                            Log.d(TAG, "Frame $frameCount: Got depth image ${width}x${height}")

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
                                val params = Arguments.createMap().apply {
                                    putDouble("distance", depthMeters)
                                    putInt("depthWidth", width)
                                    putInt("depthHeight", height)
                                    putDouble("confidence", 1.0)
                                }
                                sendEvent("onDepthData", params)
                                errorCount = 0 // Reset error count on success
                            }
                        } catch (e: NotYetAvailableException) {
                            // Depth data not ready yet — this is normal for the first few frames
                            if (frameCount % 20 == 0) {
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
                        errorCount++
                        val errorMsg = "${e.javaClass.simpleName}: ${e.message ?: "no details"}"
                        Log.e(TAG, "Depth read error ($errorCount): $errorMsg", e)

                        // Only send error to JS every 5th error to avoid spam
                        if (errorCount <= 3 || errorCount % 5 == 0) {
                            val errorParams = Arguments.createMap().apply {
                                putString("error", errorMsg)
                            }
                            sendEvent("onDepthError", errorParams)
                        }

                        // If too many consecutive errors, stop
                        if (errorCount >= 30) {
                            Log.e(TAG, "Too many consecutive errors, stopping depth")
                            break
                        }

                        Thread.sleep(200)
                    }
                }

                Log.d(TAG, "Depth thread ended (frames: $frameCount, errors: $errorCount)")
            }
            depthThread?.start()

            promise.resolve(true)
        } catch (e: UnavailableArcoreNotInstalledException) {
            Log.e(TAG, "ARCore not installed", e)
            promise.reject("NOT_INSTALLED", "ARCore is not installed")
        } catch (e: UnavailableDeviceNotCompatibleException) {
            Log.e(TAG, "Device not compatible", e)
            promise.reject("NOT_COMPATIBLE", "Device does not support ARCore")
        } catch (e: UnavailableSdkTooOldException) {
            Log.e(TAG, "SDK too old", e)
            promise.reject("SDK_TOO_OLD", "ARCore SDK is too old")
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
            promise.reject("CAMERA_BUSY", "Camera is in use. Please wait a moment and try again.")
        } catch (e: Exception) {
            val errorMsg = "${e.javaClass.simpleName}: ${e.message ?: "no details"}"
            Log.e(TAG, "Failed to start depth: $errorMsg", e)
            promise.reject("ERROR", "Failed to start: $errorMsg")
        }
    }

    /**
     * Sample a 5x5 grid of depth pixels around (centerX, centerY) and return
     * the averaged depth in meters. Ignores zero (invalid) readings.
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
    fun addListener(eventName: String) {
        // Required for RN event emitter
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Required for RN event emitter
    }
}
