package dev.spacebod.aetherium.client.mixin.shaders.census;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.spacebod.aetherium.client.gpu.FrameCensus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The F3 census ({@link FrameCensus}): every render pass, standalone clear and texture copy the Vulkan backend records,
 * and the end of each frame. A clear of part of a texture is recorded by the backend as a render pass of its own, so it
 * counts as both.
 */
@Mixin(VulkanCommandEncoder.class)
abstract class VulkanCommandEncoderCensusMixin {
	@Inject(method = "createRenderPass", at = @At("HEAD"))
	private void aetherium$countPass(CallbackInfoReturnable<?> cir) {
		FrameCensus.passes++;
	}

	@Inject(method = {
			"clearColorTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;)V",
			"clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V",
			"clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;DIIIII)V",
			"clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V",
			"clearStencilTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;I)V"
	}, at = @At("HEAD"))
	private void aetherium$countClear(CallbackInfo ci) {
		FrameCensus.clears++;
	}

	@Inject(method = {
			"copyTextureToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/textures/GpuTexture;IIIIIII)V",
			"copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V"
	}, at = @At("HEAD"))
	private void aetherium$countCopy(CallbackInfo ci) {
		FrameCensus.copies++;
	}

	@Inject(method = "submit", at = @At("TAIL"))
	private void aetherium$endFrame(CallbackInfo ci) {
		FrameCensus.endFrame();
	}
}
