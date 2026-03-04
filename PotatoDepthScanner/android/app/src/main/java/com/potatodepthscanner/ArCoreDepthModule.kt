package com.potatodepthscanner

import android.app.Activity
import android.content.Context
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.google.ar.core.*

class ArCoreDepthModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "ArCoreDepthModule"
        const val TAG = "ArCoreDepth"
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
    fun addListener(eventName: String) {}

    @ReactMethod
    fun removeListeners(count: Int) {}
}
