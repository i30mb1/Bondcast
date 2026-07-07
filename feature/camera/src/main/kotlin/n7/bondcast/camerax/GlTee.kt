package n7.bondcast.camerax

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal class GlTee(private val width: Int, private val height: Int) {

    private val thread = HandlerThread("GlTee").also { it.start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesTex = 0
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uStMatrix = 0
    private var uTransform = 0

    private lateinit var surfaceTexture: SurfaceTexture
    lateinit var inputSurface: Surface
        private set

    private val outputs = LinkedHashMap<Surface, Output>()
    private val stMatrix = FloatArray(16)
    private val transform = FloatArray(4)

    private val quad = floatBuffer(
        floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        ),
    )

    @Volatile
    var ready = false
        private set

    init {
        handler.post { initGl() }
    }

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, num, 0)
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)

        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
        uTransform = GLES20.glGetUniformLocation(program, "uTransform")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTex = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(oesTex).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ handler.post { drawFrame() } }, handler)
        }
        inputSurface = Surface(surfaceTexture)
        ready = true
        Log.i(TAG, "initGl done ${width}x$height")
    }

    fun provideInputSurfaceTo(consumer: (Surface) -> Unit) {
        handler.post { if (ready) consumer(inputSurface) }
    }

    fun addOutput(surface: Surface, rotationDegrees: Int = 0) {
        handler.post {
            if (!ready || outputs.containsKey(surface) || !surface.isValid) return@post
            val eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "eglCreateWindowSurface failed")
                return@post
            }
            outputs[surface] = Output(eglSurface, rotationDegrees)
            Log.i(TAG, "addOutput total=${outputs.size}")
        }
    }

    fun removeOutput(surface: Surface) {
        handler.post {
            val out = outputs.remove(surface) ?: return@post
            EGL14.eglDestroySurface(eglDisplay, out.egl)
        }
    }

    private fun drawFrame() {
        if (!ready) return
        runCatching {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)
        }.onFailure { return }
        if (outputs.isEmpty()) return
        val iterator = outputs.entries.iterator()
        while (iterator.hasNext()) {
            val (surface, out) = iterator.next()
            val egl = out.egl
            if (!surface.isValid) {
                EGL14.eglDestroySurface(eglDisplay, egl)
                iterator.remove()
                continue
            }
            if (!EGL14.eglMakeCurrent(eglDisplay, egl, egl, eglContext)) continue
            val w = querySurface(egl, EGL14.EGL_WIDTH)
            val h = querySurface(egl, EGL14.EGL_HEIGHT)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            applyTransform(out.rotation)
            quad.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aPos)
            quad.position(2)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGL14.eglSwapBuffers(eglDisplay, egl)
        }
    }

    private fun applyTransform(rotation: Int) {
        val rad = Math.toRadians(rotation.toDouble())
        val cos = Math.cos(rad).toFloat()
        val sin = Math.sin(rad).toFloat()
        transform[0] = cos
        transform[1] = sin
        transform[2] = -sin
        transform[3] = cos
        GLES20.glUniformMatrix2fv(uTransform, 1, false, transform, 0)
    }

    private fun querySurface(egl: EGLSurface, what: Int): Int {
        val value = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, egl, what, value, 0)
        return value[0]
    }

    fun release() {
        handler.post {
            ready = false
            outputs.values.forEach { EGL14.eglDestroySurface(eglDisplay, it.egl) }
            outputs.clear()
            runCatching { surfaceTexture.release() }
            runCatching { inputSurface.release() }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            }
            thread.quitSafely()
        }
    }

    private fun buildProgram(): Int {
        val vertex = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
            GLES20.glShaderSource(it, VERTEX_SHADER)
            GLES20.glCompileShader(it)
        }
        val fragment = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
            GLES20.glShaderSource(it, FRAGMENT_SHADER)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertex)
            GLES20.glAttachShader(it, fragment)
            GLES20.glLinkProgram(it)
        }
    }

    private companion object {
        const val TAG = "GlTee"
        const val EGL_RECORDABLE_ANDROID = 0x3142

        const val VERTEX_SHADER =
            "attribute vec4 aPos;\n" +
                "attribute vec4 aTex;\n" +
                "uniform mat4 uStMatrix;\n" +
                "uniform mat2 uTransform;\n" +
                "varying vec2 vTex;\n" +
                "void main() {\n" +
                "  gl_Position = vec4(uTransform * aPos.xy, 0.0, 1.0);\n" +
                "  vTex = (uStMatrix * aTex).xy;\n" +
                "}\n"

        const val FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "varying vec2 vTex;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(sTexture, vTex);\n" +
                "}\n"
    }
}

private class Output(val egl: EGLSurface, val rotation: Int)

private fun floatBuffer(data: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(data)
        position(0)
    }
