package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.spacebod.aetherium.client.gpu.DeviceTraits;
import dev.spacebod.aetherium.client.gpu.GeometryStages;
import dev.spacebod.aetherium.client.gpu.VulkanCompute;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.gl.buffer.BuiltShaderStorageInfo;
import dev.spacebod.aetherium.shaders.gl.texture.TextureType;
import dev.spacebod.aetherium.shaders.shaderpack.ImageInformation;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.util.vma.VmaBudget;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageFormatListCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkImageViewUsageCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

/**
 * A pack's custom images ({@code image.<name>}) and storage buffers ({@code bufferObject.<n>}) on the Vulkan device,
 * and the descriptor set (set 1, see {@link StorageBindings}) every pack program binds them through. Images live in
 * GENERAL layout, readable as storage images and as textures; integer formats are sampled nearest, others linearly,
 * clamp to edge, one mip level, as the pack format defines. Everything starts zeroed; images marked {@code clear}
 * are zeroed again at the start of every frame; buffers with a file are filled from it. Raw custom textures the
 * vanilla API cannot hold ({@link RawTextures}: 3D, 16-bit, float ...) are sampled from here too.
 */
public final class PackStorage implements AutoCloseable {
	/** Vertex, fragment and compute: any pack program may read or write them. */
	private static final int STAGES = VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT | VK10.VK_SHADER_STAGE_COMPUTE_BIT;

	private record Image(ImageInformation info, StorageBindings.Image binding, int vkFormat, int viewType, boolean integer) {
	}

	/** Views of the custom images in other formats ({@link ImageViews}), in binding order. */
	private final List<ImageViews.View> views;
	private long[] viewHandles = new long[0];

	private record Buffer(int index, BuiltShaderStorageInfo info) {
	}

	private final VulkanDevice vulkan;
	private final VkDevice device;
	private final List<Image> images = new ArrayList<>();
	private final List<Buffer> buffers = new ArrayList<>();
	private final long setLayout;
	private final long pool;
	private final long set;
	private final long linearSampler;
	private final long nearestSampler;
	private long[] vkImages = new long[0];
	private long[] imageAllocations = new long[0];
	private long[] imageViews = new long[0];
	private long[] vkBuffers = new long[0];
	private long[] bufferAllocations = new long[0];
	private long[] bufferSizes = new long[0];
	private int width;
	private int height;
	/** Resources were (re)created: the next frame's command buffer lays them out and zeroes or fills them. */
	private boolean needsInit = true;
	/** Raw custom textures ({@link RawTextures}): created once, never resized, uploaded by the first frame. */
	private final List<RawTextures.Texture> raws;
	private final long[] rawImages;
	private final long[] rawAllocations;
	private final long[] rawViews;
	private final long[] rawSamplers;
	private final long[] rawStaging;
	private final long[] rawStagingAllocations;
	private boolean rawsUploaded;
	/** Graphics programs bind colour / shadow colour targets as images here ({@link StorageBindings#TARGET_IMAGE_BASE}). */
	private final boolean targetImages;
	/** Colour targets (colorimg0..31) and shadow colour targets (shadowcolorimg0..7) bindable as images. */
	private static final int COLOUR_TARGETS = 32;
	private static final int SHADOW_COLOUR_TARGETS = 8;
	/** Per-pass sets, one per frame slot (a set is rewritten only when no frame in flight can still read it). */
	private static final int PASS_SLOTS = 4;
	private static final int PASS_SETS = 24;
	private final Map<Object, long[]> passSets = new HashMap<>();
	private long passPool;
	private int frame;

	/** The binding layout the pack's images imply, for {@link StorageBindings#configure} (before programs are built). */
	public static Map<String, StorageBindings.Image> bindings(List<ImageInformation> infos) {
		Map<String, StorageBindings.Image> out = new LinkedHashMap<>();
		for (int i = 0; i < infos.size(); i++) {
			ImageInformation info = infos.get(i);
			out.put(info.name(), new StorageBindings.Image(info.name(), info.samplerName(), i, PackTargets.gpuFormat(info.internalTextureFormat())));
		}
		return out;
	}

	/**
	 * @param setLayout the storage set's layout made by {@link #createSetLayout(VkDevice, List, Map, List, boolean)} for
	 *                  the same arguments (owned from here on), or 0 to make it here
	 */
	public PackStorage(VulkanDevice vulkan, List<ImageInformation> infos, Map<Integer, BuiltShaderStorageInfo> bufferInfos,
			List<RawTextures.Texture> raws, boolean targetImages, int width, int height, long setLayout) {
		this.vulkan = vulkan;
		this.device = vulkan.vkDevice();
		this.raws = List.copyOf(raws);
		this.targetImages = targetImages;
		int rawCount = this.raws.size();
		rawImages = new long[rawCount];
		rawAllocations = new long[rawCount];
		rawViews = new long[rawCount];
		rawSamplers = new long[rawCount];
		rawStaging = new long[rawCount];
		rawStagingAllocations = new long[rawCount];
		Map<String, StorageBindings.Image> bindings = bindings(infos);
		this.views = StorageBindings.views();
		for (ImageInformation info : infos) {
			StorageBindings.Image b = bindings.get(info.name());
			GpuFormat format = b.format();
			int viewType = switch (info.target()) {
				case TEXTURE_1D -> VK10.VK_IMAGE_VIEW_TYPE_1D;
				case TEXTURE_3D -> VK10.VK_IMAGE_VIEW_TYPE_3D;
				default -> VK10.VK_IMAGE_VIEW_TYPE_2D;
			};
			String n = format.name();
			images.add(new Image(info, b, VulkanConst.toVk(format), viewType, n.endsWith("_UINT") || n.endsWith("_SINT")));
		}
		bufferInfos.forEach((index, info) -> buffers.add(new Buffer(index, info)));
		this.linearSampler = sampler(VK10.VK_FILTER_LINEAR);
		this.nearestSampler = sampler(VK10.VK_FILTER_NEAREST);
		this.setLayout = setLayout != 0 ? setLayout : createSetLayout(device, infos, bufferInfos, raws, targetImages);
		this.pool = createPool(1);
		this.set = VulkanCompute.allocate(device, pool, setLayout);
		if (set == 0) {
			throw new IllegalStateException("could not allocate the pack storage descriptor set");
		}
		createRaws();
		resize(width, height);
	}

	/** The raw textures' images (sampled, filled by a copy), views, samplers and staging buffers holding their texels. */
	private void createRaws() {
		long total = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer out = stack.mallocLong(1);
			PointerBuffer allocOut = stack.mallocPointer(1);
			for (int i = 0; i < raws.size(); i++) {
				RawTextures.Texture raw = raws.get(i);
				VkImageCreateInfo create = VkImageCreateInfo.calloc(stack).sType$Default()
						.imageType(raw.imageType()).format(raw.vkFormat())
						.extent(e -> e.width(raw.width()).height(raw.height()).depth(raw.depth())).mipLevels(1).arrayLayers(1)
						.samples(VK10.VK_SAMPLE_COUNT_1_BIT).tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
						.usage(VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
						.sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
				VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
				VulkanCompute.check(Vma.vmaCreateImage(vulkan.vma(), create, alloc, out, allocOut, null), "raw texture " + raw.name());
				rawImages[i] = out.get(0);
				rawAllocations[i] = allocOut.get(0);
				VulkanCompute.check(VK10.vkCreateImageView(device, VkImageViewCreateInfo.calloc(stack).sType$Default()
						.image(rawImages[i]).viewType(raw.viewType()).format(raw.vkFormat())
						.subresourceRange(r -> r.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)),
						null, out), "raw texture view " + raw.name());
				rawViews[i] = out.get(0);
				rawSamplers[i] = sampler(raw.linear() && DeviceTraits.filtersLinearly(raw.vkFormat()) ? VK10.VK_FILTER_LINEAR : VK10.VK_FILTER_NEAREST,
						raw.clamp() ? VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT);
				byte[] texels = raw.texels();
				VkBufferCreateInfo staging = VkBufferCreateInfo.calloc(stack).sType$Default().size(texels.length)
						.usage(VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT).sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
				VmaAllocationCreateInfo host = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST)
						.flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
				VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
				VulkanCompute.check(Vma.vmaCreateBuffer(vulkan.vma(), staging, host, out, allocOut, info), "raw texture upload " + raw.name());
				rawStaging[i] = out.get(0);
				rawStagingAllocations[i] = allocOut.get(0);
				MemoryUtil.memByteBuffer(info.pMappedData(), texels.length).put(0, texels);
				Vma.vmaFlushAllocation(vulkan.vma(), rawStagingAllocations[i], 0, VK10.VK_WHOLE_SIZE);
				total += texels.length;
			}
		}
		if (!raws.isEmpty()) {
			AetheriumShaders.logger.info("{} raw custom texture(s), {} KiB", raws.size(), total >> 10);
		}
	}

	/** First frame only: the raw textures' texels copied in, then GENERAL layout like every image of the set. */
	private void uploadRaws(VkCommandBuffer cb, MemoryStack stack) {
		VkImageMemoryBarrier.Buffer toCopy = VkImageMemoryBarrier.calloc(raws.size(), stack);
		VkImageMemoryBarrier.Buffer toRead = VkImageMemoryBarrier.calloc(raws.size(), stack);
		for (int i = 0; i < raws.size(); i++) {
			toCopy.get(i).sType$Default().oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
					.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
					.image(rawImages[i]).srcAccessMask(0).dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
					.subresourceRange(r -> r.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
			toRead.get(i).sType$Default().oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
					.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
					.image(rawImages[i]).srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
					.subresourceRange(r -> r.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
		}
		VK10.vkCmdPipelineBarrier(cb, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toCopy);
		for (int i = 0; i < raws.size(); i++) {
			RawTextures.Texture raw = raws.get(i);
			VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
			region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
					.imageSubresource(s -> s.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1))
					.imageOffset(o -> o.set(0, 0, 0)).imageExtent(e -> e.width(raw.width()).height(raw.height()).depth(raw.depth()));
			VK10.vkCmdCopyBufferToImage(cb, rawStaging[i], rawImages[i], VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
		}
		VK10.vkCmdPipelineBarrier(cb, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, toRead);
	}

	public long setLayout() {
		return setLayout;
	}

	public long descriptorSet() {
		return set;
	}

	/** The Vulkan buffer behind {@code bufferObject.<index>}, or 0 when the pack has none (indirect dispatch reads it). */
	public long vkBuffer(int index) {
		for (int i = 0; i < buffers.size(); i++) {
			if (buffers.get(i).index() == index && i < vkBuffers.length) {
				return vkBuffers[i];
			}
		}
		return 0;
	}

	/** Recreates images and buffers whose size follows the screen; the first call creates everything. */
	public void resize(int width, int height) {
		if (width == this.width && height == this.height) {
			return;
		}
		boolean first = this.width == 0;
		boolean relative = images.stream().anyMatch(i -> i.info().isRelative()) || buffers.stream().anyMatch(b -> b.info().relative());
		this.width = width;
		this.height = height;
		if (!first && !relative) {
			return;
		}
		if (!first) {
			// The set is updated in place: no frame in flight may still read it.
			VK10.vkDeviceWaitIdle(device);
		}
		destroyResources();
		createResources();
		writeDescriptors();
		needsInit = true;
	}

	/**
	 * Refuses storage the device cannot hold, before anything is allocated: a buffer larger than a storage descriptor
	 * may address ({@code maxStorageBufferRange}), or images and buffers together beyond 80% of the device-local
	 * memory still free. The pack then fails to load with this message (vanilla visuals) instead of failing in the driver.
	 */
	private void checkBudget() {
		long images = 0;
		for (Image image : this.images) {
			ImageInformation info = image.info();
			long w = info.isRelative() ? Math.max(1, (int) (width * info.relativeWidth())) : Math.max(1, info.width());
			long h = info.target() == TextureType.TEXTURE_1D ? 1
					: info.isRelative() ? Math.max(1, (int) (height * info.relativeHeight())) : Math.max(1, info.height());
			long d = info.target() == TextureType.TEXTURE_3D ? Math.max(1, info.depth()) : 1;
			images += w * h * d * bytesPerTexel(image.binding().format());
		}
		long buffers = 0;
		long largest = 0;
		for (var buffer : this.buffers) {
			BuiltShaderStorageInfo info = buffer.info();
			long size = info.relative() ? (long) (width * info.scaleX()) * (long) (height * info.scaleY()) * info.size() : info.size();
			buffers += size;
			largest = Math.max(largest, size);
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var properties = VkPhysicalDeviceProperties.calloc(stack);
			VK10.vkGetPhysicalDeviceProperties(device.getPhysicalDevice(), properties);
			long range = Integer.toUnsignedLong(properties.limits().maxStorageBufferRange());
			if (largest > range) {
				throw new IllegalStateException("This pack asks for a " + (largest >> 20) + " MiB storage buffer; the graphics card allows "
						+ (range >> 20) + " MiB per buffer");
			}
			var memory = VkPhysicalDeviceMemoryProperties.calloc(stack);
			VK10.vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), memory);
			var budgets = VmaBudget.calloc(memory.memoryHeapCount(), stack);
			Vma.vmaGetHeapBudgets(vulkan.vma(), budgets);
			long free = 0;
			for (int heap = 0; heap < memory.memoryHeapCount(); heap++) {
				if ((memory.memoryHeaps(heap).flags() & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
					free = Math.max(free, budgets.get(heap).budget() - budgets.get(heap).usage());
				}
			}
			if (free > 0 && images + buffers > free * 8 / 10) {
				throw new IllegalStateException("This pack's images and buffers need " + ((images + buffers) >> 20) + " MiB; the graphics card has "
						+ (free >> 20) + " MiB free");
			}
		}
	}

	private void createResources() {
		checkBudget();
		int count = images.size();
		vkImages = new long[count];
		imageAllocations = new long[count];
		imageViews = new long[count];
		long total = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer out = stack.mallocLong(1);
			PointerBuffer allocOut = stack.mallocPointer(1);
			for (int i = 0; i < count; i++) {
				Image image = images.get(i);
				ImageInformation info = image.info();
				int w = info.isRelative() ? Math.max(1, (int) (width * info.relativeWidth())) : Math.max(1, info.width());
				int h = info.isRelative() ? Math.max(1, (int) (height * info.relativeHeight())) : Math.max(1, info.height());
				int d = info.target() == TextureType.TEXTURE_3D ? Math.max(1, info.depth()) : 1;
				if (info.target() == TextureType.TEXTURE_1D) {
					h = 1;
				}
				int imageType = info.target() == TextureType.TEXTURE_1D ? VK10.VK_IMAGE_TYPE_1D
						: info.target() == TextureType.TEXTURE_3D ? VK10.VK_IMAGE_TYPE_3D : VK10.VK_IMAGE_TYPE_2D;
				int fw = w, fh = h, fd = d;
				VkImageCreateInfo create = VkImageCreateInfo.calloc(stack).sType$Default()
						.imageType(imageType).format(image.vkFormat())
						.extent(e -> e.width(fw).height(fh).depth(fd)).mipLevels(1).arrayLayers(1)
						.samples(VK10.VK_SAMPLE_COUNT_1_BIT).tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
						.usage(VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
						.sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
				int[] viewFormats = viewFormats(i, image.vkFormat());
				if (viewFormats.length > 1) {
					// Viewed in other formats of the same texel size: one mutable image, the formats listed so the driver can keep it compressed.
					create.flags(VK10.VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT)
							.pNext(VkImageFormatListCreateInfo.calloc(stack).sType$Default().pViewFormats(stack.ints(viewFormats)).address());
				}
				VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
				VulkanCompute.check(Vma.vmaCreateImage(vulkan.vma(), create, alloc, out, allocOut, null), "pack image " + info.name());
				vkImages[i] = out.get(0);
				imageAllocations[i] = allocOut.get(0);
				VulkanCompute.check(VK10.vkCreateImageView(device, VkImageViewCreateInfo.calloc(stack).sType$Default()
						.image(vkImages[i]).viewType(image.viewType()).format(image.vkFormat())
						.subresourceRange(r -> r.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)),
						null, out), "pack image view " + info.name());
				imageViews[i] = out.get(0);
				total += (long) w * h * d * bytesPerTexel(image.binding().format());
			}
			viewHandles = new long[views.size()];
			for (int v = 0; v < views.size(); v++) {
				ImageViews.View view = views.get(v);
				Image image = images.get(view.index());
				// Usage narrowed to what the view is bound as: the other format need only support that.
				VkImageViewUsageCreateInfo usage = VkImageViewUsageCreateInfo.calloc(stack).sType$Default()
						.usage(view.storage() ? VK10.VK_IMAGE_USAGE_STORAGE_BIT : VK10.VK_IMAGE_USAGE_SAMPLED_BIT);
				VulkanCompute.check(VK10.vkCreateImageView(device, VkImageViewCreateInfo.calloc(stack).sType$Default().pNext(usage.address())
						.image(vkImages[view.index()]).viewType(image.viewType()).format(VulkanConst.toVk(view.format()))
						.subresourceRange(r -> r.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)),
						null, out), "pack image view " + view.image() + " as " + view.format());
				viewHandles[v] = out.get(0);
			}
			int bufferCount = buffers.size();
			vkBuffers = new long[bufferCount];
			bufferAllocations = new long[bufferCount];
			bufferSizes = new long[bufferCount];
			for (int i = 0; i < bufferCount; i++) {
				BuiltShaderStorageInfo info = buffers.get(i).info();
				long size = info.relative() ? (long) (width * info.scaleX()) * (long) (height * info.scaleY()) * info.size() : info.size();
				size = Math.max(4, (size + 3) & ~3L);
				bufferSizes[i] = size;
				boolean upload = info.content() != null;
				VkBufferCreateInfo create = VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
						.usage(VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
						.sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
				VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
				if (upload) {
					alloc.flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
				}
				VmaAllocationInfo allocInfo = VmaAllocationInfo.calloc(stack);
				VulkanCompute.check(Vma.vmaCreateBuffer(vulkan.vma(), create, alloc, out, allocOut, allocInfo), "pack buffer " + buffers.get(i).index());
				vkBuffers[i] = out.get(0);
				bufferAllocations[i] = allocOut.get(0);
				if (upload) {
					ByteBuffer mapped = MemoryUtil.memByteBuffer(allocInfo.pMappedData(), (int) Math.min(size, Integer.MAX_VALUE));
					byte[] content = info.content();
					mapped.put(0, content, 0, Math.min(content.length, mapped.capacity()));
					Vma.vmaFlushAllocation(vulkan.vma(), bufferAllocations[i], 0, VK10.VK_WHOLE_SIZE);
				}
				total += size;
			}
		}
		AetheriumShaders.logger.info("pack storage {} image(s), {} buffer(s), {} MiB", images.size(), buffers.size(), total >> 20);
	}

	private void writeDescriptors() {
		writeDescriptors(set);
	}

	/** The set's shared descriptors (images, buffers, raw textures) into {@code set}. */
	private void writeDescriptors(long set) {
		for (int i = 0; i < images.size(); i++) {
			Image image = images.get(i);
			VulkanCompute.writeImage(device, set, image.binding().storageBinding(), imageViews[i], 0);
			if (image.binding().samplerName() != null) {
				VulkanCompute.writeImage(device, set, image.binding().samplerBinding(), imageViews[i], image.integer() || !DeviceTraits.filtersLinearly(image.vkFormat()) ? nearestSampler : linearSampler);
			}
		}
		for (int i = 0; i < buffers.size(); i++) {
			VulkanCompute.writeBuffer(device, set, StorageBindings.BUFFER_BASE + buffers.get(i).index(), vkBuffers[i], 0, VK10.VK_WHOLE_SIZE);
		}
		for (int i = 0; i < raws.size(); i++) {
			VulkanCompute.writeImage(device, set, raws.get(i).binding(), rawViews[i], rawSamplers[i]);
		}
		for (int v = 0; v < views.size(); v++) {
			ImageViews.View view = views.get(v);
			// Views read through samplers are of integer formats: sampled nearest.
			VulkanCompute.writeImage(device, set, view.binding(), viewHandles[v], view.storage() ? 0 : nearestSampler);
		}
	}

	/**
	 * Start of a frame, before anything reads or writes the pack's images: first lays out and zeroes new resources,
	 * then zeroes the images marked {@code clear}. Records into {@code cb}; earlier work is ordered before it.
	 */
	public void beginFrame(VkCommandBuffer cb) {
		frame++;
		VulkanCompute.barrier(cb, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT,
				VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
		boolean init = needsInit;
		needsInit = false;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			if (!rawsUploaded && !raws.isEmpty()) {
				uploadRaws(cb, stack);
			}
			rawsUploaded = true;
			if (init && vkImages.length > 0) {
				VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(vkImages.length, stack);
				for (int i = 0; i < vkImages.length; i++) {
					barriers.get(i).sType$Default().oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
							.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
							.image(vkImages[i]).srcAccessMask(0).dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
							.subresourceRange(r -> r.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
				}
				VK10.vkCmdPipelineBarrier(cb, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barriers);
			}
			VkClearColorValue zero = VkClearColorValue.calloc(stack);
			VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
			range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
			for (int i = 0; i < vkImages.length; i++) {
				if (init || images.get(i).info().clear()) {
					VK10.vkCmdClearColorImage(cb, vkImages[i], VK10.VK_IMAGE_LAYOUT_GENERAL, zero, range);
				}
			}
			if (init) {
				for (int i = 0; i < vkBuffers.length; i++) {
					if (buffers.get(i).info().content() == null) {
						VK10.vkCmdFillBuffer(cb, vkBuffers[i], 0, VK10.VK_WHOLE_SIZE, 0);
					}
				}
			}
		}
		VulkanCompute.barrier(cb, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
				VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
	}

	/**
	 * The storage set's layout (set 1 of every pack program) for these images, buffers, raw textures and target images.
	 * Needs no other storage object, so a pack load builds it off the render thread, with the compute pipelines that use it.
	 */
	public static long createSetLayout(VkDevice device, List<ImageInformation> infos, Map<Integer, BuiltShaderStorageInfo> bufferInfos,
			List<RawTextures.Texture> raws, boolean targetImages) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Collection<StorageBindings.Image> images = bindings(infos).values();
			// Pack geometry stages read and write the storage set too (voxelisation in the shadow pass).
			int STAGES = PackStorage.STAGES | (GeometryStages.enabled()
					? GeometryStages.STAGE_BIT : 0);
			int count = 0;
			for (StorageBindings.Image image : images) {
				count += image.samplerName() != null ? 2 : 1;
			}
			List<ImageViews.View> views = StorageBindings.views();
			count += bufferInfos.size() + raws.size() + views.size() + (targetImages ? COLOUR_TARGETS + SHADOW_COLOUR_TARGETS : 0);
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(Math.max(1, count), stack);
			int at = 0;
			for (StorageBindings.Image image : images) {
				bindings.get(at++).binding(image.storageBinding()).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
						.descriptorCount(1).stageFlags(STAGES);
				if (image.samplerName() != null) {
					bindings.get(at++).binding(image.samplerBinding()).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
							.descriptorCount(1).stageFlags(STAGES);
				}
			}
			for (int index : bufferInfos.keySet()) {
				bindings.get(at++).binding(StorageBindings.BUFFER_BASE + index).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
						.descriptorCount(1).stageFlags(STAGES);
			}
			for (RawTextures.Texture raw : raws) {
				bindings.get(at++).binding(raw.binding()).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
						.descriptorCount(1).stageFlags(STAGES);
			}
			if (targetImages) {
				for (int i = 0; i < COLOUR_TARGETS + SHADOW_COLOUR_TARGETS; i++) {
					int binding = i < COLOUR_TARGETS ? StorageBindings.TARGET_IMAGE_BASE + i : StorageBindings.SHADOW_TARGET_IMAGE_BASE + i - COLOUR_TARGETS;
					bindings.get(at++).binding(binding).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(STAGES);
				}
			}
			for (ImageViews.View view : views) {
				bindings.get(at++).binding(view.binding()).descriptorType(view.storage() ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
						: VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(STAGES);
			}
			bindings.limit(at);
			LongBuffer out = stack.mallocLong(1);
			VulkanCompute.check(VK10.vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),
					null, out), "pack storage set layout");
			return out.get(0);
		}
	}

	/** A pool for {@code sets} sets of the storage layout (every binding counted, written or not). */
	private long createPool(int sets) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int storageViews = (int) views.stream().filter(ImageViews.View::storage).count();
			int storageImages = images.size() + storageViews + (targetImages ? COLOUR_TARGETS + SHADOW_COLOUR_TARGETS : 0);
			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
			sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(Math.max(1, storageImages) * sets);
			sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
					.descriptorCount(Math.max(1, images.size() + raws.size() + views.size() - storageViews) * sets);
			sizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(Math.max(1, buffers.size()) * sets);
			LongBuffer out = stack.mallocLong(1);
			VulkanCompute.check(VK10.vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(sets).pPoolSizes(sizes),
					null, out), "pack storage pool");
			return out.get(0);
		}
	}

	/**
	 * The set a graphics pass binding targets as images uses this frame: the shared descriptors plus each target's view
	 * ({@code colour}: colortex index -> view, {@code shadowColour}: shadowcolor index -> view) as the pass reads it (image
	 * bindings follow buffer flips). One set per pass and frame slot; 0 when the pass sets are used up (the shared set is
	 * bound then, without the targets).
	 */
	public long targetImageSet(Object pass, Map<Integer, Long> colour, Map<Integer, Long> shadowColour) {
		if (!targetImages) {
			return 0;
		}
		long[] sets = passSets.get(pass);
		if (sets == null) {
			if (passSets.size() >= PASS_SETS) {
				return 0;
			}
			if (passPool == 0) {
				passPool = createPool(PASS_SETS * PASS_SLOTS);
			}
			sets = new long[PASS_SLOTS];
			for (int i = 0; i < PASS_SLOTS; i++) {
				sets[i] = VulkanCompute.allocate(device, passPool, setLayout);
			}
			passSets.put(pass, sets);
		}
		long target = sets[Math.floorMod(frame, PASS_SLOTS)];
		if (target == 0) {
			return 0;
		}
		writeDescriptors(target);
		colour.forEach((index, view) -> {
			if (index < COLOUR_TARGETS && view != 0) {
				VulkanCompute.writeImage(device, target, StorageBindings.TARGET_IMAGE_BASE + index, view, 0);
			}
		});
		shadowColour.forEach((index, view) -> {
			if (index < SHADOW_COLOUR_TARGETS && view != 0) {
				VulkanCompute.writeImage(device, target, StorageBindings.SHADOW_TARGET_IMAGE_BASE + index, view, 0);
			}
		});
		return target;
	}

	/** A world or shadow pass's target views: equal keys within a frame share one set (a set bound this frame is never rewritten). */
	private record WorldKey(Map<Integer, Long> colour, Map<Integer, Long> shadowColour) {
	}

	/** World keys -> the frame their set was last written. */
	private final Map<WorldKey, Integer> worldWritten = new HashMap<>();

	/**
	 * {@link #targetImageSet} for world and shadow render passes, whose programs bind targets as images: passes of one
	 * frame that see the same views share a set, written once that frame.
	 */
	public long worldImageSet(Map<Integer, Long> colour, Map<Integer, Long> shadowColour) {
		WorldKey key = new WorldKey(Map.copyOf(colour), Map.copyOf(shadowColour));
		Integer written = worldWritten.get(key);
		long[] sets = passSets.get(key);
		if (written != null && written == frame && sets != null) {
			return sets[Math.floorMod(frame, PASS_SLOTS)];
		}
		if (sets == null) {
			// Views change with the targets (resize, reload): a key unused for every frame slot hands its sets over.
			var stale = worldWritten.entrySet().iterator();
			while (stale.hasNext()) {
				var e = stale.next();
				if (frame - e.getValue() >= PASS_SLOTS) {
					stale.remove();
					long[] old = passSets.remove(e.getKey());
					if (old != null) {
						passSets.put(key, old);
						break;
					}
				}
			}
		}
		long set = targetImageSet(key, colour, shadowColour);
		if (set != 0) {
			worldWritten.put(key, frame);
		}
		return set;
	}

	private long sampler(int filter) {
		return sampler(filter, VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
	}

	private long sampler(int filter, int address) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
					.magFilter(filter).minFilter(filter).mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
					.addressModeU(address)
					.addressModeV(address)
					.addressModeW(address)
					.minLod(0).maxLod(0);
			LongBuffer out = stack.mallocLong(1);
			VulkanCompute.check(VK10.vkCreateSampler(device, info, null, out), "pack storage sampler");
			return out.get(0);
		}
	}

	/** The formats image {@code index} is viewed in: its own first, then each other view's; just its own when it has none. */
	private int[] viewFormats(int index, int own) {
		return java.util.stream.IntStream.concat(java.util.stream.IntStream.of(own), views.stream()
				.filter(v -> v.index() == index).mapToInt(v -> VulkanConst.toVk(v.format()))).distinct().toArray();
	}

	private static int bytesPerTexel(GpuFormat format) {
		String n = format.name();
		int bits = 0;
		for (String part : n.substring(0, n.lastIndexOf('_')).split("(?<=\\d)(?=[A-Z])")) {
			String digits = part.replaceAll("\\D", "");
			int channels = part.replaceAll("\\d", "").length();
			bits += channels * (digits.isEmpty() ? 8 : Integer.parseInt(digits));
		}
		return Math.max(1, bits / 8);
	}

	private void destroyResources() {
		for (long view : viewHandles) {
			VK10.vkDestroyImageView(device, view, null);
		}
		viewHandles = new long[0];
		for (int i = 0; i < vkImages.length; i++) {
			VK10.vkDestroyImageView(device, imageViews[i], null);
			Vma.vmaDestroyImage(vulkan.vma(), vkImages[i], imageAllocations[i]);
		}
		for (int i = 0; i < vkBuffers.length; i++) {
			Vma.vmaDestroyBuffer(vulkan.vma(), vkBuffers[i], bufferAllocations[i]);
		}
		vkImages = new long[0];
		vkBuffers = new long[0];
	}

	@Override
	public void close() {
		VK10.vkDeviceWaitIdle(device);
		destroyResources();
		VK10.vkDestroyDescriptorPool(device, pool, null);
		if (passPool != 0) {
			VK10.vkDestroyDescriptorPool(device, passPool, null);
		}
		VK10.vkDestroyDescriptorSetLayout(device, setLayout, null);
		VK10.vkDestroySampler(device, linearSampler, null);
		VK10.vkDestroySampler(device, nearestSampler, null);
		for (int i = 0; i < raws.size(); i++) {
			VK10.vkDestroyImageView(device, rawViews[i], null);
			Vma.vmaDestroyImage(vulkan.vma(), rawImages[i], rawAllocations[i]);
			VK10.vkDestroySampler(device, rawSamplers[i], null);
			Vma.vmaDestroyBuffer(vulkan.vma(), rawStaging[i], rawStagingAllocations[i]);
		}
	}
}
