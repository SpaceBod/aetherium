package dev.spacebod.aetherium.client.mixin.opt.gpu.pipeline_cache;

import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import dev.spacebod.aetherium.client.gpu.PipelineCacheStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** {@code gpu.pipeline_cache}: graphics pipelines are created through the device's pipeline cache. */
@Mixin(VulkanRenderPipeline.class)
abstract class VulkanRenderPipelineMixin {
	@ModifyArg(method = "compile", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VK12;vkCreateGraphicsPipelines(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"),
			index = 1)
	private static long aetherium$pipelineCache(long pipelineCache) {
		return PipelineCacheStore.handle();
	}
}
