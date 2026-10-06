package dev.spacebod.aetherium.client.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import dev.spacebod.aetherium.client.mixin.access.VulkanDeviceFeaturesAccessor;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Optional device features shader packs use, each requested as a feature set of its own when vanilla creates its
 * Vulkan device, so a device missing one keeps the others. What each capability needs:
 * <ul>
 *   <li>compute programs, and storage images and buffers read or written from them: nothing optional;</li>
 *   <li>image and buffer writes and atomics from vertex, geometry and tessellation stages: {@link #VERTEX_STORES};</li>
 *   <li>the same from fragment stages: {@link #FRAGMENT_STORES};</li>
 *   <li>storage images in the extended formats ({@code rg16f}, {@code r11f_g11f_b10f}, {@code rgba16}, {@code r8} ...):
 *       {@link #EXTENDED_FORMATS};</li>
 *   <li>image writes and loads with no format qualifier: {@link #WRITE_WITHOUT_FORMAT}, {@link #READ_WITHOUT_FORMAT};</li>
 *   <li>16 and 8 bit arithmetic and storage ({@code float16_t}, {@code int8_t} ...): {@link #NARROW}.</li>
 * </ul>
 */
public final class StorageFeatures {
	public static final FeatureSet VERTEX_STORES = vk10("vertexPipelineStoresAndAtomics");
	public static final FeatureSet FRAGMENT_STORES = vk10("fragmentStoresAndAtomics");
	public static final FeatureSet EXTENDED_FORMATS = vk10("shaderStorageImageExtendedFormats");
	public static final FeatureSet WRITE_WITHOUT_FORMAT = vk10("shaderStorageImageWriteWithoutFormat");
	public static final FeatureSet READ_WITHOUT_FORMAT = vk10("shaderStorageImageReadWithoutFormat");
	/** Per-attachment blend and write masks, which vanilla already sets for multi-target pipelines. */
	public static final FeatureSet INDEPENDENT_BLEND = vk10("independentBlend");
	/** 16 and 8 bit arithmetic, storage buffer access in those types, and subgroup operations over them. */
	public static final List<FeatureSet> NARROW = List.of(
			feature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "shaderFloat16"),
			feature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "shaderInt8"),
			vk10("shaderInt16"),
			feature(VulkanFeatureSets.VK11_FEATURES_STRUCT, "storageBuffer16BitAccess"),
			feature(VulkanFeatureSets.VK11_FEATURES_STRUCT, "uniformAndStorageBuffer16BitAccess"),
			feature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "storageBuffer8BitAccess"),
			feature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "uniformAndStorageBuffer8BitAccess"),
			feature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "shaderSubgroupExtendedTypes"));
	/** Every feature set requested, each optional on its own. */
	public static final List<FeatureSet> ALL;

	static {
		List<FeatureSet> all = new java.util.ArrayList<>(List.of(VERTEX_STORES, FRAGMENT_STORES, EXTENDED_FORMATS, WRITE_WITHOUT_FORMAT,
				READ_WITHOUT_FORMAT, INDEPENDENT_BLEND));
		all.addAll(NARROW);
		ALL = List.copyOf(all);
	}

	private StorageFeatures() {
	}

	private static FeatureSet vk10(String name) {
		return feature(VulkanFeatureSets.VK10_FEATURES_STRUCT, name);
	}

	private static FeatureSet feature(VulkanPNextStruct struct, String name) {
		return new FeatureSet("Aetherium " + name, Set.of(), Set.of(new VulkanFeature(struct, name)));
	}

	/** Compile harness only: report every feature as enabled without a device. */
	private static volatile boolean assumed;

	public static void assumeForCompileCheck() {
		assumed = true;
	}

	/** Whether the device enabled {@code feature} (one of this class's sets). */
	public static boolean has(FeatureSet feature) {
		if (assumed) {
			return true;
		}
		VulkanDevice device = device();
		return device != null && ((VulkanDeviceFeaturesAccessor) device).aetherium$enabledFeatures().contains(feature);
	}

	/** Vanilla's Vulkan device, or null (none yet, or OpenGL). */
	private static @Nullable VulkanDevice device() {
		return RenderSystem.tryGetDevice() == null ? null : VulkanAccess.device();
	}

	/**
	 * Compute programs and the pack storage set (custom images, storage buffers, raw textures, targets bound as images)
	 * can be used: any Vulkan device, no optional feature needed. Writes from graphics stages are gated per stage
	 * ({@link #vertexStores}, {@link #fragmentStores}).
	 */
	public static boolean enabled() {
		return assumed || device() != null;
	}

	/** Compute programs run (no optional feature needed). */
	public static boolean compute() {
		return enabled();
	}

	/** Vertex, geometry and tessellation stages may write storage images and buffers. */
	public static boolean vertexStores() {
		return has(VERTEX_STORES);
	}

	/** Fragment stages may write storage images and buffers (what full-screen and gbuffer programs use them for). */
	public static boolean fragmentStores() {
		return has(FRAGMENT_STORES);
	}

	/**
	 * Image load/store and storage buffers as packs use them (written from fragment programs, read anywhere): the
	 * {@code CUSTOM_IMAGES} and {@code SSBO} feature flags and their {@code MC_GL_ARB_*} macros.
	 */
	public static boolean imageLoadStore() {
		return enabled() && fragmentStores();
	}
}
