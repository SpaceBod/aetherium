package dev.spacebod.aetherium.client.gpu;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

/** GLSL to SPIR-V with the shaderc that vanilla 26.3 already ships (lwjgl-shaderc). */
public final class ShaderCompiler {
	private ShaderCompiler() {
	}

	/**
	 * {@link #compute} for a pack's compute program: read-before-written variables and undefined values zeroed when
	 * {@code shaders.zero_locals} is on ({@link PackModules#patch}). Same ownership.
	 */
	public static ByteBuffer packCompute(String name, String source) {
		return PackModules.patch(compute(name, source));
	}

	/** Compiles a compute shader for Vulkan 1.2; the returned buffer is native memory owned by the caller ({@link MemoryUtil#memFree}). */
	public static ByteBuffer compute(String name, String source) {
		ByteBuffer code = compute(name, source, Shaderc.shaderc_optimization_level_performance);
		if (!validCompositeConstants(code)) {
			// shaderc's optimizer can emit a vec3 constant with four constituents in large compute programs.
			MemoryUtil.memFree(code);
			AetheriumShaders.logger.info("[{}] shader optimizer emitted an invalid vector constant; recompiling without optimization", name);
			code = compute(name, source, Shaderc.shaderc_optimization_level_zero);
			if (!validCompositeConstants(code)) {
				MemoryUtil.memFree(code);
				throw new IllegalStateException("Compiling " + name + " produced an invalid composite constant");
			}
		}
		return code;
	}

	/** Checks the vector/matrix constant shapes before handing shaderc's output to the driver. */
	public static boolean validCompositeConstants(ByteBuffer spirv) {
		var words = spirv.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
		Map<Integer, Integer> components = new HashMap<>();
		for (int at = 5; at < words.limit();) {
			int count = words.get(at) >>> 16;
			int opcode = words.get(at) & 0xFFFF;
			if (count == 0 || at + count > words.limit()) {
				return false;
			}
			if ((opcode == 23 || opcode == 24) && count == 4) {
				components.put(words.get(at + 1), words.get(at + 3));
			} else if ((opcode == 44 || opcode == 51) && count >= 3) {
				Integer expected = components.get(words.get(at + 1));
				if (expected != null && count - 3 != expected) {
					return false;
				}
			}
			at += count;
		}
		return true;
	}

	private static ByteBuffer compute(String name, String source, int optimization) {
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		try {
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
			Shaderc.shaderc_compile_options_set_optimization_level(options, optimization);
			// Through native heap memory: large sources overflow LWJGL's thread stack.
			ByteBuffer sourceBytes = MemoryUtil.memUTF8(source, false);
			ByteBuffer nameBytes = MemoryUtil.memUTF8(name);
			ByteBuffer entryBytes = MemoryUtil.memUTF8("main");
			long result;
			try {
				result = Shaderc.shaderc_compile_into_spv(compiler, sourceBytes, Shaderc.shaderc_compute_shader, nameBytes, entryBytes, options);
			} finally {
				MemoryUtil.memFree(sourceBytes);
				MemoryUtil.memFree(nameBytes);
				MemoryUtil.memFree(entryBytes);
			}
			try {
				if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
					throw new IllegalStateException("Compiling " + name + " failed: " + Shaderc.shaderc_result_get_error_message(result));
				}
				ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
				ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
				copy.put(bytes).flip();
				return copy;
			} finally {
				Shaderc.shaderc_result_release(result);
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}
}
