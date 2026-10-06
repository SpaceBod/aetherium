package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import dev.spacebod.aetherium.client.gpu.VulkanAccess;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.TargetImages;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramArrayId;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramId;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ComputeSource;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSet;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkFormatProperties;

/**
 * Storage usage on the pack's colour and shadow colour targets, for {@code colorimgN} / {@code shadowcolorimgN} (the
 * targets bound as writable images). Vanilla's texture API has no storage usage, so the targets are created inside
 * {@link #create} and vanilla's usage conversion ({@code VulkanConst.textureUsageToVk}) adds it there, for formats the
 * device can store to. Only when the loaded pack binds targets as images: storage usage can cost render-target
 * compression on some devices. Vanilla keeps every texture in GENERAL layout, so no layout changes are needed.
 */
public final class TargetStorage {
	private static volatile boolean packUsesImages;
	private static final ThreadLocal<Boolean> CREATING = ThreadLocal.withInitial(() -> Boolean.FALSE);
	private static final Map<GpuFormat, Boolean> SUPPORTED = new ConcurrentHashMap<>();

	private TargetStorage() {
	}

	/** At pack load, before its targets are created: whether any program binds a target as an image. */
	public static void configure(boolean usesImages) {
		packUsesImages = usesImages;
	}

	static boolean active() {
		return packUsesImages;
	}

	/** Creates a pack target; with storage usage when the pack binds targets as images. */
	static <T> T create(Supplier<T> create) {
		if (!packUsesImages) {
			return create.get();
		}
		CREATING.set(Boolean.TRUE);
		try {
			return create.get();
		} finally {
			CREATING.set(Boolean.FALSE);
		}
	}

	/** {@code VulkanConst.textureUsageToVk} RETURN: storage usage while a pack target is created, when the format allows. */
	public static int adjust(int vkUsage, GpuFormat format) {
		if (!CREATING.get()) {
			return vkUsage;
		}
		return supports(format) ? vkUsage | VK10.VK_IMAGE_USAGE_STORAGE_BIT : vkUsage;
	}

	private static boolean supports(GpuFormat format) {
		return SUPPORTED.computeIfAbsent(format, f -> {
			var device = VulkanAccess.device();
			if (device == null) {
				return false;
			}
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkFormatProperties properties = VkFormatProperties.calloc(stack);
				VK10.vkGetPhysicalDeviceFormatProperties(device.vkDevice().getPhysicalDevice(), VulkanConst.toVk(f), properties);
				boolean ok = (properties.optimalTilingFeatures() & VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0;
				if (!ok) {
					AetheriumShaders.logger.warn("the graphics card cannot use {} targets as images; colorimg on them is not available", f);
				}
				return ok;
			}
		});
	}

	/** Whether any program of the set binds a colour or shadow colour target as an image. */
	public static boolean usedBy(ProgramSet programs) {
		for (String text : sourceTexts(programs)) {
			if (TargetImages.matcher(text).find()) {
				return true;
			}
		}
		return false;
	}

	/** The GLSL of every program of the set: world and full-screen stages, and compute programs. */
	public static List<String> sourceTexts(ProgramSet programs) {
		List<String> out = new ArrayList<>();
		List<ProgramSource> sources = new ArrayList<>();
		for (ProgramId id : ProgramId.values()) {
			programs.get(id).ifPresent(sources::add);
		}
		List<ComputeSource[]> computes = new ArrayList<>();
		for (ProgramArrayId stage : ProgramArrayId.values()) {
			for (ProgramSource source : programs.getComposite(stage)) {
				if (source != null) {
					sources.add(source);
				}
			}
			var staged = programs.getCompute(stage);
			if (staged != null) {
				computes.addAll(List.of(staged));
			}
		}
		computes.add(programs.getSetup());
		computes.add(programs.getShadowCompute());
		computes.add(programs.getFinalCompute());
		for (ProgramSource source : sources) {
			for (Optional<String> text : List.of(source.getVertexSource(), source.getGeometrySource(), source.getFragmentSource())) {
				text.ifPresent(out::add);
			}
		}
		for (var list : computes) {
			if (list != null) {
				for (var compute : list) {
					if (compute != null) {
						compute.getSource().ifPresent(out::add);
					}
				}
			}
		}
		return out;
	}
}
