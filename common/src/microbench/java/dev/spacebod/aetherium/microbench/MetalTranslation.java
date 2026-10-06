package dev.spacebod.aetherium.microbench;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;

/**
 * macOS: MoltenVK turns every SPIR-V module into Metal Shading Language with SPIRV-Cross when the pipeline is created.
 * This runs the same translation (vanilla's own SPIRV-Cross build, lwjgl-spvc) on the harness's modules, so a pack
 * stage Metal cannot express fails here instead of as a pipeline error in game. The MSL is then compiled by Metal itself
 * ({@code benchmarks/tools/metal-compile.swift}, the runtime compiler MoltenVK calls), which catches what SPIRV-Cross
 * passes through, such as a pack local named like a C++ keyword. Not every MoltenVK option is mirrored: passing is
 * necessary, not sufficient.
 */
final class MetalTranslation {
	/** MSL 3.1: Apple silicon on macOS 14 and later, the version MoltenVK picks there. */
	private static final int MSL_VERSION = 3 * 10000 + 1 * 100;

	private MetalTranslation() {
	}

	/** The MSL for {@code spirv}, or SPIRV-Cross's error. */
	record Result(String msl, String error) {
		static Result failed(String error) {
			return new Result(null, error);
		}
	}

	static Result translate(ByteBuffer spirv) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.mallocPointer(1);
			if (Spvc.spvc_context_create(out) != Spvc.SPVC_SUCCESS) {
				return Result.failed("SPIRV-Cross context");
			}
			long context = out.get(0);
			IntBuffer words = MemoryUtil.memAllocInt(spirv.remaining() / 4);
			try {
				words.put(spirv.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer()).flip();
				if (Spvc.spvc_context_parse_spirv(context, words, words.remaining(), out) != Spvc.SPVC_SUCCESS) {
					return Result.failed(Spvc.spvc_context_get_last_error_string(context));
				}
				long ir = out.get(0);
				if (Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out) != Spvc.SPVC_SUCCESS) {
					return Result.failed(Spvc.spvc_context_get_last_error_string(context));
				}
				long compiler = out.get(0);
				Spvc.spvc_compiler_create_compiler_options(compiler, out);
				long options = out.get(0);
				Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION);
				Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS);
				Spvc.spvc_compiler_install_compiler_options(compiler, options);
				if (Spvc.spvc_compiler_compile(compiler, out) != Spvc.SPVC_SUCCESS) {
					return Result.failed(Spvc.spvc_context_get_last_error_string(context));
				}
				return new Result(MemoryUtil.memUTF8(out.get(0)), null);
			} finally {
				MemoryUtil.memFree(words);
				Spvc.spvc_context_destroy(context);
			}
		}
	}
}
