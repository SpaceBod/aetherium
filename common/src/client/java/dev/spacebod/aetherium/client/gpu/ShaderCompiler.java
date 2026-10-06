package dev.spacebod.aetherium.client.gpu;

import java.nio.ByteBuffer;
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
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		try {
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
			Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
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
