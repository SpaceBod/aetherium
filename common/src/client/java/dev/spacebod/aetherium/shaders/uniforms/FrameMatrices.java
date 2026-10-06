package dev.spacebod.aetherium.shaders.uniforms;

import dev.spacebod.aetherium.shaders.compat.lod.LodCompat;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackDirectives;
import dev.spacebod.aetherium.shaders.shadows.ShadowView;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The matrices packs read ({@code gbufferModelView}, {@code gbufferProjection}, {@code dhProjection}, the shadow
 * matrices), with their inverses and last frame's values, computed once at every frame start for all programs. The
 * previous-frame values advance every frame whether or not a program reads them, so a program drawn only now and then
 * still gets last frame's matrix. On the pack's first frame (and after {@link FrameUpdateNotifier#reset}) the previous
 * matrices are the identity. Render thread only; the returned matrices are reused, callers copy them.
 */
final class FrameMatrices {
	private final PackDirectives directives;

	private final Matrix4f modelView = new Matrix4f();
	private final Matrix4f modelViewInverse = new Matrix4f();
	private final Matrix4f previousModelView = new Matrix4f();
	private final Matrix3f normal = new Matrix3f();

	private final Matrix4f projection = new Matrix4f();
	private final Matrix4f projectionInverse = new Matrix4f();
	private final Matrix4f previousProjection = new Matrix4f();

	private final Matrix4f lodProjection = new Matrix4f();
	private final Matrix4f lodProjectionInverse = new Matrix4f();
	private final Matrix4f previousLodProjection = new Matrix4f();

	private final Matrix4f shadowModelViewInverse = new Matrix4f();
	private final Matrix3f shadowNormal = new Matrix3f();
	private final Matrix4f shadowProjectionInverse = new Matrix4f();

	/** The current matrices hold a frame's values (false before the first frame and after a reset). */
	private boolean hasCurrent;

	FrameMatrices(PackDirectives directives, FrameUpdateNotifier notifier) {
		this.directives = directives;
		notifier.addListener(this::update);
		notifier.addResetListener(() -> hasCurrent = false);
	}

	private void update() {
		if (hasCurrent) {
			previousModelView.set(modelView);
			previousProjection.set(projection);
			previousLodProjection.set(lodProjection);
		} else {
			previousModelView.identity();
			previousProjection.identity();
			previousLodProjection.identity();
		}
		set(modelView, CapturedRenderingState.INSTANCE.getGbufferModelView());
		modelView.invert(modelViewInverse);
		modelViewInverse.transpose3x3(normal);
		set(projection, CapturedRenderingState.INSTANCE.getGbufferProjection());
		projection.invert(projectionInverse);
		lodProjection.set(LodCompat.getProjection());
		lodProjection.invert(lodProjectionInverse);
		ShadowView.modelView(directives).invert(shadowModelViewInverse);
		shadowModelViewInverse.transpose3x3(shadowNormal);
		ShadowView.projection(directives).invert(shadowProjectionInverse);
		hasCurrent = true;
	}

	private static void set(Matrix4f target, Matrix4fc value) {
		if (value == null) {
			target.identity();
		} else {
			target.set(value);
		}
	}

	Matrix4fc modelView() {
		return modelView;
	}

	Matrix4fc modelViewInverse() {
		return modelViewInverse;
	}

	Matrix4fc previousModelView() {
		return previousModelView;
	}

	/** The inverse transpose of the model-view's upper 3x3: the normal matrix of world draws. */
	Matrix3fc normal() {
		return normal;
	}

	Matrix4fc projection() {
		return projection;
	}

	Matrix4fc projectionInverse() {
		return projectionInverse;
	}

	Matrix4fc previousProjection() {
		return previousProjection;
	}

	Matrix4fc lodProjection() {
		return lodProjection;
	}

	Matrix4fc lodProjectionInverse() {
		return lodProjectionInverse;
	}

	Matrix4fc previousLodProjection() {
		return previousLodProjection;
	}

	Matrix4fc shadowModelView() {
		return ShadowView.modelView(directives);
	}

	Matrix4fc shadowModelViewInverse() {
		return shadowModelViewInverse;
	}

	/** The inverse transpose of the shadow model-view's upper 3x3: the normal matrix of terrain drawn into the shadow map. */
	Matrix3fc shadowNormal() {
		return shadowNormal;
	}

	Matrix4fc shadowProjection() {
		return ShadowView.projection(directives);
	}

	Matrix4fc shadowProjectionInverse() {
		return shadowProjectionInverse;
	}
}
