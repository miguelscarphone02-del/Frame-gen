package com.framegen.pvr

import android.content.Context

/** Estado compartilhado entre os dois servicos (mesmo processo). */
object Bus {
    @Volatile var overlay: FrameGenAccessibilityService? = null
    @Volatile var running: Boolean = false
}

/** Configuracoes ja resolvidas, lidas uma vez quando a captura comeca. */
data class FgConfig(
    val radius: Int,          // raio da busca grossa (texels do nivel 1/16)
    val scale: Float,         // escala da resolucao de captura/processamento
    val sharpen: Float,       // 0..1
    val hud: Boolean,
    val bypassHigh: Boolean,  // se o jogo ja passa de ~50 FPS, nao interpola
    val interpolate: Boolean,
    val lowLatency: Boolean   // mostra o quadro real na hora e preve o proximo
)

object Prefs {
    private const val FILE = "framegen"

    val QUALITY_NAMES = arrayOf("Rapido (menos GPU)", "Equilibrado", "Alta (mais GPU)")
    val SCALE_NAMES = arrayOf("50%", "67%", "85%", "100%")
    private val SCALES = floatArrayOf(0.5f, 0.67f, 0.85f, 1.0f)
    private val RADII = intArrayOf(4, 5, 6)

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getQuality(c: Context) = sp(c).getInt("quality", 1)
    fun setQuality(c: Context, v: Int) = sp(c).edit().putInt("quality", v).apply()

    fun getScale(c: Context) = sp(c).getInt("scale", 3)
    fun setScale(c: Context, v: Int) = sp(c).edit().putInt("scale", v).apply()

    fun getSharpen(c: Context) = sp(c).getInt("sharpen", 0)
    fun setSharpen(c: Context, v: Int) = sp(c).edit().putInt("sharpen", v).apply()

    fun getHud(c: Context) = sp(c).getBoolean("hud", true)
    fun setHud(c: Context, v: Boolean) = sp(c).edit().putBoolean("hud", v).apply()

    fun getBypass(c: Context) = sp(c).getBoolean("bypass", true)
    fun setBypass(c: Context, v: Boolean) = sp(c).edit().putBoolean("bypass", v).apply()

    fun getInterpolate(c: Context) = sp(c).getBoolean("interp", true)
    fun setInterpolate(c: Context, v: Boolean) = sp(c).edit().putBoolean("interp", v).apply()

    fun getLowLat(c: Context) = sp(c).getBoolean("lowlat", true)
    fun setLowLat(c: Context, v: Boolean) = sp(c).edit().putBoolean("lowlat", v).apply()

    fun load(c: Context): FgConfig = FgConfig(
        radius = RADII[getQuality(c).coerceIn(0, 2)],
        scale = SCALES[getScale(c).coerceIn(0, 3)],
        sharpen = getSharpen(c) / 100f,
        hud = getHud(c),
        bypassHigh = getBypass(c),
        interpolate = getInterpolate(c),
        lowLatency = getLowLat(c)
    )
}
