package dev.spacebod.aetherium.client.mixin.shaders;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Aetherium Shaders: remembers which vanilla pipeline each compiled pipeline is, so a gbuffer pass can replace it. */
@Mixin(RenderSystem.class)
abstract class RenderSystemMixin {
	@Inject(method = "getCompiledPipelineNullable", at = @At("RETURN"))
	private static void aetherium$record(RenderPipeline pipeline, CallbackInfoReturnable<CompiledRenderPipeline> cir) {
		ShaderPackEngine.get().onCompiledPipeline(pipeline, cir.getReturnValue());
	}
}
