package dev.spacebod.aetherium.client.gpu;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import java.nio.LongBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * Set 0 of a pack pipeline whose stages read more samplers than MoltenVK can push. Vanilla creates every set-0 layout
 * for push descriptors with all of its bindings visible to every stage; MoltenVK gives each pushed sampler a Metal
 * slot of its own, Metal has 16 per stage, and a stage past that is refused when Metal builds it. On MoltenVK such a
 * pack pipeline's set 0 is created as an ordinary set instead, which MoltenVK binds through a Metal argument buffer,
 * and each draw writes the same descriptors into a set allocated here and binds it in place of the push.
 * <p>
 * Only pack pipelines past the push limit and within what an allocated set may hold change; every other pipeline, and
 * every pipeline on every other driver, is pushed as vanilla pushes it. Sets come from a pool used until it is full;
 * a full pool is destroyed once the GPU has finished the frame that retired it, which also covers every earlier frame.
 * Render thread only.
 */
public final class AllocatedSets {
	/** {@code VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_SET_BIT_KHR}, which vanilla sets on every layout. */
	public static final int PUSH_FLAG = 1;
	private static final int POOL_SETS = 256;
	private static final int POOL_SAMPLERS = POOL_SETS * 64;
	private static final int POOL_BUFFERS = POOL_SETS * 16;
	private static final int POOL_TEXEL_BUFFERS = POOL_SETS * 4;

	private static long pool;
	private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

	private AllocatedSets() {
	}

	/** A pipeline whose set 0 is allocated rather than pushed; 0 = pushed. Implemented on vanilla's Vulkan pipeline. */
	public interface Holder {
		long aetherium$allocatedSetLayout();

		void aetherium$allocatedSetLayout(long setLayout);
	}

	/**
	 * Whether set 0 of pack pipeline {@code name}, whose stages each see {@code samplers} samplers, is allocated rather
	 * than pushed: MoltenVK, past the push limit, within the allocated-set limit.
	 */
	public static boolean wanted(String name, int samplers) {
		if (!DeviceTraits.moltenVk()) {
			return false;
		}
		int pushed = DeviceTraits.perStageSamplers();
		if (pushed <= 0 || samplers <= pushed) {
			return false;
		}
		int allocated = DeviceTraits.allocatedSetSamplers();
		if (samplers > allocated) {
			if (LOGGED.add(name)) {
				AetheriumShaders.logger.warn("{} reads {} samplers in one stage, past the {} MoltenVK can push, and the device takes no more in an "
						+ "allocated set ({}); Metal will refuse it", name, samplers, pushed, allocated);
			}
			return false;
		}
		if (LOGGED.add(name)) {
			AetheriumShaders.logger.info("{} reads {} samplers in one stage, past the {} MoltenVK can push: its set 0 is allocated and bound "
					+ "through an argument buffer", name, samplers, pushed);
		}
		return true;
	}

	/**
	 * Binds {@code writes} (set 0 of {@code pipelineLayout}, whose set layout is {@code setLayout}) as an allocated set,
	 * in place of vanilla's push.
	 */
	public static void bind(VulkanCommandEncoder encoder, VkCommandBuffer commands, int bindPoint, long pipelineLayout, long setLayout,
			VkWriteDescriptorSet.Buffer writes) {
		VkDevice device = commands.getDevice();
		long set = allocate(encoder, device, setLayout);
		for (int i = writes.position(); i < writes.limit(); i++) {
			writes.get(i).dstSet(set);
		}
		VK10.vkUpdateDescriptorSets(device, writes, null);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VK10.vkCmdBindDescriptorSets(commands, bindPoint, pipelineLayout, 0, stack.longs(set), null);
		}
	}

	private static long allocate(VulkanCommandEncoder encoder, VkDevice device, long setLayout) {
		for (int attempt = 0; attempt < 2; attempt++) {
			if (pool == 0) {
				pool = createPool(device);
			}
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
						.descriptorPool(pool).pSetLayouts(stack.longs(setLayout));
				LongBuffer out = stack.mallocLong(1);
				int result = VK10.vkAllocateDescriptorSets(device, info, out);
				if (result == VK10.VK_SUCCESS) {
					return out.get(0);
				}
			}
			// Full: retire it; it is destroyed once the GPU is done with this frame (and so with every earlier one).
			long full = pool;
			pool = 0;
			encoder.queueForDestroy(() -> VK10.vkDestroyDescriptorPool(device, full, null));
		}
		throw new IllegalStateException("cannot allocate a descriptor set from a new pool");
	}

	private static long createPool(VkDevice device) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
			sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(POOL_SAMPLERS);
			sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(POOL_BUFFERS);
			sizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER).descriptorCount(POOL_TEXEL_BUFFERS);
			VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(POOL_SETS).pPoolSizes(sizes);
			LongBuffer out = stack.mallocLong(1);
			int result = VK10.vkCreateDescriptorPool(device, info, null, out);
			if (result != VK10.VK_SUCCESS) {
				throw new IllegalStateException("vkCreateDescriptorPool failed: " + result);
			}
			return out.get(0);
		}
	}
}
