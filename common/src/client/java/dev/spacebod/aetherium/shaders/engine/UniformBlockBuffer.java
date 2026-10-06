package dev.spacebod.aetherium.shaders.engine;

import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;

/**
 * The std140 {@code AetheriumUniforms} bytes of the program whose uniforms are being updated. The uniform
 * classes write through {@code ShaderRenderSystem.uniformXxx(location, ...)} with the member's byte offset as the
 * location; this routes those writes into the right buffer. Render thread only.
 */
public final class UniformBlockBuffer {
	private static @Nullable ByteBuffer current;

	private UniformBlockBuffer() {
	}

	/** Makes {@code buffer} the target of uniform writes until {@link #end()}. */
	public static void begin(ByteBuffer buffer) {
		current = buffer;
	}

	public static void end() {
		current = null;
	}

	/** The buffer a write at {@code offset} goes to, or null when nothing is bound or the uniform is not in the block. */
	public static @Nullable ByteBuffer target(int offset) {
		ByteBuffer b = current;
		return b == null || offset < 0 || offset >= b.capacity() ? null : b;
	}
}
