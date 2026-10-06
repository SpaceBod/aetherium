package dev.spacebod.aetherium.shaders.gl;

import dev.spacebod.aetherium.client.gpu.StorageFeatures;
import dev.spacebod.aetherium.shaders.engine.UniformBlockBuffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Uniform uploads for the shader pipeline's uniform classes, without OpenGL. A uniform's integer "location" is the
 * member's byte offset in the program's std140 {@code AetheriumUniforms} block (see {@code UniformBlock}); the value
 * is written into the block buffer of the program being prepared ({@link UniformBlockBuffer}). A negative location
 * (the program does not declare the uniform) writes nothing.
 */
public final class ShaderRenderSystem {
	private ShaderRenderSystem() {
	}

	public static void uniform1f(int location, float v0) {
		ByteBuffer b = UniformBlockBuffer.target(location);
		if (b != null) {
			b.putFloat(location, v0);
		}
	}

	public static void uniform1i(int location, int v0) {
		ByteBuffer b = UniformBlockBuffer.target(location);
		if (b != null) {
			b.putInt(location, v0);
		}
	}

	public static void uniform2f(int location, float v0, float v1) {
		ByteBuffer b = UniformBlockBuffer.target(location);
		if (b != null) {
			b.putFloat(location, v0).putFloat(location + 4, v1);
		}
	}

	public static void uniform3f(int location, float v0, float v1, float v2) {
		ByteBuffer b = UniformBlockBuffer.target(location);
		if (b != null) {
			b.putFloat(location, v0).putFloat(location + 4, v1).putFloat(location + 8, v2);
		}
	}

	public static void uniform4f(int location, float v0, float v1, float v2, float v3) {
		ByteBuffer b = UniformBlockBuffer.target(location);
		if (b != null) {
			b.putFloat(location, v0).putFloat(location + 4, v1).putFloat(location + 8, v2).putFloat(location + 12, v3);
		}
	}

	public static void uniform2i(int location, int v0, int v1) {
		ByteBuffer b = UniformBlockBuffer.target(location);
		if (b != null) {
			b.putInt(location, v0).putInt(location + 4, v1);
		}
	}

	public static void uniform3i(int location, int v0, int v1, int v2) {
		ByteBuffer b = UniformBlockBuffer.target(location);
		if (b != null) {
			b.putInt(location, v0).putInt(location + 4, v1).putInt(location + 8, v2);
		}
	}

	public static void uniform4i(int location, int v0, int v1, int v2, int v3) {
		ByteBuffer b = UniformBlockBuffer.target(location);
		if (b != null) {
			b.putInt(location, v0).putInt(location + 4, v1).putInt(location + 8, v2).putInt(location + 12, v3);
		}
	}

	/** Column-major 4x4; std140 lays it out as four vec4 columns, i.e. unchanged. */
	public static void uniformMatrix4fv(int location, boolean transpose, FloatBuffer matrix) {
		ByteBuffer b = UniformBlockBuffer.target(location);
		if (b != null) {
			int base = matrix.position();
			for (int i = 0; i < 16; i++) {
				b.putFloat(location + i * 4, matrix.get(base + (transpose ? (i % 4) * 4 + i / 4 : i)));
			}
		}
	}

	public static void uniformMatrix4fv(int location, boolean transpose, float[] matrix) {
		ByteBuffer b = UniformBlockBuffer.target(location);
		if (b != null) {
			for (int i = 0; i < 16; i++) {
				b.putFloat(location + i * 4, transpose ? matrix[(i % 4) * 4 + i / 4] : matrix[i]);
			}
		}
	}

	/** Column-major 3x3; std140 pads each column to a vec4 (16-byte stride). */
	public static void uniformMatrix3fv(int location, boolean transpose, FloatBuffer matrix) {
		ByteBuffer b = UniformBlockBuffer.target(location);
		if (b != null) {
			int base = matrix.position();
			for (int column = 0; column < 3; column++) {
				for (int row = 0; row < 3; row++) {
					float v = matrix.get(base + (transpose ? row * 3 + column : column * 3 + row));
					b.putFloat(location + column * 16 + row * 4, v);
				}
			}
		}
	}

	public static void uniformMatrix3fv(int location, boolean transpose, float[] matrix) {
		ByteBuffer b = UniformBlockBuffer.target(location);
		if (b != null) {
			for (int column = 0; column < 3; column++) {
				for (int row = 0; row < 3; row++) {
					float v = transpose ? matrix[row * 3 + column] : matrix[column * 3 + row];
					b.putFloat(location + column * 16 + row * 4, v);
				}
			}
		}
	}

	// Capabilities reported to packs through feature flags. Compute programs, storage buffers and custom images run
	// when the device enabled the storage features (vertex/fragment stores, extended image formats); tessellation is
	// not supported.
	public static boolean supportsCompute() {
		return StorageFeatures.compute();
	}

	public static boolean supportsSSBO() {
		return StorageFeatures.imageLoadStore();
	}

	public static boolean supportsImageLoadStore() {
		return StorageFeatures.imageLoadStore();
	}

	public static boolean supportsTesselation() {
		return false;
	}

	/** Buffer blending per draw buffer is part of vanilla pipelines' colour-target state on both backends. */
	public static boolean supportsBufferBlending() {
		return true;
	}
}
