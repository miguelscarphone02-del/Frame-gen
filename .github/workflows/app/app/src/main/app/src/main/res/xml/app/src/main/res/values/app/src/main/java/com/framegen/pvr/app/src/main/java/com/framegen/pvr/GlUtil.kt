package com.framegen.pvr

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30

/** Textura (RGBA8, R8 ou RGBA16F) + FBO. */
class Target(val tex: Int, val fbo: Int, val w: Int, val h: Int) {
    fun release() {
        GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
    }
}

/** Programa GLSL com cache de uniforms. */
class Prog(vs: String, fs: String) {
    val id: Int
    private val locs = HashMap<String, Int>()

    init {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("Falha ao linkar programa: $log")
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        id = p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Falha ao compilar shader: $log\n---\n$src")
        }
        return s
    }

    fun use() = GLES20.glUseProgram(id)

    private fun loc(name: String): Int = locs.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }

    fun i1(name: String, v: Int) = GLES20.glUniform1i(loc(name), v)
    fun f1(name: String, v: Float) = GLES20.glUniform1f(loc(name), v)
    fun f2(name: String, x: Float, y: Float) = GLES20.glUniform2f(loc(name), x, y)
    fun m4(name: String, m: FloatArray) = GLES20.glUniformMatrix4fv(loc(name), 1, false, m, 0)

    fun delete() = GLES20.glDeleteProgram(id)
}

object GlUtil {

    private fun setupParams(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun createOesTexture(): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
        setupParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        return t[0]
    }

    /**
     * singleChannel = true cria textura R8 (so luminancia).
     * half = true cria textura RGBA16F (para guardar vetores de movimento grandes).
     */
    fun createTarget(w: Int, h: Int, singleChannel: Boolean = false, half: Boolean = false): Target {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        if (singleChannel) {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0,
                GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, null
            )
        } else if (half) {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0,
                GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, null
            )
        } else {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
        }
        setupParams(GLES20.GL_TEXTURE_2D)

        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0
        )
        val st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (st != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("FBO incompleto: 0x${Integer.toHexString(st)} (${w}x$h)")
        }
        return Target(t[0], f[0], w, h)
    }

    fun bindTex2D(unit: Int, tex: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
    }

    fun bindOes(unit: Int, tex: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
    }

    fun bindTarget(t: Target) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.fbo)
        GLES20.glViewport(0, 0, t.w, t.h)
    }

    fun drawQuad() {
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}
