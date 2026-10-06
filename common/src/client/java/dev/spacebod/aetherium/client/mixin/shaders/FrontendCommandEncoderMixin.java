package dev.spacebod.aetherium.client.mixin.shaders;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aetherium Shaders: while a pack draws the world, vanilla's render passes on the main colour target open on the pack's
 * gbuffer attachments instead (every render pass of every backend goes through this one method).
 */
@Mixin(FrontendCommandEncoder.class)
abstract class FrontendCommandEncoderMixin {
	@ModifyVariable(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;",
			at = @At("HEAD"), argsOnly = true)
	private RenderPassDescriptor aetherium$redirect(RenderPassDescriptor descriptor) {
		return ShaderPackEngine.get().redirect(descriptor);
	}

	@Inject(method = "submitRenderPass", at = @At("HEAD"))
	private void aetherium$closed(CallbackInfo ci) {
		ShaderPackEngine.get().onRenderPassClosed();
	}
}
