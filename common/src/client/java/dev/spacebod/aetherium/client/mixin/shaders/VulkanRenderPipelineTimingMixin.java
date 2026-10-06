package dev.spacebod.aetherium.client.mixin.shaders;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import dev.spacebod.aetherium.shaders.engine.LoadTimings;
import java.nio.LongBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Aetherium Shaders' load timing ({@link LoadTimings}): time spent in the driver's graphics pipeline creation. */
@Mixin(VulkanRenderPipeline.class)
abstract class VulkanRenderPipelineTimingMixin {
	@WrapOperation(method = "compile", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VK12;vkCreateGraphicsPipelines(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private static int aetherium$time(VkDevice device, long cache, VkGraphicsPipelineCreateInfo.Buffer info, VkAllocationCallbacks allocator,
			LongBuffer pipelines, Operation<Integer> original) {
		long start = System.nanoTime();
		try {
			return original.call(device, cache, info, allocator, pipelines);
		} finally {
			LoadTimings.add(LoadTimings.Kind.GRAPHICS_PIPELINE, System.nanoTime() - start);
		}
	}
}
