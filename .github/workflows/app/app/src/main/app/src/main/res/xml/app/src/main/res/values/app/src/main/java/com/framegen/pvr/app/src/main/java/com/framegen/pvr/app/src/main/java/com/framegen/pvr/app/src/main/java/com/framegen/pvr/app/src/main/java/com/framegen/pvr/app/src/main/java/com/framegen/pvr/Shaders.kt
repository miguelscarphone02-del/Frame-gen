package com.framegen.pvr

/**
 * Pipeline (OpenGL ES 3.0), por quadro capturado:
 *   OES -> RGBA (frame) -> luma L1 (1/4) -> L2 (1/8) -> L3 (1/16)   [L1..L3 em R8]
 *   busca grossa em L3 (+-R) -> refino em L2 (+-1) -> refino em L1 (+-1)
 *   -> mapa de movimento em 1/4 da resolucao
 *   interpolacao: quadro do meio entre anterior e atual
 *   extrapolacao: quadro atual avancado meio passo (baixa latencia)
 *
 * Vetores: rg = v/64 + 0.5 (texels do proprio nivel), b = custo medio.
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

    /** Quadro RGBA -> luminancia (grava em textura R8). Caixa 4x4. */
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

    /** Luminancia R8 -> luminancia R8 (media 2x2). */
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
     * Busca grossa no nivel mais pequeno (L3): janela 3x3 do quadro atual,
     * procura +-R texels no anterior (menor SAD). atual(x) ~= anterior(x + v).
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
  o = vec4(bv / 64.0 + 0.5, best / 9.0, 1.0);
}
"""
        )
        return sb.toString()
    }

    /**
     * Refino: parte do vetor do nivel mais grosso (x2) e testa +-1 texel,
     * janela 3x3. Serve para L2 e L1 (o nivel grosso tem metade do tamanho).
     */
    fun fragRefine(): String {
        val win = ArrayList<Pair<Int, Int>>()
        for (j in -1..1) for (i in -1..1) win.add(i to j)
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
  vec4 cv = texelFetch(uCoarse, ivec2(gl_FragCoord.xy) / 2, 0);
  vec2 base = floor((cv.rg - 0.5) * 64.0 + 0.5) * 2.0;
"""
        )
        win.forEachIndexed { k, (i, j) ->
            sb.append("  float a$k = texture(uCur, vUv + vec2(${i}.0, ${j}.0) * uTexel).r;\n")
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
        win.forEachIndexed { k, (i, j) ->
            sb.append("      sad += abs(a$k - texture(uPrev, p + vec2(${i}.0, ${j}.0) * uTexel).r);\n")
        }
        sb.append(
            """      sad += uLambda * length(d);
      if (sad < best) { best = sad; bv = base + d; }
    }
  }
  o = vec4(bv / 64.0 + 0.5, best / 9.0, 1.0);
}
"""
        )
        return sb.toString()
    }

    /**
     * Interpolacao (t = 0.5): mistura atual deslocado e anterior deslocado.
     * Onde os dois discordam ou o custo do movimento e alto, usa o quadro atual.
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
    vec3 fl = texture(uFlow, vUv).rgb;
    vec2 off = (fl.rg - 0.5) * 64.0 * uInvL1;
    vec3 a = texture(uCur,  vUv - 0.5 * off).rgb;
    vec3 b = texture(uPrev, vUv + 0.5 * off).rgb;
    vec3 c0 = texture(uCur, vUv).rgb;
    float err = dot(abs(a - b), vec3(0.3333));
    float w = max(smoothstep(uErr.x, uErr.y, err), smoothstep(0.10, 0.25, fl.b));
    o = vec4(mix(0.5 * (a + b), c0, w), 1.0);
}
"""

    /**
     * Extrapolacao (baixa latencia): quadro atual avancado meio passo.
     * out(y) = atual(y + v/2). Onde a previsao e duvidosa, repete o quadro atual.
     */
    const val FRAG_EXTRAP = """#version 300 es
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
    vec3 fl = texture(uFlow, vUv).rgb;
    vec2 off = (fl.rg - 0.5) * 64.0 * uInvL1;
    vec3 c0 = texture(uCur, vUv).rgb;
    vec2 q = vUv + 0.5 * off;
    vec3 e = texture(uCur, q).rgb;
    vec3 p = texture(uPrev, q + off).rgb;
    float err = dot(abs(e - p), vec3(0.3333));
    float w = max(smoothstep(uErr.x, uErr.y, err), smoothstep(0.10, 0.25, fl.b));
    o = vec4(mix(e, c0, w), 1.0);
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
