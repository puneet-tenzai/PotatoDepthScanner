package com.potatodepthscanner

import android.app.Activity
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import java.nio.ShortBuffer
import java.util.EnumSet

class ArCoreDepthModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "ArCoreDepthModule"
        const val TAG = "ArCoreDepth"
    }

    private var arSession: Session? = null
    private var isRunning = false
    private var depthThread: Thread? = null

    override fun getName(): String = NAME

    @ReactMethod
    fun checkDepthSupport(promise: Promise) {
        try {
            val activity = currentActivity
            if (activity == null) {
                promise.resolve(false)
                return
            }

            val availability = ArCoreApk.getInstance().checkAvailability(activity)
            if (availability.isTransient) {
                // Re-query at a later time
                promise.resolve(false)
                return
            }

            if (!availability.isSupported) {
                promise.resolve(false)
                return
            }

            // Check if depth is supported by creating a temporary session
            try {
                val tempSession = Session(activity, EnumSet.of(Session.Feature.FRONT_CAMERA).let {
                    EnumSet.noneOf(Session.Feature::class.java)
                })
                val config = Config(tempSession)
                config.depthMode = Config.DepthMode.AUTOMATIC
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
            val activity = currentActivity
            if (activity == null) {
                promise.reject("NO_ACTIVITY", "No current activity")
                return
            }

            // Install ARCore if needed
            val installStatus = ArCoreApk.getInstance().requestInstall(activity, true)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                promise.reject("INSTALL_REQUESTED", "ARCore installation requested")
                return
            }

            // Create and configure session
            arSession = Session(activity)
            val config = Config(arSession)
            config.depthMode = Config.DepthMode.AUTOMATIC
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            arSession?.configure(config)
            arSession?.resume()

            isRunning = true

            // Start a background thread to poll depth data
            depthThread = Thread {
                while (isRunning) {
                    try {
                        val frame = arSession?.update()
                        if (frame != null) {
                            try {
                                val depthImage = frame.acquireDepthImage16Bits()
                                val width = depthImage.width
                                val height = depthImage.height

                                // Get depth at center of image
                                val centerX = width / 2
                                val centerY = height / 2

                                val plane = depthImage.planes[0]
                                val buffer: ShortBuffer = plane.buffer.asShortBuffer()
                                val pixelStride = plane.pixelStride
                                val rowStride = plane.rowStride

                                // Calculate the index for the center pixel
                                val index = (centerY * rowStride / 2) + centerX
                                val depthMillimeters = buffer.get(index).toInt() and 0xFFFF
                                val depthMeters = depthMillimeters / 1000.0

                                depthImage.close()

                                // Send event to React Native
                                val params = Arguments.createMap().apply {
                                    putDouble("distance", depthMeters)
                                    putInt("depthWidth", width)
                                    putInt("depthHeight", height)
                                    putDouble("confidence", if (depthMillimeters > 0) 1.0 else 0.0)
                                }
                                sendEvent("onDepthData", params)
                            } catch (e: NotYetAvailableException) {
                                // Depth data not ready yet, skip this frame
                            }
                        }
                        Thread.sleep(100) // ~10 FPS for depth data
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading depth", e)
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
        } catch (e: Exception) {
            promise.reject("ERROR", "Failed to start depth session: ${e.message}")
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
