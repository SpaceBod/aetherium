package dev.spacebod.aetherium.client.mixin.shaders;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import dev.spacebod.aetherium.client.gpu.AllocatedSets;
import dev.spacebod.aetherium.shaders.engine.StorageSet;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Binding a pack pipeline that has the storage set binds it as set 1. Every bind: set 0 differs per pipeline, which
 * makes the pipeline layouts incompatible for later sets, so a set bound for one pipeline does not carry over. A
 * full-screen pass binding targets as images binds its own set; a world or shadow pass whose programs do binds one
 * resolved at its first pack pipeline.
 * <p>
 * A pack pipeline whose set 0 is allocated rather than pushed ({@link AllocatedSets}, MoltenVK past its sampler slots)
 * gets the same descriptors written into an allocated set at each draw.
 */
@Mixin(VulkanRenderPass.class)
abstract class VulkanRenderPassMixin implements dev.spacebod.aetherium.client.gpu.PassViewport {
	@Shadow
	@Final
	private VkCommandBuffer commandBuffer;

	@Shadow
	protected @Nullable VulkanRenderPipeline pipeline;

	@Shadow
	@Final
	private VulkanCommandEncoder encoder;

	@Redirect(method = "pushDescriptors", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRPushDescriptor;vkCmdPushDescriptorSetKHR(Lorg/lwjgl/vulkan/VkCommandBuffer;IJILorg/lwjgl/vulkan/VkWriteDescriptorSet$Buffer;)V"))
	private void aetherium$allocatedSet(VkCommandBuffer commands, int bindPoint, long layout, int set, VkWriteDescriptorSet.Buffer writes) {
		long setLayout = (Object) pipeline instanceof AllocatedSets.Holder holder ? holder.aetherium$allocatedSetLayout() : 0;
		if (setLayout != 0 && set == 0) {
			AllocatedSets.bind(encoder, commands, bindPoint, layout, setLayout, writes);
		} else {
			KHRPushDescriptor.vkCmdPushDescriptorSetKHR(commands, bindPoint, layout, set, writes);
		}
	}

	@Override
	public void aetherium$setViewport(float x, float y, float width, float height) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			org.lwjgl.vulkan.VkViewport.Buffer viewport = org.lwjgl.vulkan.VkViewport.calloc(1, stack);
			viewport.x(x).y(y).width(width).height(height).minDepth(0.0F).maxDepth(1.0F);
			VK10.vkCmdSetViewport(commandBuffer, 0, viewport);
		}
	}

	/** The set pack pipelines bind in this render pass when it has no pass set ({@link StorageSet#renderPassSet}); 0 = not asked yet. */
	@Unique
	private long aetherium$renderPassSet;

	@Inject(method = "setPipeline", at = @At("TAIL"))
	private void aetherium$bindStorageSet(CallbackInfo ci) {
		VulkanRenderPipeline p = pipeline;
		if (p == null || !StorageSet.has(p.pipelineLayout())) {
			return;
		}
		long set = StorageSet.passSet();
		if (set == 0) {
			if (aetherium$renderPassSet == 0) {
				aetherium$renderPassSet = StorageSet.renderPassSet();
			}
			set = aetherium$renderPassSet;
		}
		if (set != 0) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VK10.vkCmdBindDescriptorSets(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipelineLayout(), 1, stack.longs(set), null);
			}
		}
	}
}
