package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import dev.spacebod.aetherium.shaders.gl.blending.BlendMode;
import dev.spacebod.aetherium.shaders.gl.blending.BlendModeOverride;
import dev.spacebod.aetherium.shaders.gl.blending.BufferBlendInformation;
import dev.spacebod.aetherium.shaders.shaderpack.properties.ProgramDirectives;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * The colour-target state of a pack pipeline, the same way for world, shadow and full-screen programs: per attachment
 * its format, write mask and blending. Blending is the program's {@code blend.<program>} (or the given default, vanilla's
 * for world programs and none for full-screen passes), replaced per colour target by {@code blend.<program>.<buffer>}
 * (the buffer names the target, not the draw-buffer position). Integer formats never blend.
 */
final class PipelineState {
	private PipelineState() {
	}

	/**
	 * One colour-target state per attachment. {@code attachments}: the render pass's attachments as target indices;
	 * {@code written}: the targets the program writes (others are masked off); {@code fallbackBlend}: blending when the
	 * pack sets none for the program.
	 */
	static ColorTargetState[] colorTargets(int[] attachments, IntFunction<GpuFormat> format, int[] written, ProgramDirectives directives,
			Optional<BlendFunction> fallbackBlend, int writeMask) {
		Optional<BlendFunction> blend = programBlend(directives, fallbackBlend);
		Map<Integer, Optional<BlendFunction>> perBuffer = bufferBlends(directives);
		ColorTargetState[] out = new ColorTargetState[attachments.length];
		for (int a = 0; a < attachments.length; a++) {
			int target = attachments[a];
			GpuFormat f = format.apply(target);
			if (indexOf(written, target) < 0) {
				out[a] = new ColorTargetState(Optional.empty(), f, ColorTargetState.WRITE_NONE);
			} else {
				Optional<BlendFunction> b = perBuffer.getOrDefault(target, blend);
				out[a] = new ColorTargetState(WorldProgram.isIntegerFormat(f) ? Optional.empty() : b, f, writeMask);
			}
		}
		return out;
	}

	/** {@code blend.<program>}: off, a blend function, or (not set) the fallback. */
	static Optional<BlendFunction> programBlend(ProgramDirectives directives, Optional<BlendFunction> fallback) {
		Optional<BlendModeOverride> override = directives.getBlendModeOverride();
		return override.isPresent() ? Optional.ofNullable(override.get().blendMode()).map(PipelineState::toBlendFunction) : fallback;
	}

	/** {@code blend.<program>.<buffer>} by target index (colortexN / shadowcolorN). */
	static Map<Integer, Optional<BlendFunction>> bufferBlends(ProgramDirectives directives) {
		Map<Integer, Optional<BlendFunction>> perBuffer = new HashMap<>();
		for (BufferBlendInformation info : directives.getBufferBlendOverrides()) {
			perBuffer.put(info.index(), Optional.ofNullable(info.blendMode()).map(PipelineState::toBlendFunction));
		}
		return perBuffer;
	}

	/** {@link BlendMode} carries OpenGL blend factor enums; vanilla pipelines take BlendFactor. */
	static BlendFunction toBlendFunction(BlendMode mode) {
		return new BlendFunction(factor(mode.srcRgb()), factor(mode.dstRgb()), factor(mode.srcAlpha()), factor(mode.dstAlpha()));
	}

	private static BlendFactor factor(int gl) {
		return switch (gl) {
			case 0 -> BlendFactor.ZERO;
			case 1 -> BlendFactor.ONE;
			case 0x0300 -> BlendFactor.SRC_COLOR;
			case 0x0301 -> BlendFactor.ONE_MINUS_SRC_COLOR;
			case 0x0302 -> BlendFactor.SRC_ALPHA;
			case 0x0303 -> BlendFactor.ONE_MINUS_SRC_ALPHA;
			case 0x0304 -> BlendFactor.DST_ALPHA;
			case 0x0305 -> BlendFactor.ONE_MINUS_DST_ALPHA;
			case 0x0306 -> BlendFactor.DST_COLOR;
			case 0x0307 -> BlendFactor.ONE_MINUS_DST_COLOR;
			case 0x0308 -> BlendFactor.SRC_ALPHA_SATURATE;
			case 0x8001 -> BlendFactor.CONSTANT_COLOR;
			case 0x8002 -> BlendFactor.ONE_MINUS_CONSTANT_COLOR;
			case 0x8003 -> BlendFactor.CONSTANT_ALPHA;
			case 0x8004 -> BlendFactor.ONE_MINUS_CONSTANT_ALPHA;
			default -> throw new IllegalArgumentException("unknown GL blend factor 0x" + Integer.toHexString(gl));
		};
	}

	static int indexOf(int[] array, int value) {
		for (int i = 0; i < array.length; i++) {
			if (array[i] == value) {
				return i;
			}
		}
		return -1;
	}
}
