package dev.spacebod.aetherium.client.mixin.shaders.ids;

import dev.spacebod.aetherium.client.mixin.access.RenderTypeAccessor;
import dev.spacebod.aetherium.shaders.helpers.DrawIds;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Aetherium Shaders: geometry with draw flags ({@link DrawIds}: block entities, shadow-only casters, the camera's
 * player) goes into draws of its own, under a copy of its render type named {@code aetherium_draw:<flags>:...}, so the
 * engine can treat those draws apart: block-entity programs ({@code gbuffers_block}, {@code gbuffers_block_translucent}),
 * skipped in the gbuffer passes, or in the shadow pass per the pack's directives. The copy shares the original's setup
 * (pipeline, textures, outline).
 */
@Mixin(RenderTypeFeatureRenderer.class)
abstract class DrawRenderTypeMixin {
	@Unique
	@SuppressWarnings("unchecked")
	private static final Map<RenderType, RenderType>[] WRAPPED = new Map[DrawIds.COMBINATIONS];

	static {
		for (int i = 0; i < WRAPPED.length; i++) {
			WRAPPED[i] = new IdentityHashMap<>();
		}
	}

	@ModifyVariable(method = "getVertexBuilder", at = @At("HEAD"), argsOnly = true)
	private RenderType aetherium$flaggedRenderType(RenderType renderType) {
		int flags = DrawIds.flags();
		if (flags == 0) {
			return renderType;
		}
		int key = flags & DrawIds.COMBINATIONS - 1;
		Map<RenderType, RenderType> wrapped = WRAPPED[key];
		RenderType copy = wrapped.get(renderType);
		if (copy == null) {
			RenderTypeAccessor access = (RenderTypeAccessor) renderType;
			copy = RenderTypeAccessor.aetherium$create(DrawIds.PREFIX + Integer.toHexString(key) + ":" + access.aetherium$name(),
					access.aetherium$state());
			wrapped.put(renderType, copy);
		}
		return copy;
	}
}
