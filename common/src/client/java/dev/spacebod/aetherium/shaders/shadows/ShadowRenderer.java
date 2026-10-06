package dev.spacebod.aetherium.shaders.shadows;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.spacebod.aetherium.shaders.uniforms.CameraUniforms;
import dev.spacebod.aetherium.shaders.uniforms.CelestialUniforms;
import org.joml.Vector3d;

/** The shadow map's light angle (live, or pinned while the shadow cache reuses a map) and model-view. */
public final class ShadowRenderer {
	private ShadowRenderer() {
	}

	public static PoseStack createShadowModelView(float sunPathRotation, float intervalSize) {
		Vector3d cameraPos = CameraUniforms.getUnshiftedCameraPosition();
		PoseStack modelView = new PoseStack();
		ShadowMatrices.createModelViewMatrix(modelView, getShadowAngle(), intervalSize, sunPathRotation, cameraPos.x, cameraPos.y, cameraPos.z);
		return modelView;
	}

	/**
	 * Shadow cache: while set, the shadow matrices use this light angle (in turns) instead of the live one, so the
	 * matrices packs sample with match the cached map. Set before the frame's uniforms are computed.
	 */
	private static float pinnedAngle = Float.NaN;

	public static void pinShadowAngle(float angle) {
		pinnedAngle = angle;
	}

	public static void unpinShadowAngle() {
		pinnedAngle = Float.NaN;
	}

	/** The live light angle in turns (sun by day, moon by night), ignoring any pin. */
	public static float liveShadowAngle() {
		return CelestialUniforms.getSunAngle(CelestialUniforms.isDay()) / 360.0f;
	}

	private static float getShadowAngle() {
		return Float.isNaN(pinnedAngle) ? liveShadowAngle() : pinnedAngle;
	}
}
