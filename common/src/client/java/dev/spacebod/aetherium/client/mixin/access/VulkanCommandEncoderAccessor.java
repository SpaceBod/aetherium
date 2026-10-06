package dev.spacebod.aetherium.client.mixin.access;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The frame's current command buffer of vanilla's encoder (begun on demand; not inside a render pass). */
@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {
	@Invoker("commandBuffer")
	VkCommandBuffer aetherium$commandBuffer();
}
