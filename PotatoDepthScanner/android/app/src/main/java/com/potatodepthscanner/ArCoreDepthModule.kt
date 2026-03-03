package com.potatodepthscanner

import android.app.Activity
import android.content.Context
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import java.nio.ShortBuffer
import java.util.EnumSet
import kotlin.math.max
import kotlin.math.min

class ArCoreDepthModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "ArCoreDepthModule"
        const val TAG = "ArCoreDepth"
        const val SAMPLE_RADIUS = 2 // 5x5 grid (2 pixels in each direction from center)
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

            // Check if depth is supported by creating a temporary session
            try {
                val tempSession = Session(activity as Context, EnumSet.noneOf(Session.Feature::class.java))
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

            // Create and configure session
            arSession = Session(activity as Context)
            val config = Config(arSession)
            config.depthMode = Config.DepthMode.AUTOMATIC
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            arSession?.configure(config)
            arSession?.resume()

            isRunning = true

            // Start a background thread to poll depth data
            depthThread = Thread {
                // Give ARCore a moment to initialize and acquire first frames
                Thread.sleep(500)

                while (isRunning) {
                    try {
                        val frame = arSession?.update()
                        if (frame != null) {
                            try {
                                val depthImage = frame.acquireDepthImage16Bits()
                                val width = depthImage.width
                                val height = depthImage.height

                                // Sample at bottom-center of the image (ground area)
                                // Using 85% down the image to target the ground when
                                // the phone is held at a normal angle
                                val targetX = width / 2
                                val targetY = (height * 0.85).toInt()

                                val plane = depthImage.planes[0]
                                val buffer: ShortBuffer = plane.buffer.asShortBuffer()
                                val rowStride = plane.rowStride

                                // Average a grid of pixels around the target for stability
                                val depthMeters = sampleAveragedDepth(
                                    buffer, rowStride, width, height, targetX, targetY
                                )

                                depthImage.close()

                                if (depthMeters > 0) {
                                    // Send event to React Native
                                    val params = Arguments.createMap().apply {
                                        putDouble("distance", depthMeters)
                                        putInt("depthWidth", width)
                                        putInt("depthHeight", height)
                                        putDouble("confidence", 1.0)
                                    }
                                    sendEvent("onDepthData", params)
                                }
                            } catch (e: NotYetAvailableException) {
                                // Depth data not ready yet, skip this frame
                            }
                        }
                        Thread.sleep(100) // ~10 FPS for depth data
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading depth", e)
                        // Send error event to JS
                        val errorParams = Arguments.createMap().apply {
                            putString("error", e.message ?: "Unknown depth error")
                        }
                        sendEvent("onDepthError", errorParams)
                    }
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
        } catch (e: CameraNotAvailableException) {
            promise.reject("CAMERA_BUSY", "Camera is not available. Please close other camera apps.")
        } catch (e: Exception) {
            promise.reject("ERROR", "Failed to start depth session: ${e.message}")
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
                    if (depthMm > 0 && depthMm < 10000) { // Valid range: 0-10 meters
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
            depthThread = null
            arSession?.pause()
            arSession?.close()
            arSession = null
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERROR", "Failed to stop depth session: ${e.message}")
        }
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
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
