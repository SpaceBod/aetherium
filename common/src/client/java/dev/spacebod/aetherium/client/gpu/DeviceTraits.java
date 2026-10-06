package dev.spacebod.aetherium.client.gpu;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import dev.spacebod.aetherium.client.mixin.access.VulkanDeviceFeaturesAccessor;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceDriverProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Properties;
import org.jspecify.annotations.Nullable;

/**
 * What the Vulkan device is, beyond its enabled features: read from the physical device on first use and kept.
 * <p>
 * MoltenVK (macOS) translates every SPIR-V module to Metal Shading Language and runs it on Metal, whose limits Vulkan
 * does not name: 16 sampler slots per stage for pushed descriptors, no geometry stage, 32 KiB of threadgroup memory,
 * no linear filtering of 32-bit float textures, and its own compiler for pack names and arithmetic. Pack programs are
 * lowered for it ({@code LoweringParameters.Platform}) and bound for it ({@link AllocatedSets}).
 */
public final class DeviceTraits {
	/** {@code VK_DRIVER_ID_MOLTENVK}. */
	private static final int DRIVER_ID_MOLTENVK = 14;
	/** {@code VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT}. */
	private static final int FILTER_LINEAR = 0x1000;

	/**
	 * Comparison samplers in descriptors on a portability-subset device (MoltenVK). Every other device accepts them with
	 * nothing enabled; the set names the extension, so it is only enabled where the device has it.
	 */
	public static final FeatureSet COMPARISON_SAMPLERS = new FeatureSet("Aetherium comparison samplers", Set.of("VK_KHR_portability_subset"),
			Set.of(new VulkanFeature(VulkanFeatureSets.PORTABILITY_SUBSET_FEATURES_STRUCT, "mutableComparisonSamplers")));

	private record Traits(boolean moltenVk, boolean portability, int perStageSamplers, int allocatedSetSamplers) {
	}

	private static volatile @Nullable Traits traits;
	private static final Map<Integer, Boolean> LINEAR_FILTER = new ConcurrentHashMap<>();
	/** Compile harness only: the platform it stands in for, without a device. */
	private static volatile @Nullable Boolean assumedMoltenVk;

	private DeviceTraits() {
	}

	/** Compile harness only: answer as MoltenVK ({@code true}) or a desktop driver, without a device. */
	public static void assumeForCompileCheck(boolean moltenVk) {
		assumedMoltenVk = moltenVk;
	}

	/** Whether the device's driver is MoltenVK (Vulkan on Metal). */
	public static boolean moltenVk() {
		Boolean assumed = assumedMoltenVk;
		if (assumed != null) {
			return assumed;
		}
		Traits t = traits();
		return t != null && t.moltenVk();
	}

	/**
	 * Whether a comparison sampler may be written into a descriptor: always, except on a portability-subset device that
	 * did not enable {@link #COMPARISON_SAMPLERS}.
	 */
	public static boolean comparisonSamplers() {
		if (assumedMoltenVk != null) {
			return true;
		}
		Traits t = traits();
		if (t == null || !t.portability()) {
			return true;
		}
		VulkanDevice device = VulkanAccess.device();
		return device != null && ((VulkanDeviceFeaturesAccessor) device).aetherium$enabledFeatures().contains(COMPARISON_SAMPLERS);
	}

	/** Samplers one stage may read from a pushed descriptor set ({@code maxPerStageDescriptorSamplers}); 0 = unknown. */
	public static int perStageSamplers() {
		Traits t = traits();
		return t == null ? 0 : t.perStageSamplers();
	}

	/**
	 * Samplers one stage may read from an allocated descriptor set. On MoltenVK that is past the pushed limit only while
	 * it binds sets through argument buffers ({@code maxPerStageDescriptorUpdateAfterBindSamplers}); 0 = unknown.
	 */
	public static int allocatedSetSamplers() {
		Traits t = traits();
		return t == null ? 0 : t.allocatedSetSamplers();
	}

	/**
	 * Whether textures of {@code vkFormat} (optimal tiling) can be sampled with linear filtering. Vulkan requires it of
	 * only some formats; Metal filters no 32-bit float format. True without a device.
	 */
	public static boolean filtersLinearly(int vkFormat) {
		VulkanDevice device = VulkanAccess.device();
		if (device == null) {
			return true;
		}
		return LINEAR_FILTER.computeIfAbsent(vkFormat, format -> {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkFormatProperties properties = VkFormatProperties.calloc(stack);
				VK10.vkGetPhysicalDeviceFormatProperties(device.vkDevice().getPhysicalDevice(), format, properties);
				return (properties.optimalTilingFeatures() & FILTER_LINEAR) != 0;
			}
		});
	}

	private static @Nullable Traits traits() {
		Traits known = traits;
		if (known != null) {
			return known;
		}
		VulkanDevice device = VulkanAccess.device();
		if (device == null) {
			return null;
		}
		VkPhysicalDevice physical = device.vkDevice().getPhysicalDevice();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceDriverProperties driver = VkPhysicalDeviceDriverProperties.calloc(stack).sType$Default();
			VkPhysicalDeviceVulkan12Properties vk12 = VkPhysicalDeviceVulkan12Properties.calloc(stack).sType$Default().pNext(driver.address());
			VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(vk12.address());
			VK12.vkGetPhysicalDeviceProperties2(physical, properties);
			boolean portability = false;
			for (String extension : ((VulkanDeviceFeaturesAccessor) device).aetherium$enabledFeatures().extensions()) {
				portability |= extension.equals("VK_KHR_portability_subset");
			}
			known = new Traits(driver.driverID() == DRIVER_ID_MOLTENVK, portability,
					properties.properties().limits().maxPerStageDescriptorSamplers(), vk12.maxPerStageDescriptorUpdateAfterBindSamplers());
		}
		traits = known;
		return known;
	}
}
