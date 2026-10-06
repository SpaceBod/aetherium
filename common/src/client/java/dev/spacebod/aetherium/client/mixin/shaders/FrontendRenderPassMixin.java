package dev.spacebod.aetherium.client.mixin.shaders;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import dev.spacebod.aetherium.shaders.chunks.ChunkMeshFormat;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aetherium Shaders: inside a gbuffer pass, every vanilla pipeline is swapped for the pack program assigned to it
 * (or for itself widened to the pack's attachments), and a pack program gets its uniform block and samplers once bound.
 */
@Mixin(FrontendRenderPass.class)
abstract class FrontendRenderPassMixin {
	@ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
	private CompiledRenderPipeline aetherium$substitute(CompiledRenderPipeline pipeline) {
		return ShaderPackEngine.get().substitute(pipeline);
	}

	/**
	 * Pack programs sample the albedo (vanilla's {@code Sampler0}, the chunk renderer's block atlas) with plain texture(): give them the atlas filtering packs expect (see
	 * ShaderPackEngine.albedoSampler), and the albedo's material maps (ShaderPackEngine.onAlbedo).
	 */
	@Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V",
			at = @At("HEAD"), cancellable = true)
	private void aetherium$albedoSampler(String name, GpuTextureView view, GpuSampler sampler, CallbackInfo ci) {
		ShaderPackEngine engine = ShaderPackEngine.get();
		if (view == null || !engine.running() || !name.equals("Sampler0") && !name.equals(ChunkMeshFormat.ALBEDO)) {
			return;
		}
		GpuSampler replacement = engine.albedoSampler(sampler);
		if (replacement != sampler) {
			// Set again with the replacement: that call comes back here and binds the material maps.
			ci.cancel();
			((RenderPass) (Object) this).setUniform(name, view, replacement);
			return;
		}
		// Its material maps (normals, specular) for the bound pack program.
		engine.onAlbedo((RenderPass) (Object) this, view, replacement);
	}

	@Inject(method = "setPipeline", at = @At("TAIL"))
	private void aetherium$bound(CompiledRenderPipeline pipeline, CallbackInfo ci) {
		ShaderPackEngine.get().afterSetPipeline((RenderPass) (Object) this, pipeline);
	}
}
