package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import dev.spacebod.aetherium.client.gpu.VulkanAccess;
import dev.spacebod.aetherium.client.mixin.access.VulkanCommandEncoderAccessor;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageBlit;

/**
 * Mip chains filled by {@code vkCmdBlitImage}, level to level, on the frame's current command buffer: no render pass per
 * level. Each level is the one above scaled to half its size (sides floored at 1, so a non-square chain runs down to
 * 1x1), linearly filtered for colour formats (the 2x2 box average of an even level), nearest for integer and depth
 * formats (the only filtering Vulkan allows there). Between two levels a transfer barrier on the level just written; after
 * the last one, a barrier from the transfers to whatever follows ({@link PassBarriers}); with the narrow barriers off,
 * vanilla's full barrier after every level instead. Not inside a render pass.
 */
final class MipChains {
	private static final Map<GpuFormat, Boolean> BLITTABLE = new ConcurrentHashMap<>();

	private MipChains() {
	}

	/** Whether chains of {@code format} can be blitted on this device (blit source and destination, and linear filtering for colour). */
	static boolean supports(GpuFormat format) {
		return BLITTABLE.computeIfAbsent(format, f -> {
			var device = VulkanAccess.device();
			if (device == null) {
				return false;
			}
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkFormatProperties properties = VkFormatProperties.calloc(stack);
				VK10.vkGetPhysicalDeviceFormatProperties(device.vkDevice().getPhysicalDevice(), VulkanConst.toVk(f), properties);
				int features = properties.optimalTilingFeatures();
				int needed = VK10.VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK10.VK_FORMAT_FEATURE_BLIT_DST_BIT;
				if (linear(f)) {
					needed |= VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
				}
				boolean ok = (features & needed) == needed;
				if (!ok) {
					AetheriumShaders.logger.info("the graphics card cannot blit {} images; their mip chains are drawn level by level", f);
				}
				return ok;
			}
		});
	}

	/** Linear filtering between texels: colour formats that are not integer. */
	private static boolean linear(GpuFormat format) {
		return format.hasColorAspect() && !WorldProgram.isIntegerFormat(format);
	}

	/**
	 * Fills levels 1 to {@code levels - 1} of {@code texture} from its level 0. False (nothing recorded) when the device
	 * cannot blit the format or no frame command buffer is reachable.
	 */
	static boolean generate(GpuTexture texture, int levels) {
		if (levels <= 1 || !(texture instanceof VulkanGpuTexture image) || !supports(texture.getFormat())) {
			return false;
		}
		var encoder = VulkanAccess.encoder();
		if (encoder == null) {
			return false;
		}
		VkCommandBuffer commands = ((VulkanCommandEncoderAccessor) encoder).aetherium$commandBuffer();
		int aspect = VulkanConst.formatAspectMask(texture.getFormat());
		int filter = linear(texture.getFormat()) ? VK10.VK_FILTER_LINEAR : VK10.VK_FILTER_NEAREST;
		boolean narrow = PassBarriers.narrow();
		long vkImage = image.vkImage();
		int width = texture.getWidth(0);
		int height = texture.getHeight(0);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			for (int level = 1; level < levels; level++) {
				int srcWidth = Math.max(1, width >> (level - 1));
				int srcHeight = Math.max(1, height >> (level - 1));
				int dstWidth = Math.max(1, width >> level);
				int dstHeight = Math.max(1, height >> level);
				VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
				region.srcSubresource().aspectMask(aspect).mipLevel(level - 1).baseArrayLayer(0).layerCount(1);
				region.dstSubresource().aspectMask(aspect).mipLevel(level).baseArrayLayer(0).layerCount(1);
				region.srcOffsets(0).set(0, 0, 0);
				region.srcOffsets(1).set(srcWidth, srcHeight, 1);
				region.dstOffsets(0).set(0, 0, 0);
				region.dstOffsets(1).set(dstWidth, dstHeight, 1);
				VK10.vkCmdBlitImage(commands, vkImage, VK10.VK_IMAGE_LAYOUT_GENERAL, vkImage, VK10.VK_IMAGE_LAYOUT_GENERAL, region, filter);
				if (!narrow) {
					PassBarriers.full(commands, stack);
				} else if (level + 1 < levels) {
					PassBarriers.levelWritten(commands, stack, vkImage, aspect, level);
				}
			}
			if (narrow) {
				PassBarriers.afterTransfer(commands, stack);
			}
		}
		return true;
	}

	/** Levels of a full chain for {@code w} x {@code h}: down to 1x1, sides floored at 1. */
	static int fullLevels(int w, int h) {
		return 1 + (31 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(w, h))));
	}
}
