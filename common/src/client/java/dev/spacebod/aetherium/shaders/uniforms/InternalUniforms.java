package dev.spacebod.aetherium.shaders.uniforms;

import dev.spacebod.aetherium.shaders.engine.VanillaWorldState;
import dev.spacebod.aetherium.shaders.gl.state.FogMode;
import dev.spacebod.aetherium.shaders.gl.uniform.DynamicUniformHolder;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformHolder;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import static dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency.PER_FRAME;

/**
 * Uniforms the shader transformer's own code reads ({@code aeth_*}), not part of the pack format.
 */
public class InternalUniforms {
	private InternalUniforms() {
	}

	public static void addFogUniforms(DynamicUniformHolder uniforms, FogMode fogMode) {
		Vector4f fogColor = new Vector4f();
		uniforms
			// From vanilla's FogData.
			.uniform4f("aeth_FogColor", () -> fogColor.set(VanillaWorldState.fog().color), t -> {})
			.uniform1f("aeth_FogStart", () -> VanillaWorldState.fog().environmentalStart, t -> {})
			.uniform1f("aeth_FogEnd", () -> VanillaWorldState.fog().environmentalEnd, t -> {})
			.uniform1f("aeth_FogDensity", () -> Math.max(0.0F, CapturedRenderingState.INSTANCE.getFogDensity()), t -> {});

		// World programs get their alpha test reference compiled in as a constant; full-screen and compute passes have
		// no alpha test, so a pack reading alphaTestRef there reads 0.
		uniforms.uniform1f("alphaTestRef", () -> 0.0F, t -> {});
	}

	/** The loaded pack's frame matrices (one pack at a time). */
	private static @Nullable FrameMatrices frame;

	static void addOtherUniforms(UniformHolder uniforms, FrameMatrices matrices) {
		frame = matrices;
		uniforms
			.uniformMatrix3(PER_FRAME, "aeth_DefaultNormalMat", matrices::normal)
			.uniformMatrix(PER_FRAME, "aeth_DefaultProjectionMatrixInverse", matrices::projectionInverse)
			.uniformMatrix(PER_FRAME, "aeth_DefaultModelViewMatrixInverse", matrices::modelViewInverse)
			.uniformMatrix(PER_FRAME, "aeth_ShadowModelViewMatrixInverse", matrices::shadowModelViewInverse)
			.uniformMatrix(PER_FRAME, "aeth_ShadowProjectionMatrixInverse", matrices::shadowProjectionInverse);
	}

	/**
	 * The normal matrix of terrain draws ({@code aeth_TerrainNormalMat}): their model-view is the frame's view in the
	 * world passes and the shadow map's view in the shadow pass ({@code shadow}), the same for every draw of the frame.
	 */
	public static void addTerrainMatrices(UniformHolder uniforms, boolean shadow) {
		uniforms
			.uniformMatrix3(PER_FRAME, "aeth_TerrainNormalMat", () -> {
				FrameMatrices m = frame;
				return m == null ? IDENTITY3 : shadow ? m.shadowNormal() : m.normal();
			})
			.uniformMatrix(PER_FRAME, "aeth_TerrainModelViewInverse", () -> {
				FrameMatrices m = frame;
				return m == null ? IDENTITY4 : shadow ? m.shadowModelViewInverse() : m.modelViewInverse();
			});
	}

	private static final Matrix3fc IDENTITY3 = new Matrix3f();
	private static final Matrix4fc IDENTITY4 = new Matrix4f();
}
