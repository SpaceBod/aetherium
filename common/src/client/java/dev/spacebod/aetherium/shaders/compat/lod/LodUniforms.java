package dev.spacebod.aetherium.shaders.compat.lod;

import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformHolder;
import dev.spacebod.aetherium.shaders.uniforms.CapturedRenderingState;
import dev.spacebod.aetherium.shaders.uniforms.SystemTimeUniforms;
import net.minecraft.client.Minecraft;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency.PER_FRAME;

/**
 * The inputs the LOD transformer gives a pack's LOD programs (on top of the common pack uniforms): model-view (the
 * camera's view rotation, LOD vertices are camera-relative), the LOD projection ({@link LodCompat#getProjection}, the
 * same matrix packs read as {@code dhProjection}, so depth written and depth reconstructed agree), the normal matrix,
 * the camera position the per-buffer origins are rebased by, the mod's micro-offset (0.01 block) and its near clip.
 * In the shadow map ({@code dh_shadow}) the shadow model-view and projection take the camera's place.
 */
public final class LodUniforms {
	/** Offset the mod pushes LOD faces out by to keep them from z-fighting (its own vertex shader uses the same). */
	private static final float MICRO_OFFSET = 0.01F;
	/** The value packs' LOD programs are written for. */
	private static final float WORLD_Y_OFFSET = -1000.0F;
	private static final Matrices CAMERA = new Matrices(false);
	private static final Matrices SHADOW = new Matrices(true);
	private static final Vector3f CAMERA_POSITION = new Vector3f();

	private LodUniforms() {
	}

	/** @param shadow a {@code dh_shadow} program: the shadow map's model-view and projection instead of the camera's */
	public static void add(UniformHolder uniforms, boolean shadow) {
		Matrices m = shadow ? SHADOW : CAMERA;
		uniforms
				.uniformMatrix(PER_FRAME, "aeth_ModelViewMatrix", () -> m.current().modelView)
				.uniformMatrix(PER_FRAME, "aeth_ModelViewMatrixInverse", () -> m.current().modelViewInverse)
				.uniformMatrix(PER_FRAME, "aeth_ProjectionMatrix", () -> m.current().projection)
				.uniformMatrix(PER_FRAME, "aeth_ProjectionMatrixInverse", () -> m.current().projectionInverse)
				.uniformMatrix3(PER_FRAME, "aeth_NormalMatrix", () -> m.current().normal)
				.uniform3f(PER_FRAME, "aeth_lodCamera", LodUniforms::camera)
				.uniform1f(PER_FRAME, "mircoOffset", () -> MICRO_OFFSET)
				.uniform1f(PER_FRAME, "worldYOffset", () -> WORLD_Y_OFFSET)
				.uniform1f(PER_FRAME, "clipDistance", LodCompat::getNearPlane);
	}

	private static Vector3f camera() {
		var position = Minecraft.getInstance().gameRenderer.mainCamera().position();
		return CAMERA_POSITION.set((float) position.x, (float) position.y, (float) position.z);
	}

	/**
	 * One set of LOD matrices with inverses and normal matrix, computed at the first read of a frame and shared by every
	 * LOD program that frame. Render thread only; the uniforms copy the values.
	 */
	private static final class Matrices {
		private final boolean shadow;
		private final Matrix4f modelView = new Matrix4f();
		private final Matrix4f modelViewInverse = new Matrix4f();
		private final Matrix4f projection = new Matrix4f();
		private final Matrix4f projectionInverse = new Matrix4f();
		private final Matrix3f normal = new Matrix3f();
		private long frame = Long.MIN_VALUE;

		Matrices(boolean shadow) {
			this.shadow = shadow;
		}

		Matrices current() {
			long now = SystemTimeUniforms.COUNTER.frameId();
			if (now != frame) {
				frame = now;
				ShaderPackEngine engine = ShaderPackEngine.get();
				modelView.set(shadow ? engine.shadowModelView() : CapturedRenderingState.INSTANCE.getGbufferModelView());
				modelView.invert(modelViewInverse);
				modelViewInverse.transpose3x3(normal);
				projection.set(shadow ? engine.shadowProjection() : LodCompat.getProjection());
				projection.invert(projectionInverse);
			}
			return this;
		}
	}
}
