package dev.spacebod.aetherium.client.gpu;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Small helpers for Aetherium's own compute pipelines on vanilla's {@code VkDevice}. */
public final class VulkanCompute {
	private VulkanCompute() {
	}

	/** A compute pipeline with one descriptor set of {@code bindingTypes} (binding i = type i) and a push-constant block. */
	public record Pipeline(long setLayout, long layout, long pipeline) {
		public void destroy(VkDevice device) {
			VK10.vkDestroyPipeline(device, pipeline, null);
			VK10.vkDestroyPipelineLayout(device, layout, null);
			VK10.vkDestroyDescriptorSetLayout(device, setLayout, null);
		}
	}

	public static Pipeline pipeline(VkDevice device, String name, String glsl, int[] bindingTypes, int pushBytes) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(bindingTypes.length, stack);
			for (int i = 0; i < bindingTypes.length; i++) {
				bindings.get(i).binding(i).descriptorType(bindingTypes[i]).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
			}
			LongBuffer out = stack.mallocLong(1);
			check(VK10.vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, out),
					name + " set layout");
			long setLayout = out.get(0);
			VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout));
			if (pushBytes > 0) {
				VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
				push.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushBytes);
				layoutInfo.pPushConstantRanges(push);
			}
			check(VK10.vkCreatePipelineLayout(device, layoutInfo, null, out), name + " pipeline layout");
			long layout = out.get(0);
			ByteBuffer spirv = ShaderCompiler.compute(name, glsl);
			long module;
			try {
				check(VK10.vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv), null, out), name + " module");
				module = out.get(0);
			} finally {
				MemoryUtil.memFree(spirv);
			}
			VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
			info.get(0).sType$Default().layout(layout)
					.stage(s -> s.sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main")));
			check(VK10.vkCreateComputePipelines(device, PipelineCacheStore.handle(), info, null, out), name + " pipeline");
			VK10.vkDestroyShaderModule(device, module, null);
			return new Pipeline(setLayout, layout, out.get(0));
		}
	}

	/** A pool for {@code maxSets} sets of up to four descriptors each, of the given types. */
	public static long pool(VkDevice device, int maxSets, int... types) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(types.length, stack);
			for (int i = 0; i < types.length; i++) {
				sizes.get(i).type(types[i]).descriptorCount(maxSets * 4);
			}
			LongBuffer out = stack.mallocLong(1);
			check(VK10.vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(maxSets).pPoolSizes(sizes), null, out),
					"descriptor pool");
			return out.get(0);
		}
	}

	/** Allocates a set, or 0 when the pool is exhausted. */
	public static long allocate(VkDevice device, long pool, long setLayout) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer out = stack.mallocLong(1);
			int result = VK10.vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
					.descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), out);
			return result == VK10.VK_SUCCESS ? out.get(0) : 0;
		}
	}

	public static void writeBuffer(VkDevice device, long set, int binding, long buffer, long offset, long range) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
			info.get(0).buffer(buffer).offset(offset).range(range);
			VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
			write.get(0).sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
					.descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(info);
			VK10.vkUpdateDescriptorSets(device, write, null);
		}
	}

	/** An image in GENERAL layout, as a storage image ({@code sampler == 0}) or a combined image sampler. */
	public static void writeImage(VkDevice device, long set, int binding, long view, long sampler) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
			info.get(0).imageView(view).sampler(sampler).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
			VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
			write.get(0).sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
					.descriptorType(sampler == 0 ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
					.pImageInfo(info);
			VK10.vkUpdateDescriptorSets(device, write, null);
		}
	}

	public static void barrier(VkCommandBuffer cb, int srcStage, int srcAccess, int dstStage, int dstAccess) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack);
			b.get(0).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
			VK10.vkCmdPipelineBarrier(cb, srcStage, dstStage, 0, b, null, null);
		}
	}

	public static int groups(int size, int group) {
		return (size + group - 1) / group;
	}

	public static void check(int result, String what) {
		if (result != VK10.VK_SUCCESS) {
			throw new IllegalStateException("Vulkan: " + what + " failed with VkResult " + result);
		}
	}
}
