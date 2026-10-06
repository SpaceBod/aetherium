package dev.spacebod.aetherium.client.mixin.opt.gpu.pipeline_cache;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import dev.spacebod.aetherium.client.gpu.PipelineCacheStore;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code gpu.pipeline_cache}: the device's pipeline cache, created with it (from disk) and saved when it closes. */
@Mixin(VulkanDevice.class)
abstract class VulkanDeviceMixin {
	@Shadow
	@Final
	private VkDevice vkDevice;

	/** Just before vanilla closes the physical device: its properties identify the saved cache data. */
	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;close()V"))
	private void aetherium$createPipelineCache(CallbackInfo ci, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) VulkanPhysicalDevice physicalDevice) {
		PipelineCacheStore.create(vkDevice, physicalDevice.vkPhysicalDeviceProperties());
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void aetherium$savePipelineCache(CallbackInfo ci) {
		PipelineCacheStore.destroy();
	}
}
