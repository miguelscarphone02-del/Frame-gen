package com.framegen.pvr

/**
 * Pipeline (OpenGL ES 3.0), por quadro capturado:
 *   OES -> RGBA (frame) -> luma L1 (1/4) -> L2 (1/8) -> L3 (1/16) -> L4 (1/32)   [R8]
 *   busca grossa em L4 (+-R) -> refino L3 -> L2 -> L1 (+-1 cada)
 *   -> filtro mediano 3x3 -> refino sub-pixel -> mapa final (1/4)
 *   interpolacao: quadro do meio entre anterior e atual
 *   extrapolacao: quadro atual avancado meio passo (baixa latencia)
 *
 * Vetores: rg = v/vs + 0.5 (texels do proprio nivel), b = custo medio.
 * vs = 256 com texturas float16, 64 com texturas de 8 bits.
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
     * Busca grossa no nivel mais pequeno (L4): janela 3x3 do quadro atual,
     * procura +-R texels no anterior (menor SAD). atual(x) ~= anterior(x + v).
     */
    fun fragCoarse(r: Int, vs: Float): String {
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
  o = vec4(bv / $vs + 0.5, best / 9.0, 1.0);
}
"""
        )
        return sb.toString()
    }

    /**
     * Refino: parte do vetor do nivel mais grosso (x2) e testa +-1 texel,
     * janela 3x3. Serve para L3, L2 e L1.
     */
    fun fragRefine(vs: Float): String {
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
  highp vec2 base = floor((cv.rg - 0.5) * $vs + 0.5) * 2.0;
"""
        )
        win.forEachIndexed { k, (i, j) ->
            sb.append("  float a$k = texture(uCur, vUv + vec2(${i}.0, ${j}.0) * uTexel).r;\n")
        }
        sb.append(
            """  float best = 1000.0;
  highp vec2 bv = base;
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
  o = vec4(bv / $vs + 0.5, best / 9.0, 1.0);
}
"""
        )
        return sb.toString()
    }

    /** Filtro mediano 3x3 do mapa de movimento (remove vetores soltos). */
    const val FRAG_MEDIAN = """#version 300 es
precision mediump float;
precision highp sampler2D;
uniform highp sampler2D uFlow;
out vec4 o;

#define s2(a, b) temp = a; a = min(a, b); b = max(temp, b);
#define mn3(a, b, c) s2(a, b); s2(a, c);
#define mx3(a, b, c) s2(b, c); s2(a, c);
#define mnmx3(a, b, c) mx3(a, b, c); s2(a, b);
#define mnmx4(a, b, c, d) s2(a, b); s2(c, d); s2(a, c); s2(b, d);
#define mnmx5(a, b, c, d, e) s2(a, b); s2(c, d); mn3(a, c, e); mx3(b, d, e);
#define mnmx6(a, b, c, d, e, f) s2(a, d); s2(b, e); s2(c, f); mn3(a, b, c); mx3(d, e, f);

vec3 fetchF(ivec2 p, ivec2 hi) {
    return texelFetch(uFlow, clamp(p, ivec2(0), hi), 0).rgb;
}

void main() {
    ivec2 hi = textureSize(uFlow, 0) - 1;
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 v0 = fetchF(p + ivec2(-1, -1), hi);
    vec3 v1 = fetchF(p + ivec2( 0, -1), hi);
    vec3 v2 = fetchF(p + ivec2( 1, -1), hi);
    vec3 v3 = fetchF(p + ivec2(-1,  0), hi);
    vec3 v4 = fetchF(p + ivec2( 0,  0), hi);
    vec3 v5 = fetchF(p + ivec2( 1,  0), hi);
    vec3 v6 = fetchF(p + ivec2(-1,  1), hi);
    vec3 v7 = fetchF(p + ivec2( 0,  1), hi);
    vec3 v8 = fetchF(p + ivec2( 1,  1), hi);
    vec3 temp;
    mnmx6(v0, v1, v2, v3, v4, v5);
    mnmx5(v1, v2, v3, v4, v6);
    mnmx4(v2, v3, v4, v7);
    mnmx3(v3, v4, v8);
    o = vec4(v4, 1.0);
}
"""

    /** Refino sub-pixel em L1: testa +-0.5 texel em volta do vetor ja filtrado. */
    fun fragSubpixel(vs: Float): String {
        val win = ArrayList<Pair<Int, Int>>()
        for (j in -1..1) for (i in -1..1) win.add(i to j)
        val sb = StringBuilder()
        sb.append(
            """#version 300 es
precision mediump float;
precision mediump sampler2D;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform highp sampler2D uFlow;
uniform highp vec2 uTexel;
uniform float uLambda;
in highp vec2 vUv;
out vec4 o;
void main() {
  vec4 fv = texelFetch(uFlow, ivec2(gl_FragCoord.xy), 0);
  highp vec2 base = (fv.rg - 0.5) * $vs;
"""
        )
        win.forEachIndexed { k, (i, j) ->
            sb.append("  float a$k = texture(uCur, vUv + vec2(${i}.0, ${j}.0) * uTexel).r;\n")
        }
        sb.append(
            """  float best = 1000.0;
  highp vec2 bv = base;
  for (int dy = -1; dy <= 1; dy++) {
    for (int dx = -1; dx <= 1; dx++) {
      vec2 d = vec2(float(dx), float(dy)) * 0.5;
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
  o = vec4(bv / $vs + 0.5, best / 9.0, 1.0);
}
"""
        )
        return sb.toString()
    }

    /**
     * Interpolacao (t = 0.5): mistura atual deslocado e anterior deslocado.
     * Usa o quadro atual sem deslocar onde: os dois discordam, o custo do
     * movimento e alto, ou o pixel nao mudou entre os quadros (HUD/parado).
     */
    fun fragMid(vs: Float): String = """#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform sampler2D uFlow;
uniform vec2 uInvL1;
uniform vec2 uErr;
uniform vec2 uCost;
in vec2 vUv;
out vec4 o;
void main() {
    vec3 fl = texture(uFlow, vUv).rgb;
    vec2 off = (fl.rg - 0.5) * $vs * uInvL1;
    vec3 a = texture(uCur,  vUv - 0.5 * off).rgb;
    vec3 b = texture(uPrev, vUv + 0.5 * off).rgb;
    vec3 c0 = texture(uCur, vUv).rgb;
    vec3 p0 = texture(uPrev, vUv).rgb;
    float err = dot(abs(a - b), vec3(0.3333));
    float still = 1.0 - smoothstep(0.004, 0.03, dot(abs(c0 - p0), vec3(0.3333)));
    float w = max(max(smoothstep(uErr.x, uErr.y, err), smoothstep(uCost.x, uCost.y, fl.b)), still);
    o = vec4(mix(0.5 * (a + b), c0, w), 1.0);
}
"""

    /**
     * Extrapolacao (baixa latencia): quadro atual avancado meio passo.
     * Onde a previsao e duvidosa, repete o quadro atual.
     */
    fun fragExtrap(vs: Float): String = """#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform sampler2D uFlow;
uniform vec2 uInvL1;
uniform vec2 uErr;
uniform vec2 uCost;
in vec2 vUv;
out vec4 o;
void main() {
    vec3 fl = texture(uFlow, vUv).rgb;
    vec2 off = (fl.rg - 0.5) * $vs * uInvL1;
    vec3 c0 = texture(uCur, vUv).rgb;
    vec3 p0 = texture(uPrev, vUv).rgb;
    vec2 q = vUv + 0.5 * off;
    vec3 e = texture(uCur, q).rgb;
    vec3 p = texture(uPrev, q + off).rgb;
    float err = dot(abs(e - p), vec3(0.3333));
    float still = 1.0 - smoothstep(0.004, 0.03, dot(abs(c0 - p0), vec3(0.3333)));
    float w = max(max(smoothstep(uErr.x, uErr.y, err), smoothstep(uCost.x, uCost.y, fl.b)), still);
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
