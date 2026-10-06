package dev.spacebod.aetherium.client.mixin.shaders.ids;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import dev.spacebod.aetherium.shaders.helpers.DrawIds;
import dev.spacebod.aetherium.shaders.uniforms.DrawState;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aetherium Shaders: while a feature render type draws, the engine knows it (the shadow directives filter feature
 * draws) and whether it is block-entity geometry (its pipeline gets the block-entity program).
 */
@Mixin(PreparedRenderType.class)
abstract class PreparedRenderTypeMixin {
	@Shadow
	@Final
	private String name;
	@Shadow
	@Final
	private java.util.List<PreparedRenderType.Texture> textures;

	@Inject(method = "draw", at = @At("HEAD"))
	private void aetherium$beginDraw(StagedVertexBuffer.ExecuteInfo info, RenderPass renderPass, RenderPipeline renderPipeline, CallbackInfo ci) {
		ShaderPackEngine engine = ShaderPackEngine.get();
		if (!engine.running()) {
			return;
		}
		engine.setFeatureDraw(true, name.startsWith(DrawIds.PREFIX) ? Character.digit(name.charAt(DrawIds.PREFIX.length()), 16) : 0);
		for (PreparedRenderType.Texture texture : textures) {
			if (texture.name().equals("Sampler0")) {
				DrawState.setAlbedo(texture.textureView());
				break;
			}
		}
	}

	/** Unconditional (two field writes): the pack can stop during the draw. */
	@Inject(method = "draw", at = @At("RETURN"))
	private void aetherium$endDraw(StagedVertexBuffer.ExecuteInfo info, RenderPass renderPass, RenderPipeline renderPipeline, CallbackInfo ci) {
		ShaderPackEngine.get().setFeatureDraw(false, 0);
		DrawState.setAlbedo(null);
	}
}
