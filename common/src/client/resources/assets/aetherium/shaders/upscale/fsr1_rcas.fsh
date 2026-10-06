#version 450 core

// Robust contrast-adaptive sharpening (RCAS), the sharpening half of FSR1. The sharpness (stops of attenuation,
// 0 = strongest) is a constant the engine substitutes for AETH_RCAS_SHARPNESS when it builds the pipeline; there is
// no noise-detection term.
//
// Runs at the output size on what EASU wrote, texel for texel.

layout(location = 0) in vec2 aeth_uv;
layout(location = 0) out vec4 aeth_out;

uniform sampler2D aeth_Source;

void main() {
    ivec2 last = textureSize(aeth_Source, 0) - 1;
    ivec2 sp = ivec2(gl_FragCoord.xy);
    // The cross of taps:
    //    b
    //  d e f
    //    h
    vec3 b = texelFetch(aeth_Source, clamp(sp + ivec2(0, -1), ivec2(0), last), 0).rgb;
    vec3 d = texelFetch(aeth_Source, clamp(sp + ivec2(-1, 0), ivec2(0), last), 0).rgb;
    vec3 e = texelFetch(aeth_Source, clamp(sp, ivec2(0), last), 0).rgb;
    vec3 f = texelFetch(aeth_Source, clamp(sp + ivec2(1, 0), ivec2(0), last), 0).rgb;
    vec3 h = texelFetch(aeth_Source, clamp(sp + ivec2(0, 1), ivec2(0), last), 0).rgb;
    vec3 mn4 = min(min(b, d), min(f, h));
    vec3 mx4 = max(max(b, d), max(f, h));
    // The lobe that would just reach 0 or 1 at e, per channel; the weakest of them, limited.
    vec3 hitMin = min(mn4, e) / (4.0 * mx4);
    vec3 hitMax = (1.0 - max(mx4, e)) / (4.0 * mn4 - 4.0);
    vec3 lobes = max(-hitMin, hitMax);
    float lobe = max(-(0.25 - 1.0 / 16.0), min(max(lobes.r, max(lobes.g, lobes.b)), 0.0)) * exp2(-AETH_RCAS_SHARPNESS);
    float weight = 1.0 / (4.0 * lobe + 1.0);
    aeth_out = vec4((lobe * (b + d + f + h) + e) * weight, 1.0);
}
