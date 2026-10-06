package dev.spacebod.aetherium.shaders.uniforms;

import dev.spacebod.aetherium.shaders.gl.uniform.UniformHolder;

import static dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency.PER_FRAME;

/** {@code gbuffer*}, {@code dh*} and {@code shadow*} matrices, with their inverses and previous-frame values. */
public final class MatrixUniforms {
	private MatrixUniforms() {
	}

	static void addMatrixUniforms(UniformHolder uniforms, FrameMatrices matrices) {
		uniforms
			.uniformMatrix(PER_FRAME, "gbufferModelView", matrices::modelView)
			.uniformMatrix(PER_FRAME, "gbufferModelViewInverse", matrices::modelViewInverse)
			.uniformMatrix(PER_FRAME, "gbufferPreviousModelView", matrices::previousModelView)
			.uniformMatrix(PER_FRAME, "gbufferProjection", matrices::projection)
			.uniformMatrix(PER_FRAME, "gbufferProjectionInverse", matrices::projectionInverse)
			.uniformMatrix(PER_FRAME, "gbufferPreviousProjection", matrices::previousProjection)
			.uniformMatrix(PER_FRAME, "dhProjection", matrices::lodProjection)
			.uniformMatrix(PER_FRAME, "dhProjectionInverse", matrices::lodProjectionInverse)
			.uniformMatrix(PER_FRAME, "dhPreviousProjection", matrices::previousLodProjection)
			// One source with the shadow pass (planes at -1 follow the LOD distance while LODs render).
			.uniformMatrix(PER_FRAME, "shadowModelView", matrices::shadowModelView)
			.uniformMatrix(PER_FRAME, "shadowModelViewInverse", matrices::shadowModelViewInverse)
			.uniformMatrix(PER_FRAME, "shadowProjection", matrices::shadowProjection)
			.uniformMatrix(PER_FRAME, "shadowProjectionInverse", matrices::shadowProjectionInverse);
	}
}
