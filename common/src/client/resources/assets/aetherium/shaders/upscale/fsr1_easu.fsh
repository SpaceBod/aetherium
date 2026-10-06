#version 450 core

// Edge-adaptive spatial upsampling (EASU), the upsampling half of FSR1. The kernel constants are derived here from
// textureSize (the whole source image is upscaled onto the whole output), so the pass needs no uniform block and
// survives a resize unchanged.
//
// The source is the scaled picture; the pass runs at the output size. Everything is in texel space and the kernel is
// symmetric, so the image's row order does not matter.

layout(location = 0) in vec2 aeth_uv;
layout(location = 0) out vec4 aeth_out;

uniform sampler2D aeth_Source;

float aeth_rcp(float x) {
    return uintBitsToFloat(0x7ef07ebbu - floatBitsToUint(x));
}

float aeth_rsq(float x) {
    return uintBitsToFloat(0x5f347d74u - (floatBitsToUint(x) >> 1));
}

void aeth_easuTap(inout vec3 colour, inout float weight, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
    vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * -dir.y + off.y * dir.x) * len;
    float d2 = min(dot(v, v), clp);
    float wB = (2.0 / 5.0) * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = (25.0 / 16.0) * wB - (25.0 / 16.0 - 1.0);
    float w = wB * wA;
    colour += c * w;
    weight += w;
}

void aeth_easuSet(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {
    float lenX = max(abs(lD - lC), abs(lC - lB));
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) * aeth_rcp(lenX), 0.0, 1.0);
    len += lenX * lenX * w;
    float lenY = max(abs(lE - lC), abs(lC - lA));
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) * aeth_rcp(lenY), 0.0, 1.0);
    len += lenY * lenY * w;
}

void main() {
    vec2 inputSize = vec2(textureSize(aeth_Source, 0));
    vec2 texel = 1.0 / inputSize;
    vec2 pp = aeth_uv * inputSize - 0.5;
    vec2 fp = floor(pp);
    pp -= fp;
    // The 12-tap footprint, gathered four texels at a time:
    //    b c
    //  e f g h
    //  i j k l
    //    n o
    vec2 p0 = (fp + vec2(1.0, -1.0)) * texel;
    vec2 p1 = p0 + vec2(-1.0, 2.0) * texel;
    vec2 p2 = p0 + vec2(1.0, 2.0) * texel;
    vec2 p3 = p0 + vec2(0.0, 4.0) * texel;
    vec4 bczzR = textureGather(aeth_Source, p0, 0);
    vec4 bczzG = textureGather(aeth_Source, p0, 1);
    vec4 bczzB = textureGather(aeth_Source, p0, 2);
    vec4 ijfeR = textureGather(aeth_Source, p1, 0);
    vec4 ijfeG = textureGather(aeth_Source, p1, 1);
    vec4 ijfeB = textureGather(aeth_Source, p1, 2);
    vec4 klhgR = textureGather(aeth_Source, p2, 0);
    vec4 klhgG = textureGather(aeth_Source, p2, 1);
    vec4 klhgB = textureGather(aeth_Source, p2, 2);
    vec4 zzonR = textureGather(aeth_Source, p3, 0);
    vec4 zzonG = textureGather(aeth_Source, p3, 1);
    vec4 zzonB = textureGather(aeth_Source, p3, 2);
    // Luma times two: B * 0.5 + (R * 0.5 + G).
    vec4 bczzL = bczzB * 0.5 + (bczzR * 0.5 + bczzG);
    vec4 ijfeL = ijfeB * 0.5 + (ijfeR * 0.5 + ijfeG);
    vec4 klhgL = klhgB * 0.5 + (klhgR * 0.5 + klhgG);
    vec4 zzonL = zzonB * 0.5 + (zzonR * 0.5 + zzonG);
    float bL = bczzL.x;
    float cL = bczzL.y;
    float iL = ijfeL.x;
    float jL = ijfeL.y;
    float fL = ijfeL.z;
    float eL = ijfeL.w;
    float kL = klhgL.x;
    float lL = klhgL.y;
    float hL = klhgL.z;
    float gL = klhgL.w;
    float oL = zzonL.z;
    float nL = zzonL.w;
    // Direction and length, accumulated bilinearly over the four quadrants f, g, j, k.
    vec2 dir = vec2(0.0);
    float len = 0.0;
    aeth_easuSet(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
    aeth_easuSet(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
    aeth_easuSet(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
    aeth_easuSet(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);
    float dirR = dot(dir, dir);
    bool zro = dirR < (1.0 / 32768.0);
    dirR = zro ? 1.0 : aeth_rsq(dirR);
    dir.x = zro ? 1.0 : dir.x;
    dir *= dirR;
    len = len * 0.5;
    len *= len;
    float stretch = dot(dir, dir) * aeth_rcp(max(abs(dir.x), abs(dir.y)));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clp = aeth_rcp(lob);
    // Deringing: the result stays within the 2x2 texels nearest the sample.
    vec3 min4 = min(min(vec3(ijfeR.z, ijfeG.z, ijfeB.z), vec3(klhgR.w, klhgG.w, klhgB.w)),
            min(vec3(ijfeR.y, ijfeG.y, ijfeB.y), vec3(klhgR.x, klhgG.x, klhgB.x)));
    vec3 max4 = max(max(vec3(ijfeR.z, ijfeG.z, ijfeB.z), vec3(klhgR.w, klhgG.w, klhgB.w)),
            max(vec3(ijfeR.y, ijfeG.y, ijfeB.y), vec3(klhgR.x, klhgG.x, klhgB.x)));
    vec3 colour = vec3(0.0);
    float weight = 0.0;
    aeth_easuTap(colour, weight, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, vec3(bczzR.x, bczzG.x, bczzB.x));
    aeth_easuTap(colour, weight, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, vec3(bczzR.y, bczzG.y, bczzB.y));
    aeth_easuTap(colour, weight, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, vec3(ijfeR.x, ijfeG.x, ijfeB.x));
    aeth_easuTap(colour, weight, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, vec3(ijfeR.y, ijfeG.y, ijfeB.y));
    aeth_easuTap(colour, weight, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, vec3(ijfeR.z, ijfeG.z, ijfeB.z));
    aeth_easuTap(colour, weight, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, vec3(ijfeR.w, ijfeG.w, ijfeB.w));
    aeth_easuTap(colour, weight, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, vec3(klhgR.x, klhgG.x, klhgB.x));
    aeth_easuTap(colour, weight, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, vec3(klhgR.y, klhgG.y, klhgB.y));
    aeth_easuTap(colour, weight, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, vec3(klhgR.z, klhgG.z, klhgB.z));
    aeth_easuTap(colour, weight, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, vec3(klhgR.w, klhgG.w, klhgB.w));
    aeth_easuTap(colour, weight, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, vec3(zzonR.z, zzonG.z, zzonB.z));
    aeth_easuTap(colour, weight, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, vec3(zzonR.w, zzonG.w, zzonB.w));
    aeth_out = vec4(min(max4, max(min4, colour * (1.0 / weight))), 1.0);
}
