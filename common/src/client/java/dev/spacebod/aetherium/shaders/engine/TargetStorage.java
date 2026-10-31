package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import dev.spacebod.aetherium.client.gpu.VulkanAccess;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.gl.texture.InternalTextureFormat;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.TargetImages;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramArrayId;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramId;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ComputeSource;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSet;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
	private record Target(boolean shadow, int index) { }
	private enum Creation { STORAGE, MUTABLE }
	private static volatile boolean packUsesImages;
	private static volatile Set<Target> mutableTargets = Set.of();
	private static final ThreadLocal<Creation> CREATING = new ThreadLocal<>();
	private static final Map<GpuFormat, Boolean> SUPPORTED = new ConcurrentHashMap<>();

	private TargetStorage() {
	}

	/** At pack load, before its targets are created: whether any program binds a target as an image. */
	public static void configure(boolean usesImages) {
		mutableTargets = Set.of();
		packUsesImages = usesImages;
	}

	/** Only compute declarations that need another compatible format make their target mutable. */
	public static void configure(boolean usesImages, ProgramSet programs) {
		Set<Target> mutable = new HashSet<>();
		if (usesImages) {
			var directives = programs.getPackDirectives();
			for (String source : computeSourceTexts(programs)) {
				for (var declaration : TargetImageViews.formats(source).entrySet()) {
					var name = TargetImages.matcher(declaration.getKey());
					if (!name.matches()) {
						continue;
					}
					boolean shadow = TargetImages.shadow(name);
					int index = TargetImages.index(name);
					InternalTextureFormat format;
					if (shadow) {
						var setting = directives.getShadowDirectives().getColorSamplingSettings().get(index);
						format = setting == null ? InternalTextureFormat.RGBA : setting.getFormat();
					} else {
						var setting = directives.getRenderTargetDirectives().getRenderTargetSettings().get(index);
						format = setting == null ? InternalTextureFormat.RGBA : setting.getInternalFormat();
					}
					if (TargetImageViews.needsView(PackTargets.gpuFormat(format), declaration.getValue())) {
						mutable.add(new Target(shadow, index));
					}
				}
			}
		}
		mutableTargets = Set.copyOf(mutable);
		packUsesImages = usesImages;
	}

	static boolean active() {
		return packUsesImages;
	}

	/** Creates a pack target; with storage usage when the pack binds targets as images. */
	static <T> T create(Supplier<T> create) {
		return create(Creation.STORAGE, create);
	}

	/** Both sides of a colour target use the format requirements found before texture allocation. */
	static <T> T create(boolean shadow, int target, Supplier<T> create) {
		return create(mutableTargets.contains(new Target(shadow, target)) ? Creation.MUTABLE : Creation.STORAGE, create);
	}

	private static <T> T create(Creation creation, Supplier<T> create) {
		if (!packUsesImages) {
			return create.get();
		}
		Creation previous = CREATING.get();
		CREATING.set(creation);
		try {
			return create.get();
		} finally {
			if (previous == null) {
				CREATING.remove();
			} else {
				CREATING.set(previous);
			}
		}
	}

	/** {@code VulkanConst.textureUsageToVk} RETURN: storage usage while a pack target is created, when the format allows. */
	public static int adjust(int vkUsage, GpuFormat format) {
		if (CREATING.get() == null) {
			return vkUsage;
		}
		return supports(format) ? vkUsage | VK10.VK_IMAGE_USAGE_STORAGE_BIT : vkUsage;
	}

	/** Pack targets can be accessed through same-size image views of another format. */
	public static int imageFlags(int flags) {
		return CREATING.get() == Creation.MUTABLE ? flags | VK10.VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT : flags;
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
		for (ProgramArrayId stage : ProgramArrayId.values()) {
			for (ProgramSource source : programs.getComposite(stage)) {
				if (source != null) {
					sources.add(source);
				}
			}
		}
		for (ProgramSource source : sources) {
			for (Optional<String> text : List.of(source.getVertexSource(), source.getGeometrySource(), source.getFragmentSource())) {
				text.ifPresent(out::add);
			}
		}
		out.addAll(computeSourceTexts(programs));
		return out;
	}

	private static List<String> computeSourceTexts(ProgramSet programs) {
		List<ComputeSource[]> computes = new ArrayList<>();
		for (ProgramArrayId stage : ProgramArrayId.values()) {
			var staged = programs.getCompute(stage);
			if (staged != null) {
				computes.addAll(List.of(staged));
			}
		}
		computes.add(programs.getSetup());
		computes.add(programs.getShadowCompute());
		computes.add(programs.getFinalCompute());
		List<String> out = new ArrayList<>();
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
