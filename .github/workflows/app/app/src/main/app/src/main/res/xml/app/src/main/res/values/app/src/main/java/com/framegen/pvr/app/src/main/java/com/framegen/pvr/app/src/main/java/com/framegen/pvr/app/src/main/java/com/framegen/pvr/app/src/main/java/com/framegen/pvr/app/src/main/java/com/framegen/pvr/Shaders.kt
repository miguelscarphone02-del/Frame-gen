package com.framegen.pvr

/**
 * Todos os shaders do pipeline (OpenGL ES 3.0).
 *
 * Pipeline por quadro capturado:
 *   OES -> RGBA (frame)  ->  luma 1/4 (L1, R8)  ->  luma 1/8 (L2, R8)
 *   block matching grosso em L2  ->  refino em L1  ->  mapa de movimento
 *   quadro intermediario = media de (atual deslocado, anterior deslocado)
 *   com fallback para o quadro atual onde a compensacao falha (oclusao).
 *
 * Nos shaders de busca, so os acumuladores de comparacao usam mediump;
 * coordenadas de textura ficam em highp.
 */
object Shaders {

    const val VERT = """#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float(gl_VertexID & 1) * 2.0 - 1.0, float((gl_VertexID >> 1) & 1) * 2.0 - 1.0);
    vUv = p * 0.5 + 0.5;
    gl_Position = vec4(p, 0.0, 1.0);
}
"""

    const val FRAG_OES = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform mat4 uTexMatrix;
in vec2 vUv;
out vec4 o;
void main() {
    vec2 uv = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;
    o = vec4(texture(uTex, uv).rgb, 1.0);
}
"""

    /** Quadro RGBA -> luminancia (grava no canal R de uma textura R8). Caixa 4x4. */
    const val FRAG_DOWN = """#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D uSrc;
uniform vec2 uOff;
in vec2 vUv;
out vec4 o;
void main() {
    vec3 c = texture(uSrc, vUv + vec2(-uOff.x, -uOff.y)).rgb
           + texture(uSrc, vUv + vec2( uOff.x, -uOff.y)).rgb
           + texture(uSrc, vUv + vec2(-uOff.x,  uOff.y)).rgb
           + texture(uSrc, vUv + vec2( uOff.x,  uOff.y)).rgb;
    float l = dot(c * 0.25, vec3(0.299, 0.587, 0.114));
    o = vec4(l, l, l, 1.0);
}
"""

    /** Luminancia R8 -> luminancia R8 (media 2x2). Le so o canal R. */
    const val FRAG_DOWN_R = """#version 300 es
precision mediump float;
precision mediump sampler2D;
uniform sampler2D uSrc;
uniform highp vec2 uOff;
in highp vec2 vUv;
out vec4 o;
void main() {
    float l = (texture(uSrc, vUv + vec2(-uOff.x, -uOff.y)).r
             + texture(uSrc, vUv + vec2( uOff.x, -uOff.y)).r
             + texture(uSrc, vUv + vec2(-uOff.x,  uOff.y)).r
             + texture(uSrc, vUv + vec2( uOff.x,  uOff.y)).r) * 0.25;
    o = vec4(l, l, l, 1.0);
}
"""

    /**
     * Busca grossa em L2: janela 3x3 do quadro atual, procura em +-R texels
     * no quadro anterior (menor SAD). Convencao: atual(x) ~= anterior(x + v).
     * Saida: rg = v/32 + 0.5 (texels L2), b = custo medio.
     */
    fun fragCoarse(r: Int): String {
        val win = ArrayList<Pair<Int, Int>>()
        for (j in -1..1) for (i in -1..1) win.add(i to j)
        val sb = StringBuilder()
        sb.append(
            """#version 300 es
precision mediump float;
precision mediump sampler2D;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform highp vec2 uTexel;
uniform float uLambda;
in highp vec2 vUv;
out vec4 o;
void main() {
"""
        )
        win.forEachIndexed { k, (i, j) ->
            sb.append("  float a$k = texture(uCur, vUv + vec2(${i}.0, ${j}.0) * uTexel).r;\n")
        }
        sb.append(
            """  float best = 1000.0;
  vec2 bv = vec2(0.0);
  for (int cy = -$r; cy <= $r; cy++) {
    for (int cx = -$r; cx <= $r; cx++) {
      vec2 c = vec2(float(cx), float(cy));
      highp vec2 p = vUv + c * uTexel;
      float sad = 0.0;
"""
        )
        win.forEachIndexed { k, (i, j) ->
            sb.append("      sad += abs(a$k - texture(uPrev, p + vec2(${i}.0, ${j}.0) * uTexel).r);\n")
        }
        sb.append(
            """      sad += uLambda * length(c);
      if (sad < best) { best = sad; bv = c; }
    }
  }
  o = vec4(bv / 32.0 + 0.5, best / 9.0, 1.0);
}
"""
        )
        return sb.toString()
    }

    /**
     * Refino em L1: parte do vetor grosso (x2) e testa +-1 texel, janela 4x4.
     * Saida: rg = v/32 + 0.5 (texels L1).
     */
    fun fragRefine(): String {
        val offs = floatArrayOf(-1.5f, -0.5f, 0.5f, 1.5f)
        val sb = StringBuilder()
        sb.append(
            """#version 300 es
precision mediump float;
precision mediump sampler2D;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform highp sampler2D uCoarse;
uniform highp vec2 uTexel;
uniform float uLambda;
in highp vec2 vUv;
out vec4 o;
void main() {
  vec4 cv = texelFetch(uCoarse, ivec2(gl_FragCoord.xy), 0);
  vec2 base = floor((cv.rg - 0.5) * 32.0 + 0.5) * 2.0;
"""
        )
        var k = 0
        for (oy in offs) for (ox in offs) {
            sb.append("  float a$k = texture(uCur, vUv + vec2($ox, $oy) * uTexel).r;\n")
            k++
        }
        sb.append(
            """  float best = 1000.0;
  vec2 bv = base;
  for (int dy = -1; dy <= 1; dy++) {
    for (int dx = -1; dx <= 1; dx++) {
      vec2 d = vec2(float(dx), float(dy));
      highp vec2 p = vUv + (base + d) * uTexel;
      float sad = 0.0;
"""
        )
        k = 0
        for (oy in offs) for (ox in offs) {
            sb.append("      sad += abs(a$k - texture(uPrev, p + vec2($ox, $oy) * uTexel).r);\n")
            k++
        }
        sb.append(
            """      sad += uLambda * length(d);
      if (sad < best) { best = sad; bv = base + d; }
    }
  }
  o = vec4(bv / 32.0 + 0.5, best / 16.0, 1.0);
}
"""
        )
        return sb.toString()
    }

    const val FRAG_MID = """#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform sampler2D uFlow;
uniform vec2 uInvL1;
uniform vec2 uErr;
in vec2 vUv;
out vec4 o;
void main() {
    vec2 v = (texture(uFlow, vUv).rg - 0.5) * 32.0;
    vec2 off = v * uInvL1;
    vec3 a = texture(uCur,  vUv - 0.5 * off).rgb;
    vec3 b = texture(uPrev, vUv + 0.5 * off).rgb;
    vec3 c0 = texture(uCur, vUv).rgb;
    float err = dot(abs(a - b), vec3(0.3333));
    float w = smoothstep(uErr.x, uErr.y, err);
    o = vec4(mix(0.5 * (a + b), c0, w), 1.0);
}
"""

    const val FRAG_PRESENT = """#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform float uSharp;
in vec2 vUv;
out vec4 o;
void main() {
    vec3 c = texture(uTex, vUv).rgb;
    if (uSharp <= 0.001) { o = vec4(c, 1.0); return; }
    vec3 n = texture(uTex, vUv + vec2(0.0,  uTexel.y)).rgb;
    vec3 s = texture(uTex, vUv + vec2(0.0, -uTexel.y)).rgb;
    vec3 e = texture(uTex, vUv + vec2( uTexel.x, 0.0)).rgb;
    vec3 w = texture(uTex, vUv + vec2(-uTexel.x, 0.0)).rgb;
    vec3 mn = min(c, min(min(n, s), min(e, w)));
    vec3 mx = max(c, max(max(n, s), max(e, w)));
    vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.001)), 0.0, 1.0));
    vec3 wgt = -amp * mix(0.125, 0.2, uSharp);
    vec3 res = (c + (n + s + e + w) * wgt) / (1.0 + 4.0 * wgt);
    o = vec4(clamp(res, 0.0, 1.0), 1.0);
}
"""
}
