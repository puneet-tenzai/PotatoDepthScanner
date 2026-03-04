package com.potatodepthscanner

import android.view.Choreographer
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext

class ArCoreDepthViewManager : SimpleViewManager<ArCoreDepthView>() {

    companion object {
        const val REACT_CLASS = "ArCoreDepthView"
        const val COMMAND_RESUME = 1
        const val COMMAND_PAUSE = 2
    }

    override fun getName(): String = REACT_CLASS

    override fun createViewInstance(reactContext: ThemedReactContext): ArCoreDepthView {
        val view = ArCoreDepthView(reactContext)
        view.setReactContext(reactContext)
        return view
    }

    override fun getCommandsMap(): Map<String, Int> {
        return mapOf(
            "resume" to COMMAND_RESUME,
            "pause" to COMMAND_PAUSE
        )
    }

    override fun receiveCommand(view: ArCoreDepthView, commandId: String?, args: ReadableArray?) {
        when (commandId) {
            "resume" -> view.resumeSession()
            "pause" -> view.pauseSession()
        }
    }

    override fun onDropViewInstance(view: ArCoreDepthView) {
        view.pauseSession()
        view.destroySession()
        super.onDropViewInstance(view)
    }
}
