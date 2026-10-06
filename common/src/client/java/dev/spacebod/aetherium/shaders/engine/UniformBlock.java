package dev.spacebod.aetherium.shaders.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Aetherium Shaders: a program's loose (non-opaque) uniforms live in one {@code layout(std140) uniform AetheriumUniforms}
 * block, identical in every stage (the Vulkan lowering builds it on the syntax tree), so pack GLSL compiles under
 * Vulkan rules. Members keep their names, so pack code is untouched; the layout gives the engine every member's std140
 * offset without reflection.
 */
public final class UniformBlock {
	public static final String BLOCK_NAME = "AetheriumUniforms";
	/**
	 * One block member: GLSL type, name, array length (0 = not an array), std140 byte offset, and the declaration's
	 * initialiser ({@code uniform float x = 1.0;}), which a block member cannot carry, so the engine writes it.
	 */
	public record Member(String type, String name, int arrayLength, int offset, @Nullable String initializer) {
	}

	/** The members of a program's block, in declaration order, and its size in bytes (0 = no block). */
	public record Layout(List<Member> members, int size) {
		public Map<String, Member> byName() {
			Map<String, Member> m = new LinkedHashMap<>();
			members.forEach(x -> m.put(x.name(), x));
			return m;
		}
	}

	private UniformBlock() {
	}
}
