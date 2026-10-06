package dev.spacebod.aetherium.shaders.compat.lod;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.spacebod.aetherium.shaders.pipeline.programs.ShaderKey;
import dev.spacebod.aetherium.shaders.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * Level-of-detail terrain from the LOD mod, when it is installed. Safe to call without it: every query then answers as
 * if LODs were absent. Only {@link LodBridge} touches the mod's API, and it is loaded only when the mod is present.
 * <p>
 * Without a shader pack the mod renders on its own (it draws into its own targets and copies the result under vanilla's
 * terrain). With a pack whose {@code dh_terrain}/{@code dh_water} programs exist, the engine re-targets the mod's
 * terrain render pass to the pack's gbuffer attachments (keeping the mod's own depth) and swaps its two terrain
 * pipelines for those programs; the mod's colour copy, fades, fog, ambient occlusion and generic objects are switched
 * off, as the packs expect.
 */
public final class LodCompat {
	/** The namespace of the mod's render pipelines. */
	static final String PIPELINE_NAMESPACE = "distanthorizons";
	/** Labels of the mod's terrain render pass (opaque and transparent alike) and of its generic-object passes. */
	private static final String TERRAIN_PASS = "distantHorizons:TerrainRenderer";
	private static final String GENERIC_PASS = "distantHorizons:GenericObjectRenderer";
	private static final boolean PRESENT = present();

	private LodCompat() {
	}

	private static boolean present() {
		try {
			Class.forName("com.seibel.distanthorizons.api.DhApi", false, LodCompat.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}

	/** Whether the LOD mod is installed. */
	public static boolean installed() {
		return PRESENT;
	}

	/** Client start-up: hooks the mod's render events (no-op without it). */
	public static void init() {
		if (PRESENT) {
			LodBridge.register();
		}
	}

	/** The mod is ready and set to render LODs (its own setting). */
	public static boolean hasRenderingEnabled() {
		return PRESENT && LodBridge.renderingEnabled();
	}

	/**
	 * Called once per frame before the world, with whether the active pack draws the LODs this frame: sets the mod's
	 * deferred transparency, fog and ambient occlusion for the pack, or gives them back.
	 */
	public static void beginFrame(boolean packDrawsLods, boolean packWithoutLodPrograms, boolean hideClouds) {
		if (PRESENT) {
			LodBridge.beginFrame(packDrawsLods, packWithoutLodPrograms, hideClouds);
		}
	}

	/** Draws the mod's transparent LODs (water) now; the mod's own deferred call is skipped while a pack draws them. */
	public static void renderDeferredTransparent() {
		if (PRESENT) {
			LodBridge.renderDeferredTransparent();
		}
	}

	/**
	 * Draws the mod's LODs into the shadow map (the engine redirects its terrain pass there meanwhile): opaque, plus
	 * transparent ones with {@code transparent} (into shadowtex0 only; shadowtex1 holds opaque casters).
	 */
	public static void renderShadow(double cameraX, double cameraZ, double distance, boolean transparent) {
		if (PRESENT) {
			LodBridge.renderShadow(cameraX, cameraZ, distance, transparent);
		}
	}

	/** True inside {@link #renderShadow}. */
	public static boolean inShadowCall() {
		return PRESENT && LodBridge.inShadowCall();
	}

	/** True while the pack draws the LODs: the mod's own fades and deferred call stand down. */
	public static boolean packDrawsLods() {
		return PRESENT && LodBridge.packDrawsLods();
	}

	/** True inside {@link #renderDeferredTransparent} (the one deferred call the mod may run under a pack). */
	public static boolean inEngineDeferredCall() {
		return PRESENT && LodBridge.inEngineDeferredCall();
	}

	/** Whether a render pass about to open is the mod's terrain pass. */
	public static boolean isTerrainPass(RenderPassDescriptor descriptor) {
		return PRESENT && descriptor.colorAttachments().size() == 1 && TERRAIN_PASS.equals(descriptor.label().get());
	}

	/** Whether a render pass about to open draws LOD geometry into the mod's targets (terrain or generic objects). */
	public static boolean isLodPass(RenderPassDescriptor descriptor) {
		if (!PRESENT || descriptor.colorAttachments().size() != 1) {
			return false;
		}
		String label = descriptor.label().get();
		return TERRAIN_PASS.equals(label) || GENERIC_PASS.equals(label);
	}

	/** The pack program for one of the mod's pipelines, or null when it is not a LOD terrain pipeline. */
	public static @Nullable ShaderKey key(RenderPipeline pipeline, boolean shadow) {
		if (!PRESENT || !PIPELINE_NAMESPACE.equals(pipeline.getLocation().getNamespace())) {
			return null;
		}
		return switch (pipeline.getLocation().getPath()) {
			case "opaque_terrain" -> shadow ? ShaderKey.SHADOW_LOD : ShaderKey.LOD_TERRAIN;
			case "transparent_terrain" -> shadow ? ShaderKey.SHADOW_LOD : ShaderKey.LOD_WATER;
			case "generic_objects" -> shadow ? null : ShaderKey.LOD_GENERIC;
			default -> null;
		};
	}

	/** The mod's depth texture (reversed depth, cleared to 0), or null before it exists. */
	public static @Nullable GpuTextureView depthView() {
		return PRESENT ? LodBridge.depthView() : null;
	}

	/**
	 * The projection the pack's LOD programs draw with and read as {@code dhProjection}: the camera's (OpenGL
	 * convention, as packs expect) with the mod's near and far planes. Without LODs, the camera's own.
	 */
	public static Matrix4f getProjection() {
		Matrix4f camera = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection());
		if (!hasRenderingEnabled()) {
			return camera;
		}
		return new Matrix4f().setPerspective(camera.perspectiveFov(), camera.m11() / camera.m00(), getNearPlane(), getFarPlane());
	}

	public static float getNearPlane() {
		return hasRenderingEnabled() ? LodBridge.nearPlane() : 0.01F;
	}

	public static float getFarPlane() {
		return hasRenderingEnabled() ? LodBridge.farPlane() : 0.01F;
	}

	/** LOD render distance in chunks (vanilla's when LODs are off). */
	public static int getRenderDistance() {
		return hasRenderingEnabled() ? LodBridge.chunkRenderDistance() : Minecraft.getInstance().options.getEffectiveRenderDistance();
	}
}
