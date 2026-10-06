package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;

/**
 * The barriers the engine records itself ({@link EngineSwitches#NARROW_BARRIER}). Vanilla's encoder ends every render
 * pass with a full memory barrier (all commands, all memory). The engine's own passes, labelled
 * {@value #LABEL_PREFIX}..., instead end on a dependency that names what they leave behind (colour and depth attachment
 * writes, shader storage writes) and everything that may come next (sampling and storage access from any shader stage,
 * attachment loads and stores, transfers, indirect arguments, vertex and uniform reads). Vanilla's own passes keep the
 * full barrier. Off: every pass ends on the full barrier.
 * <p>
 * Every image stays in GENERAL layout (vanilla's rule), so these are memory and execution dependencies only. A source
 * stage covers the stages logically before it, so any read the pass made (vertex input, uniforms, sampled images) is
 * ordered before a later write too.
 */
public final class PassBarriers {
	/** The label prefix of every render pass the engine opens (gbuffer passes included). */
	public static final String LABEL_PREFIX = "Aetherium Shaders";

	/** Mirrors {@link EngineSwitches#NARROW_BARRIER}, set once per frame by the engine. */
	private static volatile boolean narrow;

	private PassBarriers() {
	}

	public static void setNarrow(boolean on) {
		narrow = on;
	}

	public static boolean narrow() {
		return narrow;
	}

	/** Whether a render pass with this label gets the narrow barrier when it closes. */
	public static boolean narrowFor(@Nullable Supplier<String> label) {
		if (!narrow || label == null) {
			return false;
		}
		String name = label.get();
		return name != null && name.startsWith(LABEL_PREFIX);
	}

	private static final long PASS_SOURCE_STAGES = KHRSynchronization2.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT_KHR
			| KHRSynchronization2.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT_KHR
			| KHRSynchronization2.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT_KHR
			| KHRSynchronization2.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT_KHR;
	private static final long PASS_SOURCE_ACCESS = KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT_KHR;
	/**
	 * Everything a later command may do with what a pass or a mip chain left behind (a destination stage covers the
	 * stages logically after it: the geometry and tessellation stages follow the vertex shader).
	 */
	private static final long NEXT_STAGES = KHRSynchronization2.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT_KHR
			| KHRSynchronization2.VK_PIPELINE_STAGE_2_VERTEX_INPUT_BIT_KHR
			| KHRSynchronization2.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT_KHR
			| KHRSynchronization2.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT_KHR
			| KHRSynchronization2.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT_KHR
			| KHRSynchronization2.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT_KHR
			| KHRSynchronization2.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT_KHR
			| KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR
			| KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR;
	private static final long NEXT_ACCESS = KHRSynchronization2.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_UNIFORM_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR;

	/** After an engine render pass: its attachment and storage writes before anything that follows reads or writes them. */
	public static void afterPass(VkCommandBuffer commands, MemoryStack stack) {
		memory(commands, stack, PASS_SOURCE_STAGES, PASS_SOURCE_ACCESS, NEXT_STAGES, NEXT_ACCESS);
	}

	/** After the last blit of a mip chain: the transfer writes before anything that follows. */
	static void afterTransfer(VkCommandBuffer commands, MemoryStack stack) {
		memory(commands, stack, KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR, KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
				NEXT_STAGES, NEXT_ACCESS);
	}

	/** Vanilla's full barrier (all commands, all memory). */
	static void full(VkCommandBuffer commands, MemoryStack stack) {
		VulkanCommandEncoder.memoryBarrier(commands, stack);
	}

	/** Between two blits of a chain: the level just written becomes the source of the next blit. */
	static void levelWritten(VkCommandBuffer commands, MemoryStack stack, long image, int aspect, int level) {
		VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack)
				.sType$Default()
				.srcStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR)
				.srcAccessMask(KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR)
				.dstStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR)
				.dstAccessMask(KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR)
				.oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
				.newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
				.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
				.dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
				.image(image);
		barrier.subresourceRange().aspectMask(aspect).baseMipLevel(level).levelCount(1).baseArrayLayer(0).layerCount(1);
		KHRSynchronization2.vkCmdPipelineBarrier2KHR(commands, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));
	}

	private static void memory(VkCommandBuffer commands, MemoryStack stack, long srcStages, long srcAccess, long dstStages, long dstAccess) {
		VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack)
				.sType$Default()
				.srcStageMask(srcStages)
				.srcAccessMask(srcAccess)
				.dstStageMask(dstStages)
				.dstAccessMask(dstAccess);
		KHRSynchronization2.vkCmdPipelineBarrier2KHR(commands, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
	}
}
