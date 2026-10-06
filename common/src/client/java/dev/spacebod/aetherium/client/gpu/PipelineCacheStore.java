package dev.spacebod.aetherium.client.gpu;

import dev.spacebod.aetherium.Aetherium;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

/**
 * {@code gpu.pipeline_cache}: one Vulkan pipeline cache for every graphics and compute pipeline (vanilla's and shader
 * packs'), kept in {@code <game dir>/aetherium/pipeline-cache.bin} between launches, so later launches and pack
 * reloads compile pipelines from the driver's cache. Data saved for another device or driver is not passed on: its
 * header (vendor, device, cache UUID) must match the device's, and the driver version is checked too.
 */
public final class PipelineCacheStore {
	/** Our header before the driver's data: magic, driver version. */
	private static final int MAGIC = 0x41455043; // "AEPC"
	private static final int OWN_HEADER = 8;

	private static @Nullable VkDevice device;
	private static long cache = VK10.VK_NULL_HANDLE;
	private static int vendorId;
	private static int deviceId;
	private static int driverVersion;
	private static final byte[] UUID = new byte[VK10.VK_UUID_SIZE];

	private PipelineCacheStore() {
	}

	/** Device creation, while its physical device is still open: the cache is created, from disk when the data fits. */
	public static synchronized void create(VkDevice vkDevice, VkPhysicalDeviceProperties properties) {
		device = vkDevice;
		vendorId = properties.vendorID();
		deviceId = properties.deviceID();
		driverVersion = properties.driverVersion();
		properties.pipelineCacheUUID().get(0, UUID, 0, UUID.length);
		ByteBuffer initial = load();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPipelineCacheCreateInfo info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
			if (initial != null) {
				info.pInitialData(initial);
			}
			var out = stack.mallocLong(1);
			int result = VK10.vkCreatePipelineCache(vkDevice, info, null, out);
			if (result != VK10.VK_SUCCESS && initial != null) {
				// Rejected data: start empty.
				info.pInitialData(null);
				result = VK10.vkCreatePipelineCache(vkDevice, info, null, out);
			}
			cache = result == VK10.VK_SUCCESS ? out.get(0) : VK10.VK_NULL_HANDLE;
			Aetherium.LOG.info("Pipeline cache: {}", cache == VK10.VK_NULL_HANDLE ? "unavailable (" + result + ")"
					: initial != null ? "loaded " + initial.remaining() / 1024 + " KiB" : "empty");
		} finally {
			if (initial != null) {
				MemoryUtil.memFree(initial);
			}
		}
	}

	/** The cache to pass to {@code vkCreate*Pipelines} (null handle when there is none). */
	public static long handle() {
		return cache;
	}

	/** Writes the cache to disk (render thread, outside frame work: after a pack's programs are built, at shutdown). */
	public static synchronized void save() {
		VkDevice d = device;
		if (d == null || cache == VK10.VK_NULL_HANDLE) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer size = stack.mallocPointer(1);
			if (VK10.vkGetPipelineCacheData(d, cache, size, null) != VK10.VK_SUCCESS || size.get(0) == 0) {
				return;
			}
			ByteBuffer data = MemoryUtil.memAlloc((int) size.get(0));
			try {
				if (VK10.vkGetPipelineCacheData(d, cache, size, data) != VK10.VK_SUCCESS) {
					return;
				}
				data.limit((int) size.get(0));
				byte[] bytes = new byte[OWN_HEADER + data.remaining()];
				ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(MAGIC).putInt(driverVersion).put(data);
				Path file = file();
				Files.createDirectories(file.getParent());
				Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
				Files.write(tmp, bytes);
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} finally {
				MemoryUtil.memFree(data);
			}
		} catch (IOException | RuntimeException e) {
			Aetherium.LOG.warn("Pipeline cache: could not save", e);
		}
	}

	/** Device shutdown: saved, then destroyed. */
	public static synchronized void destroy() {
		save();
		if (device != null && cache != VK10.VK_NULL_HANDLE) {
			VK10.vkDestroyPipelineCache(device, cache, null);
		}
		cache = VK10.VK_NULL_HANDLE;
		device = null;
	}

	/** The saved data when it was made by this device and driver (native memory, freed by the caller), else null. */
	private static @Nullable ByteBuffer load() {
		try {
			Path file = file();
			if (!Files.isRegularFile(file)) {
				return null;
			}
			byte[] bytes = Files.readAllBytes(file);
			ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
			// Ours, then Vulkan's header: length, version (1), vendor, device, cache UUID.
			if (bytes.length < OWN_HEADER + 16 + VK10.VK_UUID_SIZE || b.getInt(0) != MAGIC || b.getInt(4) != driverVersion
					|| b.getInt(OWN_HEADER + 4) != VK10.VK_PIPELINE_CACHE_HEADER_VERSION_ONE || b.getInt(OWN_HEADER + 8) != vendorId
					|| b.getInt(OWN_HEADER + 12) != deviceId) {
				return null;
			}
			for (int i = 0; i < VK10.VK_UUID_SIZE; i++) {
				if (bytes[OWN_HEADER + 16 + i] != UUID[i]) {
					return null;
				}
			}
			ByteBuffer data = MemoryUtil.memAlloc(bytes.length - OWN_HEADER);
			data.put(bytes, OWN_HEADER, bytes.length - OWN_HEADER).flip();
			return data;
		} catch (IOException | RuntimeException e) {
			Aetherium.LOG.warn("Pipeline cache: could not read the saved data", e);
			return null;
		}
	}

	private static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("aetherium").resolve("pipeline-cache.bin");
	}
}
