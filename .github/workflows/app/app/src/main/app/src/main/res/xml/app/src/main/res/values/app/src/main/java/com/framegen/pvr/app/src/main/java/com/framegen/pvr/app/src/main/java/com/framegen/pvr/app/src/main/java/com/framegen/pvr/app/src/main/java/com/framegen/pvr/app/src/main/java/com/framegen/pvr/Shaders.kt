package com.framegen.pvr

/**
 * Todos os shaders do pipeline (OpenGL ES 3.0).
 *
 * Pipeline por quadro capturado:
 *   OES -> RGBA (frame)  ->  luma 1/4 (L1)  ->  luma 1/8 (L2)
 *   block matching grosso em L2  ->  refino em L1  ->  mapa de movimento
 *   quadro intermediario = media de (atual deslocado, anterior deslocado)
 *   com fallback para o quadro atual onde a compensacao falha (oclusao).
 */
object Shaders {

    /** Triangle strip de 4 vertices gerado so com gl_VertexID (sem VBO). */
    const val VERT = """#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float(gl_VertexID & 1) * 2.0 - 1.0, float((gl_VertexID >> 1) & 1) * 2.0 - 1.0);
    vUv = p * 0.5 + 0.5;
    gl_Position = vec4(p, 0.0, 1.0);
}
"""

    /** Textura externa (MediaProjection) -> textura RGBA normal. */
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

    /**
     * Reducao com 4 amostras bilineares em +-uOff.
     * uOff = 1 texel da origem  -> caixa 4x4 (frame -> L1)
     * uOff = 0.5 texel da origem -> caixa 2x2 (L1 -> L2)
     * Resultado: luminancia replicada em RGB.
     */
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

    /**
     * Busca grossa em L2: para cada pixel, janela 3x3 do quadro atual e
     * procura em +-R texels no quadro anterior (menor SAD).
     * Convencao: atual(x) ~= anterior(x + v).
     * Saida: rg = v/32 + 0.5 (texels L2), b = custo medio.
     */
    fun fragCoarse(r: Int): String {
        val win = ArrayList<Pair<Int, Int>>()
        for (j in -1..1) for (i in -1..1) win.add(i to j)
        val sb = StringBuilder()
        sb.append(
            """#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform vec2 uTexel;
uniform float uLambda;
in vec2 vUv;
out vec4 o;
void main() {
"""
        )
        win.forEachIndexed { k, (i, j) ->
            sb.append("  float a$k = texture(uCur, vUv + vec2(${i}.0, ${j}.0) * uTexel).r;\n")
        }
        sb.append(
            """  float best = 1e9;
  vec2 bv = vec2(0.0);
  for (int cy = -$r; cy <= $r; cy++) {
    for (int cx = -$r; cx <= $r; cx++) {
      vec2 c = vec2(float(cx), float(cy));
      vec2 p = vUv + c * uTexel;
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
precision highp float;
precision highp sampler2D;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform sampler2D uCoarse;
uniform vec2 uTexel;
uniform float uLambda;
in vec2 vUv;
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
            """  float best = 1e9;
  vec2 bv = base;
  for (int dy = -1; dy <= 1; dy++) {
    for (int dx = -1; dx <= 1; dx++) {
      vec2 d = vec2(float(dx), float(dy));
      vec2 p = vUv + (base + d) * uTexel;
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

    /**
     * Quadro intermediario (t = 0.5).
     * v = deslocamento atual->anterior (texels L1). Pixel y do quadro do meio:
     *   atual(y - v/2) e anterior(y + v/2). Se os dois discordam muito
     *   (oclusao / movimento errado) usa o quadro atual sem deslocar.
     */
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

    /** Apresentacao na tela com nitidez adaptativa leve (estilo CAS). */
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
