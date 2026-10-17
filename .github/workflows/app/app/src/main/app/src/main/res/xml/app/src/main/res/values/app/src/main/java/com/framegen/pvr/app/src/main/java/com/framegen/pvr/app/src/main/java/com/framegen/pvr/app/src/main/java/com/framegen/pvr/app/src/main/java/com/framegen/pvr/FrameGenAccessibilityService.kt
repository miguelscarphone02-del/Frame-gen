package com.framegen.pvr

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Dono da janela de overlay. Usa TYPE_ACCESSIBILITY_OVERLAY porque o Android 12+
 * bloqueia toques que atravessam overlays "normais" opacos; o overlay de
 * acessibilidade e confiavel e deixa o toque passar para o jogo.
 *
 * O servico NAO le o conteudo da tela (canRetrieveWindowContent=false).
 */
class FrameGenAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var root: FrameLayout? = null
    private var hud: TextView? = null
    private var engine: FrameGenEngine? = null

    private var attached = false
    private var captureVisible = true
    private var bypass = false
    private var showHud = true

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Bus.overlay = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::wm.isInitialized) return
        val b = wm.currentWindowMetrics.bounds
        engine?.setDisplaySize(b.width(), b.height())
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopInternal()
        Bus.overlay = null
        return super.onUnbind(intent)
    }

    // ------------------------------------------------------------------
    // Chamado pelo ProjectionService
    // ------------------------------------------------------------------

    fun startEngine(mp: MediaProjection) {
        main.post { startInternal(mp) }
    }

    fun stopEngine() {
        main.post { stopInternal() }
    }

    /** O app capturado ficou visivel/invisivel (callback do MediaProjection, Android 14). */
    fun setCaptureVisible(visible: Boolean) {
        main.post {
            captureVisible = visible
            applyOverlay(false)
        }
    }

    // ------------------------------------------------------------------

    private fun startInternal(mp: MediaProjection) {
        stopInternal()
        val cfg = Prefs.load(this)
        showHud = cfg.hud
        captureVisible = true
        bypass = false
        buildOverlay()

        val e = FrameGenEngine(cfg, resources.displayMetrics.densityDpi, object : FrameGenEngine.Listener {
            override fun onFrame(bypass: Boolean) {
                main.post {
                    this@FrameGenAccessibilityService.bypass = bypass
                    applyOverlay(true)
                }
            }

            override fun onStats(srcFps: Int, outFps: Int, emaMs: Float) {
                main.post {
                    hud?.text = if (bypass) "Jogo $srcFps fps (sem interpolar)"
                    else "Jogo $srcFps  ->  Saida $outFps fps"
                }
            }

            override fun onError(msg: String) {
                main.post {
                    Toast.makeText(this@FrameGenAccessibilityService, "FrameGen: $msg", Toast.LENGTH_LONG).show()
                    stopService(Intent(this@FrameGenAccessibilityService, ProjectionService::class.java))
                }
            }
        })
        engine = e
        val b = wm.currentWindowMetrics.bounds
        e.start(mp, b.width(), b.height())
    }

    private fun stopInternal() {
        detach()
        engine?.stop()
        engine = null
        root = null
        hud = null
    }

    private fun buildOverlay() {
        val frame = FrameLayout(this)
        val sv = SurfaceView(this)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                engine?.surfaceCreated(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                engine?.surfaceDestroyed()
            }
        })
        frame.addView(
            sv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val tv = TextView(this).apply {
            setTextColor(Color.parseColor("#7CFFB2"))
            setBackgroundColor(Color.parseColor("#99000000"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            val p = (6 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, p / 2)
            text = "FrameGen PVR"
            visibility = if (showHud) android.view.View.VISIBLE else android.view.View.GONE
        }
        val tvLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        tvLp.gravity = Gravity.TOP or Gravity.START
        tvLp.leftMargin = (48 * resources.displayMetrics.density).toInt()
        tvLp.topMargin = (4 * resources.displayMetrics.density).toInt()
        frame.addView(tv, tvLp)

        root = frame
        hud = tv
    }

    private fun applyOverlay(frameArrived: Boolean) {
        val want = captureVisible && !bypass
        if (!want && attached) {
            detach()
        } else if (want && !attached && frameArrived) {
            attach()
        }
    }

    private fun attach() {
        val r = root ?: return
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        p.setFitInsetsTypes(0)
        try {
            wm.addView(r, p)
            attached = true
        } catch (e: Exception) {
            Toast.makeText(this, "Nao foi possivel criar o overlay: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun detach() {
        val r = root
        if (attached && r != null) {
            try {
                wm.removeViewImmediate(r)
            } catch (_: Exception) {
            }
        }
        attached = false
    }
}
