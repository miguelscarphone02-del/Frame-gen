package com.framegen.pvr

import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Captura o app escolhido (MediaProjection, modo "um unico app"), gera um
 * quadro intermediario por quadro capturado e apresenta os dois na Surface
 * do overlay. Tudo roda numa thread propria com contexto GLES 3.0.
 *
 * O tamanho da captura segue o tamanho REAL da Surface do overlay.
 */
class FrameGenEngine(
    private val cfg: FgConfig,
    private val dpi: Int,
    private val listener: Listener
) {
    interface Listener {
        fun onFrame(bypass: Boolean)
        fun onStats(srcFps: Int, outFps: Int, emaMs: Float)
        fun onError(msg: String)
    }

    private companion object {
        const val TAG = "FrameGenPVR"
        const val EGL_CONTEXT_PRIORITY_LEVEL_IMG = 0x3100
        const val EGL_CONTEXT_PRIORITY_HIGH_IMG = 0x3101
    }

    private val thread = HandlerThread("framegen-gl", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
    private val h = Handler(thread.looper)

    // EGL
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var winSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // Tamanho real da Surface do overlay (0 = ainda desconhecido)
    private var surfW = 0
    private var surfH = 0

    // Captura
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var st: SurfaceTexture? = null
    private var stSurface: Surface? = null
    private var oesTex = 0
    private var capW = 0
    private var capH = 0
    private val texMatrix = FloatArray(16)

    // Programas
    private var pOes: Prog? = null
    private var pDown: Prog? = null
    private var pDownR: Prog? = null
    private var pCoarse: Prog? = null
    private var pRefine: Prog? = null
    private var pMid: Prog? = null
    private var pPresent: Prog? = null

    // Alvos
    private var frames: Array<Target>? = null
    private var l1: Array<Target>? = null
    private var l2: Array<Target>? = null
    private var coarse: Target? = null
    private var fine: Target? = null
    private var mid: Target? = null

    // Estado do pipeline
    @Volatile private var glReady = false
    private var ci = 0
    private var havePrev = false
    private var lastSrcNs = 0L
    private var emaMs = 0f
    private var bypassed = false
    private var srcCount = 0
    private var outCount = 0
    private var lastStatNs = 0L
    private val sizeQuery = IntArray(1)

    private val presentCurrent = Runnable {
        val f = frames
        if (glReady && f != null && winSurface != EGL14.EGL_NO_SURFACE) present(f[ci].tex)
    }

    // ------------------------------------------------------------------
    // API publica (qualquer thread)
    // ------------------------------------------------------------------

    fun start(mp: MediaProjection, dispW: Int, dispH: Int) {
        h.post {
            try {
                projection = mp
                initEgl()
                initPrograms()
                capW = even(dispW * cfg.scale)
                capH = even(dispH * cfg.scale)
                oesTex = GlUtil.createOesTexture()
                val s = SurfaceTexture(oesTex)
                s.setDefaultBufferSize(capW, capH)
                s.setOnFrameAvailableListener({ onSourceFrame() }, h)
                st = s
                stSurface = Surface(s)
                allocTargets()
                vd = mp.createVirtualDisplay(
                    "FrameGenPVR", capW, capH, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    stSurface, null, h
                )
                glReady = true
                Log.i(TAG, "Engine iniciado: captura ${capW}x$capH")
            } catch (t: Throwable) {
                Log.e(TAG, "Falha ao iniciar", t)
                listener.onError(t.message ?: t.toString())
            }
        }
    }

    /**
     * Tamanho informado pelo sistema. So vale enquanto ainda nao sabemos o
     * tamanho real da Surface; depois disso a Surface manda.
     */
    fun setDisplaySize(dispW: Int, dispH: Int) {
        h.post {
            if (!glReady || surfW > 0) return@post
            applyCaptureSize(dispW, dispH)
        }
    }

    fun surfaceCreated(surface: Surface) {
        h.post {
            if (eglContext == EGL14.EGL_NO_CONTEXT) return@post
            destroyWindowSurface()
            val attrs = intArrayOf(EGL14.EGL_NONE)
            winSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attrs, 0)
            if (winSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "eglCreateWindowSurface falhou: 0x${Integer.toHexString(EGL14.eglGetError())}")
                return@post
            }
            EGL14.eglMakeCurrent(eglDisplay, winSurface, winSurface, eglContext)
            EGL14.eglSwapInterval(eglDisplay, 1)
            havePrev = false

            EGL14.eglQuerySurface(eglDisplay, winSurface, EGL14.EGL_WIDTH, sizeQuery, 0)
            val w = sizeQuery[0]
            EGL14.eglQuerySurface(eglDisplay, winSurface, EGL14.EGL_HEIGHT, sizeQuery, 0)
            val hh = sizeQuery[0]
            if (w > 0 && hh > 0) {
                surfW = w
                surfH = hh
                if (glReady) applyCaptureSize(w, hh)
            }
        }
    }

    /** Bloqueia ate o engine soltar a Surface (exigencia do SurfaceHolder.Callback). */
    fun surfaceDestroyed() {
        val latch = CountDownLatch(1)
        h.post {
            destroyWindowSurface()
            latch.countDown()
        }
        latch.await(500, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        h.post {
            glReady = false
            h.removeCallbacksAndMessages(null)
            try {
                st?.setOnFrameAvailableListener(null)
                vd?.release()
                vd = null
                stSurface?.release()
                stSurface = null
                st?.release()
                st = null
                releaseTargets()
                pOes?.delete(); pDown?.delete(); pDownR?.delete(); pCoarse?.delete()
                pRefine?.delete(); pMid?.delete(); pPresent?.delete()
                pOes = null; pDown = null; pDownR = null; pCoarse = null
                pRefine = null; pMid = null; pPresent = null
                if (oesTex != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTex), 0)
                oesTex = 0
            } catch (t: Throwable) {
                Log.w(TAG, "Erro ao liberar recursos", t)
            }
            destroyWindowSurface()
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            pbuffer = EGL14.EGL_NO_SURFACE
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------
    // Inicializacao
    // ------------------------------------------------------------------

    private fun even(v: Float): Int = max(2, (v.toInt() / 2) * 2)

    /** Ajusta a captura para (w x h) * escala. Roda na thread GL. */
    private fun applyCaptureSize(w: Int, hh: Int) {
        if (!glReady || w <= 0 || hh <= 0) return
        val nw = even(w * cfg.scale)
        val nh = even(hh * cfg.scale)
        if (nw == capW && nh == capH) return
        capW = nw
        capH = nh
        try {
            st?.setDefaultBufferSize(capW, capH)
            vd?.resize(capW, capH, dpi)
            allocTargets()
            havePrev = false
            Log.i(TAG, "Captura redimensionada: ${capW}x$capH")
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao redimensionar", t)
        }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) throw RuntimeException("eglInitialize falhou")

        val cfgAttrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, cfgs, 0, 1, n, 0)
        if (n[0] <= 0 || cfgs[0] == null) throw RuntimeException("Nenhuma EGLConfig ES3 RGBA8 encontrada")
        eglConfig = cfgs[0]

        var ctx = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_HIGH_IMG,
                EGL14.EGL_NONE
            ), 0
        )
        if (ctx == EGL14.EGL_NO_CONTEXT) {
            ctx = EGL14.eglCreateContext(
                eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
        }
        if (ctx == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext falhou")
        eglContext = ctx

        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        if (!EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)) {
            throw RuntimeException("eglMakeCurrent falhou")
        }
        Log.i(TAG, "GL_RENDERER=${GLES20.glGetString(GLES20.GL_RENDERER)} | ${GLES20.glGetString(GLES20.GL_VERSION)}")
    }

    private fun initPrograms() {
        pOes = Prog(Shaders.VERT, Shaders.FRAG_OES)
        pDown = Prog(Shaders.VERT, Shaders.FRAG_DOWN)
        pDownR = Prog(Shaders.VERT, Shaders.FRAG_DOWN_R)
        pCoarse = Prog(Shaders.VERT, Shaders.fragCoarse(cfg.radius))
        pRefine = Prog(Shaders.VERT, Shaders.fragRefine())
        pMid = Prog(Shaders.VERT, Shaders.FRAG_MID)
        pPresent = Prog(Shaders.VERT, Shaders.FRAG_PRESENT)
    }

    private fun allocTargets() {
        releaseTargets()
        val w1 = (capW + 3) / 4
        val h1 = (capH + 3) / 4
        val w2 = (capW + 7) / 8
        val h2 = (capH + 7) / 8
        frames = arrayOf(GlUtil.createTarget(capW, capH), GlUtil.createTarget(capW, capH))
        // Luminancia em 1 canal (R8): 4x menos memoria lida na busca de movimento.
        l1 = arrayOf(GlUtil.createTarget(w1, h1, true), GlUtil.createTarget(w1, h1, true))
        l2 = arrayOf(GlUtil.createTarget(w2, h2, true), GlUtil.createTarget(w2, h2, true))
        coarse = GlUtil.createTarget(w2, h2)
        fine = GlUtil.createTarget(w2, h2)
        mid = GlUtil.createTarget(capW, capH)
        havePrev = false
    }

    private fun releaseTargets() {
        frames?.forEach { it.release() }; frames = null
        l1?.forEach { it.release() }; l1 = null
        l2?.forEach { it.release() }; l2 = null
        coarse?.release(); coarse = null
        fine?.release(); fine = null
        mid?.release(); mid = null
    }

    private fun destroyWindowSurface() {
        if (winSurface != EGL14.EGL_NO_SURFACE) {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
                EGL14.eglDestroySurface(eglDisplay, winSurface)
            }
            winSurface = EGL14.EGL_NO_SURFACE
            havePrev = false
        }
        surfW = 0
        surfH = 0
    }

    // ------------------------------------------------------------------
    // Loop principal
    // ------------------------------------------------------------------

    private fun onSourceFrame() {
        val s = st ?: return
        if (!glReady) return
        try {
            s.updateTexImage()
        } catch (e: RuntimeException) {
            return
        }
        s.getTransformMatrix(texMatrix)

        val now = System.nanoTime()
        val dtMs = if (lastSrcNs == 0L) 0f else (now - lastSrcNs) / 1_000_000f
        lastSrcNs = now
        srcCount++
        if (dtMs in 4f..150f) emaMs = if (emaMs == 0f) dtMs else emaMs * 0.8f + dtMs * 0.2f

        if (cfg.bypassHigh) {
            if (emaMs in 0.1f..17.5f) bypassed = true else if (emaMs > 24f) bypassed = false
        } else {
            bypassed = false
        }
        val bypass = bypassed || !cfg.interpolate

        h.removeCallbacks(presentCurrent)
        listener.onFrame(bypass)
        publishStats(now)

        val f = frames ?: return
        if (bypass || winSurface == EGL14.EGL_NO_SURFACE) {
            havePrev = false
            return
        }

        val prev = ci
        ci = 1 - ci
        drawOes(f[ci])
        buildPyramid(ci)

        val canInterp = havePrev && dtMs in 8f..110f
        havePrev = true
        if (!canInterp) {
            present(f[ci].tex)
            return
        }

        runMotion(ci, prev)
        drawMid(ci, prev)
        mid?.let { present(it.tex) }
        h.postDelayed(presentCurrent, max(1L, (emaMs * 0.5f).toLong()))
    }

    private fun publishStats(now: Long) {
        if (lastStatNs == 0L) lastStatNs = now
        if (now - lastStatNs >= 1_000_000_000L) {
            listener.onStats(srcCount, outCount, emaMs)
            srcCount = 0
            outCount = 0
            lastStatNs = now
        }
    }

    // ------------------------------------------------------------------
    // Passes de GL
    // ------------------------------------------------------------------

    private fun drawOes(dst: Target) {
        val p = pOes ?: return
        GlUtil.bindTarget(dst)
        p.use()
        GlUtil.bindOes(0, oesTex)
        p.i1("uTex", 0)
        p.m4("uTexMatrix", texMatrix)
        GlUtil.drawQuad()
    }

    private fun buildPyramid(i: Int) {
        val p = pDown ?: return
        val pr = pDownR ?: return
        val f = frames ?: return
        val a = l1 ?: return
        val b = l2 ?: return

        // Quadro RGBA -> L1 (luma, R8)
        p.use()
        p.i1("uSrc", 0)
        GlUtil.bindTarget(a[i])
        GlUtil.bindTex2D(0, f[i].tex)
        p.f2("uOff", 1f / f[i].w, 1f / f[i].h)
        GlUtil.drawQuad()

        // L1 (R8) -> L2 (R8)
        pr.use()
        pr.i1("uSrc", 0)
        GlUtil.bindTarget(b[i])
        GlUtil.bindTex2D(0, a[i].tex)
        pr.f2("uOff", 0.5f / a[i].w, 0.5f / a[i].h)
        GlUtil.drawQuad()
    }

    private fun runMotion(cur: Int, prev: Int) {
        val pc = pCoarse ?: return
        val pr = pRefine ?: return
        val a1 = l1 ?: return
        val a2 = l2 ?: return
        val c = coarse ?: return
        val fi = fine ?: return

        GlUtil.bindTarget(c)
        pc.use()
        GlUtil.bindTex2D(0, a2[cur].tex)
        GlUtil.bindTex2D(1, a2[prev].tex)
        pc.i1("uCur", 0)
        pc.i1("uPrev", 1)
        pc.f2("uTexel", 1f / a2[cur].w, 1f / a2[cur].h)
        pc.f1("uLambda", 0.02f)
        GlUtil.drawQuad()

        GlUtil.bindTarget(fi)
        pr.use()
        GlUtil.bindTex2D(0, a1[cur].tex)
        GlUtil.bindTex2D(1, a1[prev].tex)
        GlUtil.bindTex2D(2, c.tex)
        pr.i1("uCur", 0)
        pr.i1("uPrev", 1)
        pr.i1("uCoarse", 2)
        pr.f2("uTexel", 1f / a1[cur].w, 1f / a1[cur].h)
        pr.f1("uLambda", 0.015f)
        GlUtil.drawQuad()
    }

    private fun drawMid(cur: Int, prev: Int) {
        val p = pMid ?: return
        val f = frames ?: return
        val a1 = l1 ?: return
        val m = mid ?: return
        val fi = fine ?: return
        GlUtil.bindTarget(m)
        p.use()
        GlUtil.bindTex2D(0, f[cur].tex)
        GlUtil.bindTex2D(1, f[prev].tex)
        GlUtil.bindTex2D(2, fi.tex)
        p.i1("uCur", 0)
        p.i1("uPrev", 1)
        p.i1("uFlow", 2)
        p.f2("uInvL1", 1f / a1[cur].w, 1f / a1[cur].h)
        p.f2("uErr", 0.06f, 0.18f)
        GlUtil.drawQuad()
    }

    private fun present(tex: Int) {
        val p = pPresent ?: return
        if (winSurface == EGL14.EGL_NO_SURFACE) return
        EGL14.eglQuerySurface(eglDisplay, winSurface, EGL14.EGL_WIDTH, sizeQuery, 0)
        val w = sizeQuery[0]
        EGL14.eglQuerySurface(eglDisplay, winSurface, EGL14.EGL_HEIGHT, sizeQuery, 0)
        val hh = sizeQuery[0]
        if (w <= 0 || hh <= 0) return

        // A Surface mudou de tamanho (rotacao etc.): ajusta a captura no proximo ciclo.
        if (w != surfW || hh != surfH) {
            surfW = w
            surfH = hh
            h.post { applyCaptureSize(w, hh) }
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, hh)
        p.use()
        GlUtil.bindTex2D(0, tex)
        p.i1("uTex", 0)
        p.f2("uTexel", 1f / capW, 1f / capH)
        p.f1("uSharp", cfg.sharpen)
        GlUtil.drawQuad()
        if (EGL14.eglSwapBuffers(eglDisplay, winSurface)) {
            outCount++
        } else {
            Log.w(TAG, "eglSwapBuffers falhou: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
    }
}
