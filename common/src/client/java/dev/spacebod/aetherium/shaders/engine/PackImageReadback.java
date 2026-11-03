package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.spacebod.aetherium.client.diag.Diagnostics;
import dev.spacebod.aetherium.client.gpu.VulkanCompute;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.List;
import java.util.Locale;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Diagnostics recordings ({@link Diagnostics#readback()}): a box of texels from the centre of every pack image, copied at
 * the start of a frame (what the frame before left there, ahead of the per-frame clears) and summarised on the CPU a
 * few frames later, once the GPU is certainly done with the copy.
 */
final class PackImageReadback implements AutoCloseable {
	/** Box side in texels (clamped to the image). */
	private static final int BOX = 24;
	private static final int SLOTS = 4;
	/**
	 * Frames after which a copy has finished: {@link UniformArena#beginFrame} waits on the fence made at the start of
	 * the frame after the one three frames back, which covers every command that frame recorded.
	 */
	private static final int LATENCY = 3;

	record Target(String name, GpuFormat format, boolean integer, long image, int[] extent) {
	}

	private record Box(int[] offset, int[] size, int bytes) {
	}

	private final VulkanDevice vulkan;
	/** Line prefix: {@code readback} at the start of a frame, {@code readback-mid} after the shadowcomp stage. */
	private final String label;
	/** The frame a copy describes, relative to the frame recording it (-1: the start of a frame shows the one before). */
	private final int frameOffset;
	private final List<Target> targets;
	private final Box[] boxes;
	private final long[][] buffers;
	private final long[][] allocations;
	private final ByteBuffer[][] mapped;
	/** Per slot: the storage frame that recorded its copy, and the diagnostics frame it describes (-1 empty). */
	private final int[] slotStorageFrame = new int[SLOTS];
	private final int[] slotDiagFrame = new int[SLOTS];

	PackImageReadback(VulkanDevice vulkan, String label, int frameOffset, List<Target> targets, int[] bytesPerTexel) {
		this.vulkan = vulkan;
		this.label = label;
		this.frameOffset = frameOffset;
		this.targets = targets;
		int n = targets.size();
		boxes = new Box[n];
		buffers = new long[SLOTS][n];
		allocations = new long[SLOTS][n];
		mapped = new ByteBuffer[SLOTS][n];
		java.util.Arrays.fill(slotDiagFrame, -1);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer out = stack.mallocLong(1);
			PointerBuffer allocOut = stack.mallocPointer(1);
			for (int i = 0; i < n; i++) {
				int[] e = targets.get(i).extent();
				int[] size = {Math.min(BOX, e[0]), Math.min(BOX, e[1]), Math.min(BOX, e[2])};
				int[] offset = {(e[0] - size[0]) / 2, (e[1] - size[1]) / 2, (e[2] - size[2]) / 2};
				int bytes = size[0] * size[1] * size[2] * bytesPerTexel[i];
				boxes[i] = new Box(offset, size, bytes);
				for (int slot = 0; slot < SLOTS; slot++) {
					VkBufferCreateInfo create = VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
							.usage(VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
					VmaAllocationCreateInfo host = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST)
							.flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
					VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
					VulkanCompute.check(Vma.vmaCreateBuffer(vulkan.vma(), create, host, out, allocOut, info), "diagnostics readback " + targets.get(i).name());
					buffers[slot][i] = out.get(0);
					allocations[slot][i] = allocOut.get(0);
					mapped[slot][i] = MemoryUtil.memByteBuffer(info.pMappedData(), bytes).order(ByteOrder.LITTLE_ENDIAN);
				}
			}
		}
	}

	/**
	 * Copies every box into this frame's slot: everything recorded earlier is ordered before the copies, and everything
	 * after them (the frame's clears, later writes) after.
	 */
	void record(VkCommandBuffer cb, int storageFrame) {
		int diagFrame = Diagnostics.frameNumber();
		if (diagFrame < 0 || targets.isEmpty()) {
			return;
		}
		int slot = Math.floorMod(storageFrame, SLOTS);
		VulkanCompute.barrier(cb, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_ACCESS_MEMORY_WRITE_BIT,
				VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_READ_BIT);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
			for (int i = 0; i < targets.size(); i++) {
				Box b = boxes[i];
				region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
						.imageSubresource(s -> s.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1))
						.imageOffset(o -> o.set(b.offset()[0], b.offset()[1], b.offset()[2]))
						.imageExtent(e -> e.width(b.size()[0]).height(b.size()[1]).depth(b.size()[2]));
				VK10.vkCmdCopyImageToBuffer(cb, targets.get(i).image(), VK10.VK_IMAGE_LAYOUT_GENERAL, buffers[slot][i], region);
			}
		}
		VulkanCompute.barrier(cb, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
				VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT | VK10.VK_PIPELINE_STAGE_HOST_BIT,
				VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT | VK10.VK_ACCESS_HOST_READ_BIT);
		slotStorageFrame[slot] = storageFrame;
		slotDiagFrame[slot] = diagFrame + frameOffset;
	}

	/** Summarises every slot whose copy has finished (see {@link #LATENCY}) into recording lines. */
	void harvest(int storageFrame) {
		for (int slot = 0; slot < SLOTS; slot++) {
			if (slotDiagFrame[slot] < 0 || storageFrame - slotStorageFrame[slot] < LATENCY) {
				continue;
			}
			for (int i = 0; i < targets.size(); i++) {
				Vma.vmaInvalidateAllocation(vulkan.vma(), allocations[slot][i], 0, VK10.VK_WHOLE_SIZE);
				Diagnostics.line(label + " frame " + slotDiagFrame[slot] + " " + summary(targets.get(i), boxes[i], mapped[slot][i]));
			}
			slotDiagFrame[slot] = -1;
		}
	}

	private static String summary(Target t, Box b, ByteBuffer data) {
		String name = t.format().name();
		String layout = name.substring(0, name.lastIndexOf('_'));
		String kind = name.substring(name.lastIndexOf('_') + 1);
		int channels = layout.replaceAll("\\d", "").length();
		String digits = layout.replaceAll("\\D", "");
		boolean uniform = digits.length() <= 2 && !layout.contains("10") && !layout.contains("11");
		int bits = uniform && !digits.isEmpty() ? Integer.parseInt(digits) : 0;
		int texels = b.size()[0] * b.size()[1] * b.size()[2];
		long hash = 0xcbf29ce484222325L;
		for (int i = 0; i < b.bytes(); i++) {
			hash = (hash ^ (data.get(i) & 0xFF)) * 0x100000001b3L;
		}
		StringBuilder s = new StringBuilder(160);
		s.append(String.format(Locale.ROOT, "img=%s fmt=%s box=%dx%dx%d@%d,%d,%d", t.name(), name,
				b.size()[0], b.size()[1], b.size()[2], b.offset()[0], b.offset()[1], b.offset()[2]));
		if (bits == 0) {
			return s.append(" hash=").append(Long.toHexString(hash)).toString();
		}
		int stride = channels * bits / 8;
		double[] sum = new double[channels];
		int nonzero = 0;
		int ones = 0;
		int above = 0;
		for (int texel = 0; texel < texels; texel++) {
			boolean any = false;
			for (int c = 0; c < channels; c++) {
				int at = texel * stride + c * bits / 8;
				double v = switch (kind) {
					case "FLOAT" -> bits == 16 ? Float.float16ToFloat(data.getShort(at)) : data.getFloat(at);
					case "UINT", "UNORM" -> bits == 8 ? data.get(at) & 0xFF : bits == 16 ? data.getShort(at) & 0xFFFF : data.getInt(at) & 0xFFFFFFFFL;
					default -> bits == 8 ? data.get(at) : bits == 16 ? data.getShort(at) : data.getInt(at);
				};
				if (Double.isFinite(v)) {
					sum[c] += v;
				}
				any |= v != 0;
				if (c == 0 && t.integer()) {
					if (v == 1) {
						ones++;
					} else if (v > 1) {
						above++;
					}
				}
			}
			if (any) {
				nonzero++;
			}
		}
		s.append(" nonzero=").append(nonzero);
		if (t.integer()) {
			// Voxel volumes: 1 is usually solid, larger values light sources and tinted blocks.
			s.append(" eq1=").append(ones).append(" gt1=").append(above);
		}
		s.append(" sum=");
		for (int c = 0; c < channels; c++) {
			s.append(c == 0 ? "" : ",").append(String.format(Locale.ROOT, t.integer() ? "%.0f" : "%.4g", sum[c]));
		}
		return s.append(" hash=").append(Long.toHexString(hash)).toString();
	}

	@Override
	public void close() {
		for (int slot = 0; slot < SLOTS; slot++) {
			for (int i = 0; i < targets.size(); i++) {
				Vma.vmaDestroyBuffer(vulkan.vma(), buffers[slot][i], allocations[slot][i]);
			}
		}
	}
}
