package dev.spacebod.aetherium.client.mixin.shaders;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import dev.spacebod.aetherium.client.mixin.access.VulkanCommandEncoderAccessor;
import dev.spacebod.aetherium.shaders.engine.PassBarriers;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aetherium Shaders ({@code shaders.narrow_barrier}): a render pass the engine opened closes on the narrow dependency of
 * {@link PassBarriers#afterPass} instead of vanilla's full memory barrier. Vanilla's own passes are untouched.
 */
@Mixin(VulkanCommandEncoder.class)
abstract class VulkanCommandEncoderBarrierMixin {
	@Shadow
	private @Nullable VulkanRenderPass currentRenderPass;

	/** The label of the pass {@code submitRenderPass} is closing (it clears the field before the barrier). */
	@Unique
	private @Nullable Supplier<String> aetherium$closing;

	@Inject(method = "submitRenderPass", at = @At("HEAD"))
	private void aetherium$rememberLabel(CallbackInfo ci) {
		VulkanRenderPass pass = currentRenderPass;
		aetherium$closing = pass == null ? null : pass.getLabel();
	}

	@Redirect(method = "submitRenderPass", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"))
	private void aetherium$afterPass(VulkanCommandEncoder self, MemoryStack stack) {
		Supplier<String> label = aetherium$closing;
		aetherium$closing = null;
		VkCommandBuffer commands = ((VulkanCommandEncoderAccessor) self).aetherium$commandBuffer();
		if (PassBarriers.narrowFor(label)) {
			PassBarriers.afterPass(commands, stack);
		} else {
			VulkanCommandEncoder.memoryBarrier(commands, stack);
		}
	}
}
