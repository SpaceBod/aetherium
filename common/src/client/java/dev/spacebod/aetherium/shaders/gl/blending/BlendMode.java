package dev.spacebod.aetherium.shaders.gl.blending;

/** A blend function as OpenGL factor enums (the pack format's {@code blend.<program>} values). */
public record BlendMode(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
}
