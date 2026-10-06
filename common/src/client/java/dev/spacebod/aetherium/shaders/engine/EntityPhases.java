package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.spacebod.aetherium.client.mixin.access.PreparedFrameAccessor;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;

/**
 * Vanilla splits a frame's entity draws into a solid phase and translucent phases (translucent models: player skins,
 * eyes, slimes; translucent custom geometry; translucent items), and draws the translucent ones after the translucent
 * terrain. The pack format draws every entity with the entities, before the deferred passes, unless the pack sets
 * {@code separateEntityDraws}: these helpers run those translucent entity phases there, and the rest of vanilla's
 * translucent phases later without them. Render thread only.
 */
public final class EntityPhases {
	/** The translucent entity phases were drawn this frame with the solid entities (vanilla's translucent pass skips them). */
	private static boolean drawnEarly;

	private EntityPhases() {
	}

	/**
	 * Whether this frame's translucent entity phases go with the solid entities, before the deferred passes. Classic
	 * transparency only: improved transparency draws them in its own pass.
	 */
	public static boolean drawWithEntities() {
		return ShaderPackEngine.get().worldActive() && !WorldRenderingSettings.INSTANCE.shouldSeparateEntityDraws()
				&& !Minecraft.getInstance().gameRenderer.useImprovedTransparency();
	}

	/** After vanilla's solid feature phase in the opaque world pass: the translucent entity phases. */
	public static void afterSolid(FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass) {
		drawnEarly = false;
		if (!drawWithEntities()) {
			return;
		}
		drawTranslucentEntities(frame, pass);
		drawnEarly = true;
	}

	/**
	 * The translucent entity phases: translucent models, translucent custom geometry and translucent items. With improved
	 * transparency the three are vanilla's single OIT phase, drawn once.
	 */
	public static void drawTranslucentEntities(FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass) {
		var access = (PreparedFrameAccessor) (Object) frame;
		var context = access.aetherium$context();
		for (SubmitNodeCollection c : access.aetherium$submitNodeStorage().getSubmitsPerOrder().values()) {
			access.aetherium$executePhase(c.translucentModels, context, pass);
			if (c.translucentCustomGeometry != c.translucentModels) {
				access.aetherium$executePhase(c.translucentCustomGeometry, context, pass);
			}
			if (c.translucentBlocksAndItems != c.translucentModels && c.translucentBlocksAndItems != c.translucentCustomGeometry) {
				access.aetherium$executePhase(c.translucentBlocksAndItems, context, pass);
			}
		}
	}

	/**
	 * In place of vanilla's {@code executeTranslucent}: the same phases in the same order, without the translucent
	 * entity phases when they were drawn with the solid entities. Returns false when vanilla's should run unchanged.
	 */
	public static boolean executeTranslucentRest(FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass) {
		if (!drawnEarly) {
			return false;
		}
		drawnEarly = false;
		var access = (PreparedFrameAccessor) (Object) frame;
		var context = access.aetherium$context();
		var collections = access.aetherium$submitNodeStorage().getSubmitsPerOrder().values();
		for (SubmitNodeCollection c : collections) {
			access.aetherium$executePhase(c.shadows, context, pass);
			access.aetherium$executePhase(c.nameTags, context, pass);
			access.aetherium$executePhase(c.texts, context, pass);
		}
		for (SubmitNodeCollection c : collections) {
			access.aetherium$executePhase(c.shapeOutlines, context, pass);
			access.aetherium$executePhase(c.translucentGizmos, context, pass);
		}
		for (SubmitNodeCollection c : collections) {
			access.aetherium$executePhase(c.breakingOverlay, context, pass);
			access.aetherium$executePhase(c.waterMask, context, pass);
		}
		return true;
	}
}
