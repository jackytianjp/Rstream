package com.takano.rstream

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 相机 SurfaceTexture → GL 旋转 → 编码器输入 Surface。
 *
 * 为什么需要：眼镜的相机传感器方向是 270°（RG-glasses 实测），相机吐出来的帧是"躺着"的
 * 1280×720；CameraX 的 SurfaceProvider 只给未旋转的 buffer（旋转信息只体现在 transformation 里），
 * 所以必须自己做一次 GL 旋转，否则推出去的画面是侧倒的。
 *
 * 用法：
 *   val rot = GlRotator(encoderInputSurface, srcW, srcH, dstW, dstH, rotationDeg)
 *   val st = rot.start()                    // 把 Surface(st) 交给相机
 *   ...
 *   rot.release()
 */
class GlRotator(
    private val encoderSurface: Surface,
    private val srcWidth: Int,
    private val srcHeight: Int,
    private val dstWidth: Int,
    private val dstHeight: Int,
    private val rotationDeg: Int,
) {
    companion object {
        private const val TAG = "GlRotator"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0

    private lateinit var surfaceTexture: SurfaceTexture
    private var thread: Thread? = null

    @Volatile private var running = false
    private var framesLogged = 0
    private val ready = java.util.concurrent.CountDownLatch(1)
    private val frameLock = Object()
    private var frameAvailable = false

    private val texMatrix = FloatArray(16)
    private val rotMatrix = FloatArray(16)
    private val finalMatrix = FloatArray(16)

    private lateinit var quad: FloatBuffer
    private lateinit var texCoords: FloatBuffer

    /**
     * 初始化 GL 并返回给相机用的 SurfaceTexture。
     *
     * 注意：EGL context 是**按线程**生效的，SurfaceTexture.updateTexImage() 必须在持有该 context 的
     * 线程上调用（否则报 "Unable to update texture contents"）。所以 EGL/GL/SurfaceTexture 的创建
     * 全都放在 GL 线程里做，start() 只等它就绪。
     */
    fun start(): SurfaceTexture {
        thread = Thread({
            try {
                setupEgl()
                setupGl()
                surfaceTexture = SurfaceTexture(textureId)
                surfaceTexture.setDefaultBufferSize(srcWidth, srcHeight)
                surfaceTexture.setOnFrameAvailableListener {
                    synchronized(frameLock) {
                        frameAvailable = true
                        frameLock.notifyAll()
                    }
                }
                running = true
                Log.i(TAG, "GL 就绪：${srcWidth}x$srcHeight → ${dstWidth}x$dstHeight，旋转 ${rotationDeg}°")
                ready.countDown()
                loop()
            } catch (e: Throwable) {
                Log.e(TAG, "GL 初始化失败", e)
                ready.countDown()
            }
        }, "gl-rotate").also { it.start() }
        ready.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return surfaceTexture
    }

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay 失败" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize 失败" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0
        ) { "eglChooseConfig 失败" }

        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext 失败" }

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface 失败" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent 失败" }
    }

    private fun setupGl() {
        val vs = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent()
        val vsh = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
            GLES20.glShaderSource(it, vs); GLES20.glCompileShader(it)
        }
        val fsh = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
            GLES20.glShaderSource(it, fs); GLES20.glCompileShader(it)
        }
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vsh)
            GLES20.glAttachShader(it, fsh)
            GLES20.glLinkProgram(it)
        }
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        quad = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        texCoords = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        buildRotationMatrix(rotationDeg)
    }

    private fun floatBuffer(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(a); position(0)
        }

    /**
     * 纹理坐标旋转矩阵（列主序，GL 约定）。rotationDeg = 「画面需要顺时针转多少度才正立」
     * （= Android 的 SENSOR_ORIENTATION 语义）。
     *
     * 推导：把画面顺时针转 θ，等价于采样坐标转 -θ。90° CW 时 new(u,v) = old(v, 1-u)；
     * 270° CW 时 new(u,v) = old(1-v, u)。
     */
    private fun buildRotationMatrix(deg: Int) {
        val d = ((deg % 360) + 360) % 360
        val m = rotMatrix
        java.util.Arrays.fill(m, 0f)
        m[10] = 1f
        m[15] = 1f
        when (d) {
            90 -> {
                m[0] = 0f; m[4] = 1f; m[12] = 0f
                m[1] = -1f; m[5] = 0f; m[13] = 1f
            }
            180 -> {
                m[0] = -1f; m[4] = 0f; m[12] = 1f
                m[1] = 0f; m[5] = -1f; m[13] = 1f
            }
            270 -> {
                m[0] = 0f; m[4] = -1f; m[12] = 1f
                m[1] = 1f; m[5] = 0f; m[13] = 0f
            }
            else -> {
                m[0] = 1f; m[4] = 0f; m[12] = 0f
                m[1] = 0f; m[5] = 1f; m[13] = 0f
            }
        }
    }

    private fun loop() {
        try {
            while (running) {
                synchronized(frameLock) {
                    while (running && !frameAvailable) {
                        frameLock.wait(500)
                    }
                    frameAvailable = false
                }
                if (!running) break
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(texMatrix)
                if (framesLogged < 3) {
                    framesLogged++
                    Log.i(TAG, "SurfaceTexture 变换矩阵: [" +
                        texMatrix.joinToString(", ") { String.format("%.2f", it) } + "]")
                }
                // finalMatrix = rotMatrix × texMatrix（先做 SurfaceTexture 变换，再旋转）
                multiply(rotMatrix, texMatrix, finalMatrix)

                GLES20.glViewport(0, 0, dstWidth, dstHeight)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, finalMatrix, 0)
                GLES20.glEnableVertexAttribArray(aPosition)
                GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quad)
                GLES20.glEnableVertexAttribArray(aTexCoord)
                GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(aPosition)
                GLES20.glDisableVertexAttribArray(aTexCoord)
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, surfaceTexture.timestamp)
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                    Log.w(TAG, "eglSwapBuffers 失败: ${EGL14.eglGetError()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GL 线程异常", e)
        }
    }

    /** 4x4 列主序矩阵相乘：out = a × b */
    private fun multiply(a: FloatArray, b: FloatArray, out: FloatArray) {
        val tmp = FloatArray(16)
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) {
                    s += a[k * 4 + r] * b[c * 4 + k]
                }
                tmp[c * 4 + r] = s
            }
        }
        System.arraycopy(tmp, 0, out, 0, 16)
    }

    fun release() {
        running = false
        synchronized(frameLock) { frameLock.notifyAll() }
        thread?.join(1000)
        runCatching { surfaceTexture.release() }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
    }
}
