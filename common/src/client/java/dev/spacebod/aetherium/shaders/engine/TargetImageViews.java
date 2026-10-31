package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.Destroyable;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.spacebod.aetherium.client.gpu.VulkanCompute;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.TargetImages;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkImageViewUsageCreateInfo;

/** Compute image aliases of colour targets, including RGBA8 views used to clear R32UI targets. */
final class TargetImageViews implements AutoCloseable {
	private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);
	private static final Pattern UNIFORM = Pattern.compile("\\buniform\\b");
	private static final Pattern IMAGE = Pattern.compile("\\b[iu]?image2D\\b");

	private record Key(VulkanGpuTexture texture, GpuFormat format) { }
	private final VulkanDevice device;
	private final Map<Key, View> views = new HashMap<>();

	TargetImageViews(VulkanDevice device) {
		this.device = device;
	}

	/** Explicit formats of target image declarations, used at pack load and when binding a compute pass. */
	static Map<String, GpuFormat> formats(String source) {
		Map<String, GpuFormat> formats = new HashMap<>();
		for (String statement : COMMENTS.matcher(source).replaceAll(" ").split("[;{}]")) {
			Matcher type = IMAGE.matcher(statement);
			if (!UNIFORM.matcher(statement).find() || !type.find()) {
				continue;
			}
			String qualifier = ImageViews.declaredFormat(statement);
			GpuFormat format = qualifier == null ? null : ImageViews.format(qualifier);
			if (format == null) {
				continue;
			}
			for (String declarator : statement.substring(type.end()).split(",")) {
				Matcher name = TargetImages.matcher(declarator.strip());
				if (name.matches()) {
					formats.put(name.group(), format);
				}
			}
		}
		return Map.copyOf(formats);
	}

	static boolean needsView(GpuFormat base, @Nullable GpuFormat format) {
		return format != null && format != base && format.blockSize() == base.blockSize();
	}

	long view(VulkanGpuTextureView original, @Nullable GpuFormat format) {
		VulkanGpuTexture texture = original.texture();
		if (!needsView(texture.getFormat(), format)) {
			return original.vkImageView();
		}
		// Resized targets can be released as soon as their alias views have been retired from the GPU.
		views.entrySet().removeIf(entry -> {
			if (!entry.getKey().texture().isClosed()) {
				return false;
			}
			entry.getValue().close();
			return true;
		});
		return views.computeIfAbsent(new Key(texture, format), key -> new View(key)).handle;
	}

	private final class View implements Destroyable, AutoCloseable {
		private final VulkanGpuTexture texture;
		private final long handle;

		View(Key key) {
			texture = key.texture();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				var usage = VkImageViewUsageCreateInfo.calloc(stack).sType$Default().usage(VK10.VK_IMAGE_USAGE_STORAGE_BIT);
				var info = VkImageViewCreateInfo.calloc(stack).sType$Default().pNext(usage.address()).image(texture.vkImage())
						.viewType(VK10.VK_IMAGE_VIEW_TYPE_2D).format(VulkanConst.toVk(key.format()));
				info.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
				var output = stack.mallocLong(1);
				VulkanCompute.check(VK10.vkCreateImageView(device.vkDevice(), info, null, output), "target image format view");
				handle = output.get(0);
			}
			texture.addViews();
		}

		@Override
		public void destroy() {
			VK10.vkDestroyImageView(device.vkDevice(), handle, null);
		}

		@Override
		public void close() {
			device.createCommandEncoder().queueForDestroy(this);
			texture.removeViews();
		}
	}

	@Override
	public void close() {
		views.values().forEach(View::close);
		views.clear();
	}
}
