package com.potatodepthscanner

import android.content.Context
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

class ArCoreDepthView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {

    companion object {
        const val TAG = "ArCoreDepthView"
        const val SAMPLE_RADIUS = 3
        const val SMOOTHING_FACTOR = 0.3

        // Vertex shader for camera background
        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        // Fragment shader using OES external texture (required for camera)
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """

        // Full-screen quad vertices (NDC coordinates)
        private val QUAD_VERTICES = floatArrayOf(
            -1.0f, -1.0f, // bottom-left
            -1.0f,  1.0f, // top-left
             1.0f, -1.0f, // bottom-right
             1.0f,  1.0f  // top-right
        )
    }

    private var arSession: Session? = null
    private var cameraTextureId: Int = -1
    private var shaderProgram: Int = 0
    private var positionAttrib: Int = 0
    private var texCoordAttrib: Int = 0

    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null

    // Transformed texture coordinates (updated per frame)
    private val transformedTexCoords = FloatArray(8)

    private var smoothedDepth: Double = 0.0
    private var isSessionResumed = false
    private var viewWidth = 0
    private var viewHeight = 0
    private var reactContext: ReactContext? = null

    init {
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY

        // Create vertex buffer
        vertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(QUAD_VERTICES)
        vertexBuffer?.position(0)
    }

    fun setReactContext(ctx: ReactContext) {
        reactContext = ctx
    }

    fun resumeSession() {
        queueEvent {
            try {
                if (arSession == null) {
                    val activity = (context as? android.app.Activity)
                        ?: (reactContext?.currentActivity)
                    if (activity == null) {
                        Log.e(TAG, "No activity available")
                        return@queueEvent
                    }

                    arSession = Session(activity)
                    val config = Config(arSession!!)
                    config.depthMode = Config.DepthMode.AUTOMATIC
                    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    arSession!!.configure(config)
                }

                if (cameraTextureId != -1) {
                    arSession!!.setCameraTextureName(cameraTextureId)
                }

                arSession!!.resume()
                isSessionResumed = true
                smoothedDepth = 0.0
                Log.d(TAG, "ARCore session resumed")
            } catch (e: CameraNotAvailableException) {
                Log.e(TAG, "Camera not available", e)
                sendError("Camera not available. Close other camera apps.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume session: ${e.message}", e)
                sendError("${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun pauseSession() {
        queueEvent {
            try {
                isSessionResumed = false
                arSession?.pause()
                Log.d(TAG, "ARCore session paused")
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing session", e)
            }
        }
    }

    fun destroySession() {
        isSessionResumed = false
        arSession?.pause()
        arSession?.close()
        arSession = null
    }

    // ---- GLSurfaceView.Renderer ----

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // Create external texture for camera
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Compile shaders
        shaderProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttrib = GLES20.glGetAttribLocation(shaderProgram, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(shaderProgram, "a_TexCoord")

        Log.d(TAG, "Surface created, texture: $cameraTextureId, program: $shaderProgram")

        // Set texture on session if it exists
        arSession?.setCameraTextureName(cameraTextureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height

        arSession?.setDisplayGeometry(0, width, height)
        Log.d(TAG, "Surface changed: ${width}x${height}")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val session = arSession ?: return
        if (!isSessionResumed) return

        try {
            val frame = session.update()

            // Get transformed texture coordinates for proper camera orientation
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                vertexBuffer!!,
                Coordinates2d.TEXTURE_NORMALIZED,
                transformedTexCoords
            )

            // Update tex coord buffer
            texCoordBuffer = ByteBuffer.allocateDirect(transformedTexCoords.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(transformedTexCoords)
            texCoordBuffer?.position(0)

            // Draw camera background
            drawCameraBackground()

            // Read depth if tracking
            val camera = frame.camera
            if (camera.trackingState == TrackingState.TRACKING) {
                readDepth(frame)
            }
        } catch (e: NotYetAvailableException) {
            // Normal during initialization
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera lost", e)
            isSessionResumed = false
            sendError("Camera connection lost")
        } catch (e: Exception) {
            Log.e(TAG, "Draw frame error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun drawCameraBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glUseProgram(shaderProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)

        // Position
        GLES20.glEnableVertexAttribArray(positionAttrib)
        vertexBuffer?.position(0)
        GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        // Tex coords
        GLES20.glEnableVertexAttribArray(texCoordAttrib)
        texCoordBuffer?.position(0)
        GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)

        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun readDepth(frame: Frame) {
        var depthImage: Image? = null
        try {
            depthImage = frame.acquireDepthImage()
            val width = depthImage.width
            val height = depthImage.height

            val targetX = width / 2
            val targetY = height / 2

            val plane = depthImage.planes[0]
            val buffer: ShortBuffer = plane.buffer.asShortBuffer()
            val rowStride = plane.rowStride

            val rawDepth = sampleAveragedDepth(buffer, rowStride, width, height, targetX, targetY)

            if (rawDepth > 0) {
                smoothedDepth = if (smoothedDepth <= 0) rawDepth
                else smoothedDepth * (1 - SMOOTHING_FACTOR) + rawDepth * SMOOTHING_FACTOR

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
            // Depth not ready yet
        } finally {
            depthImage?.close()
        }
    }

    private fun sampleAveragedDepth(
        buffer: ShortBuffer, rowStride: Int,
        width: Int, height: Int,
        centerX: Int, centerY: Int
    ): Double {
        var totalDepth: Long = 0
        var validCount = 0
        val shortsPerRow = rowStride / 2

        for (dy in -SAMPLE_RADIUS..SAMPLE_RADIUS) {
            for (dx in -SAMPLE_RADIUS..SAMPLE_RADIUS) {
                val px = max(0, min(width - 1, centerX + dx))
                val py = max(0, min(height - 1, centerY + dy))
                val index = py * shortsPerRow + px
                if (index in 0 until buffer.limit()) {
                    val depthMm = buffer.get(index).toInt() and 0xFFFF
                    if (depthMm in 1..8000) {
                        totalDepth += depthMm
                        validCount++
                    }
                }
            }
        }

        return if (validCount > 0) (totalDepth.toDouble() / validCount) / 1000.0 else 0.0
    }

    // ---- GL Helpers ----

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Program link failed: $log")
        }

        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }

        return shader
    }

    // ---- Event Helpers ----

    private fun sendEvent(eventName: String, params: WritableMap) {
        try {
            reactContext?.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                ?.emit(eventName, params)
        } catch (_: Exception) {}
    }

    private fun sendError(message: String) {
        val params = Arguments.createMap().apply { putString("error", message) }
        sendEvent("onDepthError", params)
    }
}
