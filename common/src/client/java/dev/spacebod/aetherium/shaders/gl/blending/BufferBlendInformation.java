package dev.spacebod.aetherium.shaders.gl.blending;

/** A blend override for one draw buffer ({@code blend.<program>.<buffer>}). */
public record BufferBlendInformation(int index, BlendMode blendMode) {
}
