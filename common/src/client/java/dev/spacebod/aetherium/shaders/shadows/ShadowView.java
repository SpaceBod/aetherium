package dev.spacebod.aetherium.shaders.shadows;

import dev.spacebod.aetherium.shaders.compat.lod.LodCompat;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackDirectives;
import dev.spacebod.aetherium.shaders.shaderpack.properties.PackShadowDirectives;
import dev.spacebod.aetherium.shaders.uniforms.SystemTimeUniforms;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The shadow map's view for the current frame: one source for the matrices the shadow pass draws with and the ones packs
 * read ({@code shadowModelView}, {@code shadowProjection} and their inverses), so they cannot disagree. Near and far
 * planes set to -1 follow the render distance, the LOD mod's when it renders (packs then expect shadows out to the LODs).
 * Computed once per frame.
 */
public final class ShadowView {
	private static long frame = Long.MIN_VALUE;
	private static PackDirectives cachedFor;
	private static final Matrix4f modelView = new Matrix4f();
	private static final Matrix4f projection = new Matrix4f();

	private ShadowView() {
	}

	/** The near plane: the pack's, or minus the render distance in blocks when it is -1. */
	public static float near(PackShadowDirectives directives) {
		return Mth.equal(directives.getNearPlane(), -1.0f) ? -renderDistanceBlocks() : directives.getNearPlane();
	}

	/** The far plane: the pack's, or the render distance in blocks when it is -1. */
	public static float far(PackShadowDirectives directives) {
		return Mth.equal(directives.getFarPlane(), -1.0f) ? renderDistanceBlocks() : directives.getFarPlane();
	}

	/** The render distance the planes follow, in blocks (the LOD mod's while it renders). */
	public static float renderDistanceBlocks() {
		return LodCompat.getRenderDistance() * 16.0f;
	}

	/** The shadow model-view for this frame (camera-relative, snapped to the pack's interval). Do not modify. */
	public static Matrix4fc modelView(PackDirectives directives) {
		update(directives);
		return modelView;
	}

	/** The shadow projection for this frame (orthographic, or perspective for packs that set shadowMapFov). Do not modify. */
	public static Matrix4fc projection(PackDirectives directives) {
		update(directives);
		return projection;
	}

	private static void update(PackDirectives directives) {
		long now = SystemTimeUniforms.COUNTER.frameId();
		if (now == frame && cachedFor == directives) {
			return;
		}
		frame = now;
		cachedFor = directives;
		PackShadowDirectives shadow = directives.getShadowDirectives();
		float near = near(shadow);
		float far = far(shadow);
		modelView.set(ShadowRenderer.createShadowModelView(directives.getSunPathRotation(), shadow.getIntervalSize()).last().pose());
		projection.set(shadow.getFov() != null ? ShadowMatrices.createPerspectiveMatrix(shadow.getFov())
				: ShadowMatrices.createOrthoMatrix(shadow.getDistance(), near, far));
	}
}
